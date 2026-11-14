package io.legado.web.routes

import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.legado.web.auth.AUTH_JWT
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.requireAdmin
import io.legado.web.auth.user
import io.legado.web.service.ZLibraryService
import io.legado.web.zlib.Book

/**
 * Z-Library 搜索下载（功能由管理员开关；搜索和下载都需要登录 Z-Library）。
 * - GET  /api/zlib/config                     状态：是否开启、是否已登录、账号
 * - GET  /api/zlib/account                     联网获取额度（已用 / 额度、重置倒计时）
 * - POST /api/zlib/test-proxy { proxy }        管理员测试代理连通性
 * - POST /api/zlib/login      { email, password }  登录
 * - POST /api/zlib/logout                      退出登录
 * - GET  /api/zlib/search?q=&page=&order=&ext=&lang=   搜索（需登录）
 * - POST /api/zlib/download   { book }         开始下载（需登录，异步），返回任务
 * - GET  /api/zlib/downloads                   下载任务列表（供“下载结果”小页面轮询）
 */
data class ProxyTestRequest(val proxy: String? = null)
data class ZlibLoginRequest(val email: String = "", val password: String = "")
data class ZlibDownloadRequest(val book: Book? = null, val groupId: Long = 0)

fun Route.zlibraryRoutes(service: ZLibraryService) {
    authenticate(AUTH_JWT) {
        get("/api/zlib/config") {
            call.respond(service.status(call.user))
        }
        // 管理员测试代理连通性（空表示直连）
        post("/api/zlib/test-proxy") {
            call.requireAdmin()
            service.testProxy(call.receive<ProxyTestRequest>().proxy)
            call.respond(OkResponse())
        }
        // 已登录时联网获取账号额度（已用 / 额度、重置倒计时）；未登录或失效返回 { account: null }
        get("/api/zlib/account") {
            call.respond(mapOf("account" to service.account(call.user)))
        }
        post("/api/zlib/login") {
            val req = call.receive<ZlibLoginRequest>()
            call.respond(service.login(call.user, req.email.trim(), req.password))
        }
        post("/api/zlib/logout") {
            service.logout(call.user)
            call.respond(OkResponse())
        }
        get("/api/zlib/search") {
            val q = call.request.queryParameters
            val books = service.search(
                call.user,
                query = q["q"]?.trim() ?: "",
                page = q["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                order = q["order"]?.trim()?.ifEmpty { "popular" } ?: "popular",
                extensions = q.getAll("ext")?.filter { it.isNotBlank() } ?: emptyList(),
                languages = q.getAll("lang")?.filter { it.isNotBlank() } ?: emptyList(),
            )
            call.respond(books)
        }
        post("/api/zlib/download") {
            val req = call.receive<ZlibDownloadRequest>()
            val book = req.book ?: throw BadRequestException("缺少书籍信息")
            call.respond(service.startDownload(call.user, book, req.groupId))
        }
        get("/api/zlib/downloads") {
            call.respond(service.jobs(call.user))
        }
    }
}
