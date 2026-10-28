package io.legado.web.service

import io.legado.web.security.ScriptUrls
import io.legado.app.data.entities.SearchBook
import io.legado.web.auth.UserPrincipal
import io.legado.web.config.AppSettings
import io.legado.web.engine.runEngine
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory

/**
 * 搜索结果（合并同名同作者的书）
 */
data class SearchResultItem(
    val name: String,
    val author: String,
    val kind: String?,
    val coverUrl: String?,
    val intro: String?,
    val wordCount: String?,
    val latestChapterTitle: String?,
    /** 首个来源 */
    val bookUrl: String,
    val origin: String,
    val originName: String,
    val sourceId: Long,
    val type: Int,
    val tocUrl: String,
    val variable: String?,
    val respondTime: Long,
)

sealed interface SearchEvent {
    data class Result(val books: List<SearchResultItem>) : SearchEvent
    data class Progress(val done: Int, val total: Int, val sourceName: String, val error: String?) : SearchEvent
}

class SearchService(
    private val sourceService: SourceService,
    private val settings: AppSettings,
) {
    private val logger = LoggerFactory.getLogger(SearchService::class.java)

    /**
     * 多书源并发搜索，结果逐个通过 [emit] 推送
     */
    suspend fun search(
        user: UserPrincipal,
        key: String,
        page: Int,
        group: String?,
        sourceIds: Set<Long>?,
        emit: suspend (SearchEvent) -> Unit,
    ) = coroutineScope {
        val sources = sourceService.enabledSources(user, group, sourceIds)
        val total = sources.size
        if (total == 0) {
            emit(SearchEvent.Progress(0, 0, "", "没有可用的书源"))
            return@coroutineScope
        }
        val semaphore = Semaphore(settings.searchConcurrency)
        val events = Channel<SearchEvent>(Channel.UNLIMITED)
        var done = 0
        val job = launch {
            sources.forEach { stored ->
                launch {
                    semaphore.withPermit {
                        val start = System.currentTimeMillis()
                        var error: String? = null
                        try {
                            val books = runEngine(user.id, settings.searchTimeout) {
                                WebBook.searchBookAwait(stored.source, key, page)
                            }
                            val time = System.currentTimeMillis() - start
                            if (books.isNotEmpty()) {
                                events.send(SearchEvent.Result(books.map { it.toItem(user.id, stored.id, time) }))
                            }
                            if (stored.ownerId == user.id) sourceService.updateRespondTime(stored.id, time)
                        } catch (e: CancellationException) {
                            // 超时也会抛出 TimeoutCancellationException（CancellationException 子类）
                            if (e is kotlinx.coroutines.TimeoutCancellationException) error = "超时" else throw e
                        } catch (e: Throwable) {
                            error = e.message ?: e.javaClass.simpleName
                            logger.debug("搜索失败 {}: {}", stored.source.bookSourceName, error)
                        }
                        events.send(SearchEvent.Progress(0, total, stored.source.bookSourceName, error))
                    }
                }
            }
        }
        job.invokeOnCompletion { events.close() }
        for (event in events) {
            if (event is SearchEvent.Progress) {
                done++
                emit(event.copy(done = done))
            } else {
                emit(event)
            }
        }
    }

    private fun SearchBook.toItem(userId: Long, sourceId: Long, time: Long) = SearchResultItem(
        name = name,
        author = author,
        kind = kind,
        coverUrl = coverUrl,
        intro = intro,
        wordCount = wordCount,
        latestChapterTitle = latestChapterTitle,
        bookUrl = bookUrl,
        origin = origin,
        originName = originName,
        sourceId = sourceId,
        type = type,
        tocUrl = tocUrl,
        variable = variable,
        respondTime = time,
    ).also { ScriptUrls.remember(userId, bookUrl, tocUrl, coverUrl) }

    /**
     * 单书源搜索（换源 / 发现）
     */
    suspend fun searchInSource(user: UserPrincipal, sourceId: Long, key: String, page: Int): List<SearchResultItem> {
        val stored = sourceService.get(user, sourceId)
        val start = System.currentTimeMillis()
        val books = runEngine(user.id, settings.searchTimeout) {
            WebBook.searchBookAwait(stored.source, key, page)
        }
        return books.map { it.toItem(user.id, stored.id, System.currentTimeMillis() - start) }
    }

    /**
     * 发现页。分类地址里的 js 可能只是打开网页 / 弹提示（如“登录番茄”），这些副作用一并返回给前端
     */
    suspend fun explore(user: UserPrincipal, sourceId: Long, url: String, page: Int): ExploreResult {
        val stored = sourceService.get(user, sourceId)
        sourceService.requireTrustedUrls(user, url)
        val start = System.currentTimeMillis()
        val ctx = io.legado.app.help.engine.EngineContext(user.id)
        val books = try {
            runEngine(user.id, settings.searchTimeout * 2, ctx = ctx) {
                WebBook.exploreBookAwait(stored.source, url, page)
            }
        } catch (e: Exception) {
            // 只是打开网页的分类，请求失败属于正常情况
            if (ctx.openUrls.isEmpty() && ctx.messages.isEmpty()) throw e
            emptyList()
        }
        return ExploreResult(
            books = books.map { it.toItem(user.id, stored.id, System.currentTimeMillis() - start) },
            messages = ctx.messages.toList(),
            openUrls = ctx.openUrls.distinct(),
        )
    }
}

data class ExploreResult(
    val books: List<SearchResultItem>,
    val messages: List<String>,
    val openUrls: List<String>,
)
