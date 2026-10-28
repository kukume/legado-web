package io.legado.web.service

import io.legado.app.model.webBook.WebBook
import io.legado.web.auth.UserPrincipal
import io.legado.web.config.AppSettings
import io.legado.web.engine.runEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 书源校验（与 App 的“校验书源”相同）：依次检查搜索 → 详情 → 目录 → 正文，
 * 失败的书源加上对应的分组（搜索失效、详情失效、目录失效、正文失效、校验超时），成功的去掉这些分组。
 */
class SourceCheckService(
    private val sourceService: SourceService,
    private val settings: AppSettings,
) {
    data class CheckResult(
        val id: Long,
        val name: String,
        val ok: Boolean,
        /** 失败时加上的分组，如“搜索失效” */
        val group: String?,
        val error: String?,
        /** 耗时（毫秒） */
        val time: Long,
        /** 分组是否已写回书源（公共书源普通用户不能修改） */
        val saved: Boolean,
    )

    private class CheckFailure(val group: String, message: String) : Exception(message)

    companion object {
        /** 单个书源的校验超时 */
        const val TIMEOUT = 90_000L
        const val DEFAULT_KEYWORD = "我的"
    }

    /**
     * 校验 [ids] 中的书源（只校验当前用户可用的），每校验完一个通过 [emit] 推送结果。
     * [keyword] 为空时用书源自己的校验关键字（ruleSearch.checkKeyWord），再没有就用“我的”
     */
    suspend fun check(
        user: UserPrincipal,
        ids: List<Long>,
        keyword: String?,
        emit: suspend (done: Int, total: Int, result: CheckResult) -> Unit,
    ) = coroutineScope {
        val visible = sourceService.list(user).map { it.id }.toSet()
        val targets = ids.filter { it in visible }.distinct()
        val total = targets.size
        if (total == 0) return@coroutineScope
        val semaphore = Semaphore(settings.searchConcurrency.coerceIn(1, 8))
        val results = Channel<CheckResult>(Channel.UNLIMITED)
        val job = launch {
            targets.forEach { id ->
                launch { semaphore.withPermit { results.send(checkOne(user, id, keyword)) } }
            }
        }
        job.invokeOnCompletion { results.close() }
        var done = 0
        for (r in results) emit(++done, total, r)
    }

    private suspend fun checkOne(user: UserPrincipal, id: Long, keyword: String?): CheckResult {
        val stored = sourceService.get(user, id)
        val source = stored.source
        val start = System.currentTimeMillis()
        var group: String? = null
        var error: String? = null
        try {
            runEngine(user.id, TIMEOUT) {
                if (source.searchUrl.isNullOrBlank()) {
                    // 只有发现的书源没有可校验的搜索，跳过（不改分组）
                    if (source.exploreUrl.isNullOrBlank()) throw CheckFailure("搜索失效", "书源没有搜索和发现地址")
                    error = "书源没有搜索，跳过校验"
                    return@runEngine
                }
                val key = keyword?.trim()?.takeIf { it.isNotEmpty() } ?: source.getCheckKeyword(DEFAULT_KEYWORD)
                val books = try {
                    WebBook.searchBookAwait(source, key)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw CheckFailure("搜索失效", e.message ?: e.javaClass.simpleName)
                }
                val first = books.firstOrNull() ?: throw CheckFailure("搜索失效", "搜索“$key”没有结果")
                var book = first.toBook()
                try {
                    if (book.tocUrl.isBlank()) book = WebBook.getBookInfoAwait(source, book)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw CheckFailure("详情失效", e.message ?: e.javaClass.simpleName)
                }
                val chapters = WebBook.getChapterListAwait(source, book).getOrElse {
                    throw CheckFailure("目录失效", it.message ?: it.javaClass.simpleName)
                }
                val chapter = chapters.firstOrNull { !it.isVolume } ?: throw CheckFailure("目录失效", "目录为空")
                val content = try {
                    WebBook.getContentAwait(source, book, chapter, needSave = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw CheckFailure("正文失效", e.message ?: e.javaClass.simpleName)
                }
                if (content.isBlank()) throw CheckFailure("正文失效", "正文为空")
            }
        } catch (e: TimeoutCancellationException) {
            group = "校验超时"
            error = "超过 ${TIMEOUT / 1000} 秒没有完成"
        } catch (e: CheckFailure) {
            group = e.group
            error = e.message
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            group = "搜索失效"
            error = e.message ?: e.javaClass.simpleName
        }
        val time = System.currentTimeMillis() - start
        val saved = runCatching { sourceService.applyCheckResult(user, id, group, time) }.getOrDefault(false)
        return CheckResult(id, source.bookSourceName, group == null, group, error?.lineSequence()?.firstOrNull()?.take(300), time, saved)
    }
}
