package io.legado.web.routes

import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
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
 * - POST /api/browser/{token}/mode   选择打开方式 { mode: remote | page }（书源登录、变量设置中）
 * - POST /api/browser/{token}/fetch   转发网页中的请求（只能访问书源自己的网站）
 * - POST /api/browser/{token}/cookie  网页中 document.cookie 写入的 Cookie
 * - WS   /api/browser/{token}/screen?token=&w=&h=   远程浏览器：画面、通知和用户操作走同一条 WebSocket
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
        post("/api/browser/{token}/mode") {
            val token = call.parameters["token"]!!
            bridge.chooseMode(call.user.id, token, call.receive<Map<String, String>>()["mode"].orEmpty())
            call.respond(OkResponse())
        }
        post("/api/browser/{token}/fetch") {
            val token = call.parameters["token"]!!
            val req = call.receive<BrowserBridge.ProxyRequest>()
            val uid = call.user.id
            call.respond(withContext(Dispatchers.IO) { bridge.fetch(uid, token, req) })
        }
        // 远程浏览器的画面和操作走同一条 WebSocket：服务器推送 JSON 文本（画面帧 k=f、通知 k=i），
        // 客户端发来操作 JSON。登录令牌经 ?token= 传（浏览器的 WebSocket 不能自定义请求头）
        webSocket("/api/browser/{id}/screen") {
            val id = call.parameters["id"]!!
            val uid = call.user.id
            suspend fun info(type: String, value: Any) =
                send(Frame.Text(GSON.toJson(mapOf("k" to "i", "type" to type, "value" to value))))
            try {
                bridge.remoteSession(uid, id)
            } catch (e: Exception) {
                info("error", e.message ?: "无法打开网页")
                return@webSocket
            }
            // 记录有人在看：连接全部断开一段时间后（刷新、关闭页面）自动关闭网页，不占着浏览器
            bridge.screenOpened(id)
            try {
                // 同一时间只打开一个网页：排队期间推送排在第几位
                val session = try {
                    bridge.awaitRemote(uid, id) { pos -> if (pos > 0) info("queue", pos) }
                } catch (e: Exception) {
                    info("error", e.message ?: "无法打开网页")
                    return@webSocket
                }
                // 按前端显示区域的大小设置网页视口
                val w = call.request.queryParameters["w"]?.toIntOrNull() ?: 0
                val h = call.request.queryParameters["h"]?.toIntOrNull() ?: 0
                if (w > 0 && h > 0) runCatching { session.input(RemoteSession.Input(type = "resize", width = w, height = h)) }
                coroutineScope {
                    val infoJob = launch {
                        info("ready", "")
                        // 重新连接（如刷新页面后）时补上当前地址
                        info("url", session.currentUrl)
                        info("mode", if (session.desktop) "desktop" else "mobile")
                        session.events.collect { (type, value) -> info(type, value) }
                    }
                    val frameJob = launch {
                        session.frames.filterNotNull().collect {
                            send(Frame.Text(GSON.toJson(mapOf("k" to "f", "data" to it.data, "width" to it.width, "height" to it.height, "offsetTop" to it.offsetTop))))
                        }
                    }
                    // 客户端发来的操作
                    val inputJob = launch {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val input = runCatching { GSON.fromJson(frame.readText(), RemoteSession.Input::class.java) }.getOrNull() ?: continue
                            // 单个操作失败（如调整大小时浏览器没回复）不能让后面的操作都失效
                            try {
                                session.input(input)
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                            }
                        }
                    }
                    session.awaitClosed()
                    infoJob.cancel()
                    frameJob.cancel()
                    inputJob.cancel()
                }
            } finally {
                bridge.screenClosed(id)
            }
        }
        post("/api/browser/{token}/cookie") {
            val token = call.parameters["token"]!!
            bridge.setPageCookie(call.user.id, token, call.receive<BrowserBridge.PageCookie>())
            call.respond(OkResponse())
        }
    }
}
