package io.legado.app.help.source

import com.script.ScriptBindings
import com.script.SharedGlobalStateHandle
import com.script.rhino.runScriptWithContext
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.constant.SourceType
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.CacheManager
import io.legado.app.model.SharedJsScope
import io.legado.app.utils.GSON
import io.legado.app.utils.InfoMap
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

fun BaseSource.getShareScope(coroutineContext: CoroutineContext? = null): ScriptBindings? {
    return SharedJsScope.getScope(jsLib, coroutineContext)
}

fun BaseSource.getSharedGlobalStateKey(): SharedGlobalStateHandle? {
    val library = jsLib?.takeIf { it.isNotBlank() } ?: return null
    val key = "${MD5Utils.md5Encode(library)}:${javaClass.name}:${MD5Utils.md5Encode(getKey())}"
    return ScriptBindings.getSharedGlobalStateHandle(key)
}

fun BaseSource.clearSharedGlobalState() {
    getSharedGlobalStateKey()?.let(ScriptBindings::removeSharedGlobalState)
}

fun clearSharedGlobalStateBySourceKey(
    sourceClass: Class<out BaseSource>,
    sourceKey: String,
) {
    ScriptBindings.removeSharedGlobalStatesBySource(
        sourceClass.name,
        MD5Utils.md5Encode(sourceKey),
    )
}

fun BaseSource.getSourceType(): Int {
    return when (this) {
        is BookSource -> SourceType.book
        else -> SourceType.rss
    }
}

// ---------------- 发现分类（服务端：缓存按用户隔离，放在 CacheManager 中） ----------------

private fun BookSource.getExploreKindsKey(): String {
    return "exploreKinds_" + MD5Utils.md5Encode(bookSourceUrl + exploreUrl)
}

/**
 * 当前用户在该书源发现页控件上的取值（与阅读 App 的 InfoMap 一致，持久化在 infoMap_<源地址>）
 */
fun BookSource.exploreInfoMap(): InfoMap = InfoMap(bookSourceUrl)

suspend fun BookSource.exploreKinds(infoMap: InfoMap = exploreInfoMap()): List<ExploreKind> {
    val exploreUrl = exploreUrl
    if (exploreUrl.isNullOrBlank()) {
        return emptyList()
    }
    val exploreKindsKey = getExploreKindsKey()
    val kinds = arrayListOf<ExploreKind>()
    withContext(Dispatchers.IO) {
        kotlin.runCatching {
            val ruleStr = when {
                exploreUrl.startsWith("@js:", true) -> {
                    CacheManager.get(exploreKindsKey)?.takeIf { it.isNotBlank() } ?: runScriptWithContext {
                        evalJS(exploreUrl.substring(4)) {
                            put("infoMap", infoMap)
                        }.toString().trim()
                    }.also {
                        CacheManager.put(exploreKindsKey, it)
                    }
                }

                exploreUrl.startsWith("<js>", true) -> {
                    CacheManager.get(exploreKindsKey)?.takeIf { it.isNotBlank() } ?: runScriptWithContext {
                        evalJS(exploreUrl.substring(4, exploreUrl.lastIndexOf("<"))) {
                            put("infoMap", infoMap)
                        }.toString().trim()
                    }.also {
                        CacheManager.put(exploreKindsKey, it)
                    }
                }

                else -> exploreUrl
            }
            if (ruleStr.isJsonArray()) {
                GSON.fromJsonArray<ExploreKind>(ruleStr).getOrThrow().let {
                    kinds.addAll(it)
                }
            } else {
                ruleStr.split("(&&|\n)+".toRegex()).forEach { kindStr ->
                    val kindCfg = kindStr.split("::")
                    kinds.add(ExploreKind(kindCfg.first(), kindCfg.getOrNull(1)))
                }
            }
        }.onFailure {
            kinds.add(ExploreKind("ERROR:${it.localizedMessage}", it.stackTraceToString()))
            it.printOnDebug()
        }
    }
    return kinds
}

fun BookSource.clearExploreKindsCache() {
    CacheManager.delete(getExploreKindsKey())
}

fun BookSource.exploreKindsJson(): String {
    return CacheManager.get(getExploreKindsKey())?.takeIf { it.isJsonArray() }
        ?: exploreUrl.takeIf { it.isJsonArray() }
        ?: ""
}

fun BookSource.getBookType(): Int {
    return when (bookSourceType) {
        BookSourceType.file -> BookType.text or BookType.webFile
        BookSourceType.image -> BookType.image
        BookSourceType.audio -> BookType.audio
        BookSourceType.video -> BookType.video
        else -> BookType.text
    }
}
