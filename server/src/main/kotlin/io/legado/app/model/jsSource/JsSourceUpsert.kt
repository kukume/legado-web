package io.legado.app.model.jsSource

import io.legado.app.data.entities.BookSource
import io.legado.app.help.ConcurrentRateLimiter.Companion.concurrentRecordMap
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.model.SharedJsScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

object JsSourceUpsert {

    const val MAX_SOURCE_BYTES = 1024 * 1024

    enum class PayloadIssue {
        EMPTY,
        TOO_LARGE,
    }

    private val saveMutex = Mutex()

    fun validatePayload(text: String?): PayloadIssue? {
        if (text.isNullOrBlank()) return PayloadIssue.EMPTY
        if (text.length > MAX_SOURCE_BYTES ||
            text.toByteArray(Charsets.UTF_8).size > MAX_SOURCE_BYTES
        ) {
            return PayloadIssue.TOO_LARGE
        }
        return null
    }

    /**
     * 解析纯 JS 书源文本并准备保存。
     * 服务端版本：不直接写数据库（由 Web 层 SourceService 保存），[old] 为已存在的同源书源。
     */
    suspend fun save(
        text: String,
        old: BookSource? = null,
        timeoutMillis: Long? = null,
    ): BookSource {
        return withSaveLock(timeoutMillis) {
            withContext(IO) {
                val source = if (timeoutMillis == null) {
                    JsSourceConfig.extract(text, currentCoroutineContext())
                } else {
                    withTimeout(timeoutMillis) {
                        JsSourceConfig.extract(text, currentCoroutineContext())
                    }
                }
                val changed = prepareForSave(source, old)
                if (!changed && old != null) {
                    return@withContext old
                }
                old?.let {
                    if (it.bookSourceUrl != source.bookSourceUrl ||
                        it.exploreUrl != source.exploreUrl
                    ) {
                        it.clearExploreKindsCache()
                    }
                    if (it.jsLib != source.jsLib) {
                        SharedJsScope.remove(it.jsLib)
                    }
                    concurrentRecordMap.remove(it.bookSourceUrl)
                }
                concurrentRecordMap.remove(source.bookSourceUrl)
                source
            }
        }
    }

    internal suspend fun <T> withSaveLock(
        timeoutMillis: Long? = null,
        block: suspend () -> T,
    ): T {
        if (timeoutMillis == null) {
            saveMutex.lock()
        } else {
            withTimeout(timeoutMillis) { saveMutex.lock() }
        }
        return try {
            block()
        } finally {
            saveMutex.unlock()
        }
    }

    internal fun prepareForSave(
        source: BookSource,
        old: BookSource?,
        stamp: Long = System.currentTimeMillis(),
    ): Boolean {
        preserveUserState(source, old)
        val changed = old == null || !equalIgnoringManagedUpdateTime(source, old)
        if (changed) {
            source.lastUpdateTime = stamp
            source.mainJs?.let { script ->
                JsSourceConfig.stampLastUpdateTime(script, stamp)?.let { source.mainJs = it }
            }
        } else {
            source.lastUpdateTime = old.lastUpdateTime
            source.mainJs = old.mainJs
        }
        return changed
    }

    internal fun equalIgnoringManagedUpdateTime(source: BookSource, old: BookSource): Boolean {
        val normalizedSource = source.copy(
            mainJs = normalizeManagedUpdateTime(source.mainJs),
        )
        val normalizedOld = old.copy(
            mainJs = normalizeManagedUpdateTime(old.mainJs),
        )
        return normalizedSource.equal(normalizedOld) &&
            normalizedSource.exploreScreen.orEmpty() == normalizedOld.exploreScreen.orEmpty() &&
            normalizedSource.ruleReview == normalizedOld.ruleReview &&
            normalizedSource.eventListener == normalizedOld.eventListener &&
            normalizedSource.customButton == normalizedOld.customButton
    }

    private fun normalizeManagedUpdateTime(script: String?): String? {
        return script?.let { JsSourceConfig.stampLastUpdateTime(it, 0) ?: it }
    }

    internal fun hasTargetConflict(
        openedSource: BookSource?,
        targetSource: BookSource?,
        targetUrl: String,
    ): Boolean {
        return openedSource != null &&
            openedSource.bookSourceUrl != targetUrl &&
            targetSource != null
    }

    internal fun preserveUserState(source: BookSource, old: BookSource?) {
        old ?: return
        source.enabled = old.enabled
        source.enabledExplore = old.enabledExplore
        source.customOrder = old.customOrder
        source.weight = old.weight
        source.respondTime = old.respondTime
        if (source.bookSourceGroup.isNullOrBlank()) {
            source.bookSourceGroup = old.bookSourceGroup
        }
    }
}
