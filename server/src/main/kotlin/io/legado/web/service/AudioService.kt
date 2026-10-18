package io.legado.web.service

import io.legado.app.help.engine.EngineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl

import io.legado.web.auth.BadRequestException
import io.legado.web.auth.UserPrincipal
import io.legado.web.engine.runEngine
import io.legado.web.routes.compactGson
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/**
 * 音频书源的播放：正文是音频地址（可带 `,{"headers":…}` 参数），由服务器带上书源的请求头、Cookie 转发，
 * 支持 Range（拖动进度）。m3u8 播放列表中的分片地址改写为经过本服务转发的地址（带签名，只能转发列表中出现过的地址）。
 */
class AudioService(private val shelfService: ShelfService) {

    /** 上游响应（调用方负责关闭） */
    class Upstream(val response: Response, val playlist: String?)

    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun sign(userId: Long, bookId: Long, index: Int, target: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val digest = mac.doFinal("$userId|$bookId|$index|$target".toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.copyOf(18))
    }

    private fun verify(userId: Long, bookId: Long, index: Int, target: String, sig: String): Boolean =
        MessageDigest.isEqual(sign(userId, bookId, index, target).toByteArray(), sig.toByteArray())

    /** 正文中的音频地址（取第一行非空内容） */
    private suspend fun audioUrl(user: UserPrincipal, bookId: Long, index: Int, refresh: Boolean): String {
        val raw = shelfService.rawContent(user, bookId, index, refresh)
        // 一般正文就是地址；聚合书源的短剧可能是“开启播放…播放直链：<网址>”
        val url = io.legado.web.service.detectVideoUrl(raw)
            ?: raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        if (!url.startsWith("http", true)) {
            throw BadRequestException(if (url.isEmpty()) "没有获取到音频地址" else "正文不是音频地址：${url.take(80)}")
        }
        return url
    }

    /** 播放信息：是否为 m3u8（前端需要用 hls.js 播放） */
    suspend fun info(user: UserPrincipal, bookId: Long, index: Int, refresh: Boolean): Map<String, Any> {
        val url = audioUrl(user, bookId, index, refresh)
        val path = url.substringBefore(",{").substringBefore('?').lowercase()
        return mapOf("hls" to path.endsWith(".m3u8"))
    }

    /**
     * 打开上游音频。[segment]/[sig] 为 m3u8 改写后的分片地址；否则使用章节正文中的音频地址。
     * 使用缓存的地址失败时（可能已过期）自动刷新一次。
     */
    suspend fun open(
        user: UserPrincipal, bookId: Long, index: Int,
        range: String?, refresh: Boolean, segment: String?, sig: String?,
        /** 改写 m3u8 时附加到分片地址上的登录凭据（Safari 原生播放 m3u8 无法添加请求头） */
        token: String?,
    ): Upstream {
        if (segment != null) {
            if (sig == null || !verify(user.id, bookId, index, segment, sig)) throw BadRequestException("地址签名无效，请重新打开")
            return fetch(user, bookId, index, segment, range, token)
        }
        // 同一集的请求排队发往上游：浏览器播放时会连续发出多个请求（首次加载、拖动进度的 Range 请求、出错重试），
        // 有的书源地址是后台“边请求边解密 / 转码”的接口（如大灰狼短剧），并发请求会让后台同时跑多个任务而失败
        return lockFor("${user.id}:$bookId:$index").withLock {
            val first = runCatching { fetch(user, bookId, index, audioUrl(user, bookId, index, refresh), range, token) }
            first.getOrNull()?.let { up ->
                // 416：请求的范围超出文件长度，与地址是否过期无关
                if (up.response.isSuccessful || up.response.code == 416 || refresh) return@withLock up
                up.response.close()
            }
            if (refresh) throw first.exceptionOrNull() ?: BadRequestException("音频加载失败")
            fetch(user, bookId, index, audioUrl(user, bookId, index, true), range, token)
        }
    }

    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lockFor(key: String): Mutex {
        if (locks.size > 2000) locks.entries.removeIf { !it.value.isLocked }
        return locks.computeIfAbsent(key) { Mutex() }
    }

    private suspend fun fetch(user: UserPrincipal, bookId: Long, index: Int, target: String, range: String?, token: String?): Upstream {
        val (source, book, chapter) = shelfService.chapterContext(user, bookId, index)
        // 加载音频时不应弹出书源网页
        val ctx = EngineContext(user.id).apply { suppressUi = true }
        return runEngine(user.id, 60_000, ctx = ctx) {
            val analyzeUrl = AnalyzeUrl(
                target,
                source = source,
                ruleData = book,
                chapter = chapter,
                // 音频可能很长：不限制整次请求的时间（每次读取仍有超时）
                callTimeout = 0L,
                coroutineContext = coroutineContext,
            )
            if (!range.isNullOrBlank()) analyzeUrl.headerMap["Range"] = range
            val res = analyzeUrl.getResponseAwait()
            val type = res.header("Content-Type").orEmpty().lowercase()
            val finalUrl = res.request.url
            val isPlaylist = type.contains("mpegurl") ||
                (finalUrl.encodedPath.lowercase().endsWith(".m3u8") && !type.startsWith("audio/") && !type.startsWith("video/"))
            if (!isPlaylist || !res.isSuccessful) return@runEngine Upstream(res, null)
            // m3u8：改写其中的地址。分片请求带上与播放列表相同的自定义请求头（不含 Cookie，Cookie 每次请求时按书源重新附加）
            val text = res.use { r ->
                r.body.byteStream().use { String(it.readNBytes(4 * 1024 * 1024), Charsets.UTF_8) }
            }
            val headers = analyzeUrl.headerMap.filterKeys { !it.equals("Cookie", true) && !it.equals("Range", true) }
            val playlist = rewritePlaylist(text, finalUrl.toString()) { abs ->
                val spec = if (headers.isEmpty()) abs else abs + "," + compactGson.toJson(mapOf("headers" to headers))
                "/api/shelf/$bookId/audio/$index?u=${enc(spec)}&sig=${sign(user.id, bookId, index, spec)}" + (token?.let { "&token=${enc(it)}" } ?: "")
            }
            Upstream(res, playlist)
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private val uriAttr = Regex("""URI="([^"]+)"""")

    private fun rewritePlaylist(text: String, base: String, proxy: (String) -> String): String {
        val baseUrl = base.toHttpUrlOrNull() ?: return text
        fun abs(u: String) = baseUrl.resolve(u.trim())?.toString()
        return text.lineSequence().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> uriAttr.replace(line) { m ->
                    abs(m.groupValues[1])?.let { "URI=\"${proxy(it)}\"" } ?: m.value
                }
                else -> abs(t)?.let(proxy) ?: line
            }
        }
    }
}
