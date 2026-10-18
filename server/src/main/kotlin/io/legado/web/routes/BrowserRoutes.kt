package io.legado.web.routes

import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import io.legado.web.auth.AUTH_JWT
import io.legado.web.auth.user
import io.legado.web.service.BrowserBridge
import io.legado.web.webview.RemoteSession
import io.legado.app.utils.GSON
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * - GET  /api/events?token=&client=   SSE：推送书源脚本打开的网页 / 验证码（client 为标签页 id）
 * - POST /api/browser/{token}   提交用户操作结果 { url, body, cancel }
 * - POST /api/browser/{token}/fetch   转发网页中的请求（只能访问书源自己的网站）
 * - POST /api/browser/{token}/cookie  网页中 document.cookie 写入的 Cookie
 * - GET  /api/browser/{token}/screen?token=&w=&h=   SSE：远程浏览器的实时画面（frame）和通知（info）
 * - POST /api/browser/{token}/input   远程浏览器：用户的点击、滚动、输入
 */
fun Route.browserRoutes(bridge: BrowserBridge) {
    authenticate(AUTH_JWT) {
        sse("/api/events") {
            val uid = call.user.id
            val ch = bridge.register(uid, call.request.queryParameters["client"]?.takeIf { it.length in 8..64 })
            heartbeat { period = 25.seconds }
            try {
                for (msg in ch) send(ServerSentEvent(data = msg, event = "browser"))
            } finally {
                bridge.unregister(uid, ch)
            }
        }
        post("/api/browser/{token}") {
            val token = call.parameters["token"]!!
            bridge.submit(call.user.id, token, call.receive<BrowserBridge.BrowserResult>())
            call.respond(OkResponse())
        }
        post("/api/browser/{token}/fetch") {
            val token = call.parameters["token"]!!
            val req = call.receive<BrowserBridge.ProxyRequest>()
            val uid = call.user.id
            call.respond(withContext(Dispatchers.IO) { bridge.fetch(uid, token, req) })
        }
        sse("/api/browser/{id}/screen") {
            // 路径参数不能叫 token：SSE 用网址里的 ?token= 传登录令牌，同名时取到的是登录令牌
            val id = call.parameters["id"]!!
            val uid = call.user.id
            val info = { type: String, value: Any -> ServerSentEvent(data = GSON.toJson(mapOf("type" to type, "value" to value)), event = "info") }
            try {
                bridge.remoteSession(uid, id)
            } catch (e: Exception) {
                send(info("error", e.message ?: "无法打开网页"))
                return@sse
            }
            // 记录有人在看：连接全部断开一段时间后（刷新、关闭页面）自动关闭网页，不占着浏览器
            bridge.screenOpened(id)
            try {
                // 同一时间只打开一个网页：排队期间推送排在第几位
                val session = try {
                    bridge.awaitRemote(uid, id) { pos -> if (pos > 0) send(info("queue", pos)) }
                } catch (e: Exception) {
                    send(info("error", e.message ?: "无法打开网页"))
                    return@sse
                }
                // 按前端显示区域的大小设置网页视口
                val w = call.request.queryParameters["w"]?.toIntOrNull() ?: 0
                val h = call.request.queryParameters["h"]?.toIntOrNull() ?: 0
                if (w > 0 && h > 0) session.input(RemoteSession.Input(type = "resize", width = w, height = h))
                heartbeat { period = 25.seconds }
                coroutineScope {
                    val infoJob = launch {
                        send(info("ready", ""))
                        // 重新连接（如刷新页面后）时补上当前地址
                        send(info("url", session.currentUrl))
                        for ((type, value) in session.events) send(info(type, value))
                    }
                    val frameJob = launch {
                        session.frames.filterNotNull().collect { send(ServerSentEvent(data = GSON.toJson(it), event = "frame")) }
                    }
                    session.awaitClosed()
                    infoJob.cancel()
                    frameJob.cancel()
                }
            } finally {
                bridge.screenClosed(id)
            }
        }
        post("/api/browser/{id}/input") {
            val input = call.receive<RemoteSession.Input>()
            // 还在排队时忽略操作
            bridge.remoteSession(call.user.id, call.parameters["id"]!!)?.input(input)
            call.respond(OkResponse())
        }
        post("/api/browser/{token}/cookie") {
            val token = call.parameters["token"]!!
            bridge.setPageCookie(call.user.id, token, call.receive<BrowserBridge.PageCookie>())
            call.respond(OkResponse())
        }
    }
}
