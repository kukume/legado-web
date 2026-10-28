package io.legado.web.service

import io.legado.app.data.entities.Book
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.isDataUrl
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.UserPrincipal
import io.legado.web.engine.runEngine
import kotlin.coroutines.coroutineContext

data class ProxiedImage(val bytes: ByteArray, val contentType: String?)

class ProxyService(
    private val sourceService: SourceService,
    private val shelfService: ShelfService,
) {

    /**
     * 通过书源（带请求头、cookie、并发限制）获取图片，支持封面解密和正文图片解密 js
     * @param sourceId 书源 id（可选）
     * @param bookId 书籍 id（正文图片解密时使用）
     * @param cover 是否封面
     */
    suspend fun image(
        user: UserPrincipal,
        url: String,
        sourceId: Long?,
        origin: String?,
        bookId: Long?,
        cover: Boolean,
    ): ProxiedImage {
        if (!url.startsWith("http", true) && !url.isDataUrl()) throw BadRequestException("图片地址无效")
        sourceService.requireTrustedUrls(user, url)
        val book: Book? = bookId?.let { runCatching { shelfService.engineBook(user, it) }.getOrNull() }
        val stored = sourceId?.let { runCatching { sourceService.get(user, it) }.getOrNull() }
            ?: (origin ?: book?.origin)?.let { sourceService.findByUrl(user, it) }
        val source = stored?.source
        // 图片地址参数里的 js 会在加载时执行（与 App 相同），但加载图片不应弹出书源网页
        val ctx = io.legado.app.help.engine.EngineContext(user.id).apply { suppressUi = true }
        return runEngine(user.id, 60_000, ctx = ctx) {
            val analyzeUrl = AnalyzeUrl(url, source = source, ruleData = book, coroutineContext = coroutineContext)
            var contentType: String? = null
            var bytes = if (url.isDataUrl()) {
                analyzeUrl.getByteArrayAwait()
            } else {
                val res = analyzeUrl.getResponseAwait()
                contentType = res.header("Content-Type")
                res.body.bytes()
            }
            val decodeJs = if (cover) source?.coverDecodeJs else source?.getContentRule()?.imageDecode
            if (!decodeJs.isNullOrBlank() && source != null) {
                val rule = AnalyzeRule(book, source).setCoroutineContext(coroutineContext)
                rule.setContent(url)
                val decoded = rule.evalJS(decodeJs, bytes)
                if (decoded is ByteArray) {
                    bytes = decoded
                    contentType = null
                }
            }
            ProxiedImage(bytes, contentType ?: sniff(bytes))
        }
    }

    private fun sniff(b: ByteArray): String {
        fun startsWith(vararg sig: Int) = b.size >= sig.size && sig.indices.all { (b[it].toInt() and 0xff) == sig[it] }
        return when {
            startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
            startsWith(0x89, 0x50, 0x4E, 0x47) -> "image/png"
            startsWith(0x47, 0x49, 0x46) -> "image/gif"
            b.size > 12 && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            startsWith(0x42, 0x4D) -> "image/bmp"
            else -> "application/octet-stream"
        }
    }
}
