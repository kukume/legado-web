package io.legado.web.routes

import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.server.auth.authenticate
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.legado.web.auth.AUTH_JWT
import io.legado.web.auth.user
import io.legado.web.service.AudioService
import io.legado.web.service.TtsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Response

data class SpeakRequest(val text: String = "", val speed: Int = 10)

/** 音频响应只允许媒体类型；直接打开地址时禁止内容在本站执行 */
private fun safeMediaType(type: String?): ContentType {
    val t = type?.substringBefore(";")?.trim()?.lowercase().orEmpty()
    val ok = t.startsWith("audio/") || t.startsWith("video/") || t.contains("mpegurl") || t == "video/mp2t"
    return if (ok) runCatching { ContentType.parse(t) }.getOrDefault(ContentType.Application.OctetStream)
    else ContentType.Application.OctetStream
}

private fun ApplicationCallSecurityHeaders(call: io.ktor.server.application.ApplicationCall) {
    call.response.header("Content-Security-Policy", "sandbox; default-src 'none'")
    call.response.header("X-Content-Type-Options", "nosniff")
    call.response.header(HttpHeaders.CacheControl, "private, no-store")
}

/** 把上游响应原样转发（状态码、长度、Content-Range），边读边写 */
private class UpstreamContent(private val res: Response) : OutgoingContent.WriteChannelContent() {
    override val status = HttpStatusCode.fromValue(res.code)
    override val contentType = safeMediaType(res.header("Content-Type"))
    override val contentLength = res.body.contentLength().takeIf { it >= 0 }
    override val headers: Headers = headersOf(
        *listOfNotNull(
            res.header("Content-Range")?.let { HttpHeaders.ContentRange to listOf(it) },
            res.header("Accept-Ranges")?.let { HttpHeaders.AcceptRanges to listOf(it) },
        ).toTypedArray()
    )

    override suspend fun writeTo(channel: ByteWriteChannel) {
        res.use { r ->
            r.body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = withContext(Dispatchers.IO) { input.read(buf) }
                    if (n < 0) break
                    channel.writeFully(buf, 0, n)
                }
            }
        }
    }
}

/**
 * - GET  /api/shelf/{id}/audio/{index}/info?refresh=   播放信息（是否为 m3u8）
 * - GET  /api/shelf/{id}/audio/{index}?token=&refresh=  音频（经服务器转发，支持 Range）；m3u8 分片地址带 u、sig
 * - /api/tts/…  在线朗读引擎：列表、导入、导出、删除、合成语音
 */
fun Route.mediaRoutes(audioService: AudioService, ttsService: TtsService) {
    authenticate(AUTH_JWT) {
        get("/api/shelf/{id}/audio/{index}/info") {
            val q = call.request.queryParameters
            call.respond(audioService.info(call.user, call.longParam("id"), call.intParam("index"), q["refresh"] == "1"))
        }
        get("/api/shelf/{id}/audio/{index}") {
            val q = call.request.queryParameters
            val token = q["token"] ?: call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            val up = audioService.open(
                call.user, call.longParam("id"), call.intParam("index"),
                call.request.header(HttpHeaders.Range), q["refresh"] == "1", q["u"], q["sig"], token,
            )
            ApplicationCallSecurityHeaders(call)
            val playlist = up.playlist
            if (playlist != null) {
                call.respondText(playlist, ContentType.parse("application/vnd.apple.mpegurl"))
            } else {
                call.respond(UpstreamContent(up.response))
            }
        }

        get("/api/tts") { call.respond(ttsService.list(call.user)) }
        get("/api/tts/export") { call.respond(ttsService.export(call.user)) }
        post("/api/tts/import") { call.respond(ttsService.import(call.user, call.receiveText())) }
        delete("/api/tts/{id}") {
            ttsService.delete(call.user, call.longParam("id"))
            call.respond(OkResponse())
        }
        post("/api/tts/{id}/speak") {
            val req = call.receive<SpeakRequest>()
            val audio = ttsService.speak(call.user, call.longParam("id"), req.text, req.speed)
            ApplicationCallSecurityHeaders(call)
            call.respondBytes(audio.bytes, safeMediaType(audio.contentType))
        }
    }
}
