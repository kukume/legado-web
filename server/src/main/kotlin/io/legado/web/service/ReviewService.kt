package io.legado.web.service

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.ReviewRuleParser
import io.legado.app.model.jsSource.JsSourceReview
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.UserPrincipal
import io.legado.web.engine.runEngine
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** 段评统计：段落序号 -> 评论数（-1 为本章说，显示在章节标题后）；keys 为详情请求时传回的段落数据 */
data class ReviewSummaryDto(val counts: Map<Int, Int>, val keys: Map<Int, String>)

data class ReviewItemDto(
    val id: String?,
    val avatar: String?,
    val name: String?,
    val replyToName: String?,
    val badges: List<String>,
    val content: String?,
    val imageUrl: String?,
    val audioUrl: String?,
    val time: String?,
    val likeCount: Int?,
    val replyCount: Int?,
    val replies: List<ReviewItemDto>,
)

data class ReviewPageDto(
    val items: List<ReviewItemDto>,
    /** 下一页游标（规则书源） */
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

/**
 * 书源的原生段评（ruleReview，或纯 JS 书源的 getReviewSummary / getReviewDetail / getReviewReplies），
 * 与 App 的 ReviewController 一致：
 * - 统计：reviewSummaryUrl → summaryListRule 等规则解析出每段的评论数
 * - 详情：reviewDetailUrl（可用 paraIndex / paraData / page），下一页地址由 reviewDetailNextPageUrl 解析，以游标方式翻页
 * - 回复：reviewQuoteUrl（可用 reviewId）
 */
class ReviewService(private val shelfService: ShelfService) {

    private data class CursorKey(val userId: Long, val bookId: Long, val chapterIndex: Int, val sourceKey: String, val ruleHash: Int, val para: Int, val paraData: String)
    private data class Cursor(val key: CursorKey, val page: Int, val url: String, val time: Long = System.currentTimeMillis())

    private val cursors = ConcurrentHashMap<String, Cursor>()

    companion object {
        /** 书源是否提供段评（前端据此决定是否请求统计） */
        fun hasReview(source: BookSource?): Boolean {
            source ?: return false
            if (source.isJsSource()) return source.mainJs?.contains("getReviewSummary") == true
            val rule = source.ruleReview ?: return false
            return rule.enabled && !rule.reviewSummaryUrl.isNullOrBlank() &&
                !rule.summaryListRule.isNullOrBlank() && !rule.summaryParagraphIndexRule.isNullOrBlank() &&
                !rule.summaryCountRule.isNullOrBlank()
        }

        private fun ReviewRuleParser.DetailItem.toDto(): ReviewItemDto = ReviewItemDto(
            id, avatar, name, replyToName, badges, content, imageUrl, audioUrl, time, likeCount, replyCount,
            replies.map { it.toDto() },
        )
    }

    suspend fun summary(user: UserPrincipal, bookId: Long, chapterIndex: Int): ReviewSummaryDto {
        val (source, book, chapter) = shelfService.chapterContext(user, bookId, chapterIndex)
        if (!hasReview(source)) return ReviewSummaryDto(emptyMap(), emptyMap())
        val result = runEngine(user.id, 60_000) {
            if (source.isJsSource()) {
                JsSourceReview.getReviewSummaryAwait(source, book, chapter)
            } else {
                val rule = source.ruleReview!!
                val analyzeUrl = AnalyzeUrl(
                    rule.reviewSummaryUrl!!,
                    baseUrl = chapter.url,
                    source = source,
                    ruleData = book,
                    chapter = chapter,
                    coroutineContext = coroutineContext,
                )
                val body = analyzeUrl.getStrResponseAwait(useWebView = false).body.orEmpty()
                ReviewRuleParser.parseSummary(body, rule, source, book, chapter, analyzeUrl.url, coroutineContext)
            }
        }
        return ReviewSummaryDto(result?.counts.orEmpty(), result?.keys.orEmpty())
    }

    suspend fun detail(
        user: UserPrincipal, bookId: Long, chapterIndex: Int,
        para: Int, paraData: String?, page: Int, cursor: String?,
    ): ReviewPageDto {
        if (para == 0 || para < -1) throw BadRequestException("段落序号无效")
        val (source, book, chapter) = shelfService.chapterContext(user, bookId, chapterIndex)
        val data = paraData?.takeIf { it.isNotEmpty() } ?: para.toString()
        return runEngine(user.id, 60_000) {
            if (source.isJsSource()) {
                val r = JsSourceReview.getReviewDetailAwait(source, book, chapter, para, data, page)
                    ?: return@runEngine ReviewPageDto(emptyList())
                return@runEngine ReviewPageDto(
                    r.items.map { it.toDto() },
                    hasMore = r.items.isNotEmpty() && !r.nextPageUrl.isNullOrBlank(),
                )
            }
            val rule = source.ruleReview ?: throw BadRequestException("书源没有段评规则")
            if (!rule.enabled) throw BadRequestException("段评规则未启用")
            if (rule.detailListRule.isNullOrBlank() || rule.detailContentRule.isNullOrBlank()) throw BadRequestException("段评详情规则不完整")
            val firstUrl = rule.reviewDetailUrl?.takeIf { it.isNotBlank() } ?: throw BadRequestException("段评详情地址未配置")
            val nextRule = rule.reviewDetailNextPageUrl?.takeIf { it.isNotBlank() }
            val key = CursorKey(user.id, bookId, chapterIndex, source.getKey(), rule.hashCode(), para, data)
            val url = if (page <= 1) firstUrl else {
                val c = cursor?.let { cursors[it] }
                if (c == null || c.key != key || c.page != page) throw BadRequestException("分页已过期，请重新打开")
                c.url
            }
            val analyzeUrl = AnalyzeUrl(
                url,
                page = page,
                extraParams = mapOf("paraIndex" to para.toString(), "paraData" to data, "page" to page.toString()),
                baseUrl = chapter.url,
                source = source,
                ruleData = book,
                chapter = chapter,
                coroutineContext = coroutineContext,
            )
            val body = analyzeUrl.getStrResponseAwait(useWebView = false).body.orEmpty()
            val result = ReviewRuleParser.parseDetailPage(
                body, rule, nextRule, analyzeUrl.url, source, book, chapter, coroutineContext,
                para.toString(), data, page.toString(),
            )
            val next = result.nextPageUrl?.takeIf { result.items.isNotEmpty() && it.isNotBlank() }?.let { nextUrl ->
                cleanupCursors()
                UUID.randomUUID().toString().also { cursors[it] = Cursor(key, page + 1, nextUrl) }
            }
            ReviewPageDto(result.items.map { it.toDto() }, next, next != null)
        }
    }

    suspend fun replies(
        user: UserPrincipal, bookId: Long, chapterIndex: Int,
        para: Int, paraData: String?, reviewId: String, page: Int,
    ): ReviewPageDto {
        if (reviewId.isBlank()) throw BadRequestException("reviewId 不能为空")
        val (source, book, chapter) = shelfService.chapterContext(user, bookId, chapterIndex)
        val data = paraData?.takeIf { it.isNotEmpty() } ?: para.toString()
        return runEngine(user.id, 60_000) {
            if (source.isJsSource()) {
                val items = JsSourceReview.getReviewRepliesAwait(source, book, chapter, para, data, reviewId, page).orEmpty()
                return@runEngine ReviewPageDto(items.map { it.toDto() }, hasMore = items.isNotEmpty())
            }
            val rule = source.ruleReview ?: return@runEngine ReviewPageDto(emptyList())
            val replyUrl = rule.reviewQuoteUrl?.takeIf { it.isNotBlank() } ?: return@runEngine ReviewPageDto(emptyList())
            if (!rule.enabled || rule.replyListRule.isNullOrBlank() || rule.replyContentRule.isNullOrBlank()) {
                return@runEngine ReviewPageDto(emptyList())
            }
            val analyzeUrl = AnalyzeUrl(
                replyUrl,
                page = page,
                extraParams = mapOf("paraIndex" to para.toString(), "paraData" to data, "reviewId" to reviewId, "page" to page.toString()),
                baseUrl = chapter.url,
                source = source,
                ruleData = book,
                chapter = chapter,
                coroutineContext = coroutineContext,
            )
            val body = analyzeUrl.getStrResponseAwait(useWebView = false).body?.takeIf { it.isNotBlank() }
                ?: throw BadRequestException("段评回复内容为空")
            val items = ReviewRuleParser.parseReplyPage(
                body, rule, analyzeUrl.url, source, book, chapter, coroutineContext, para.toString(), data, page.toString(),
            )
            ReviewPageDto(items.map { it.toDto() }, hasMore = items.isNotEmpty())
        }
    }

    /** 游标保留 30 分钟 */
    private fun cleanupCursors() {
        val expire = System.currentTimeMillis() - 30 * 60_000
        cursors.entries.removeIf { it.value.time < expire }
    }
}
