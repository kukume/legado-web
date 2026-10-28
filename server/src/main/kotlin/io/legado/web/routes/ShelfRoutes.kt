package io.legado.web.routes

import io.ktor.server.response.respondFile
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import io.ktor.utils.io.readAvailable
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.legado.web.auth.AUTH_JWT
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.user
import io.legado.web.db.UserPrefs
import io.legado.web.db.dbQuery
import io.legado.web.service.OpenBookRequest
import io.legado.web.service.ProxyService
import io.legado.web.service.ReplaceRuleDto
import io.legado.web.service.ReplaceRuleService
import io.legado.web.service.ShelfService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

/** time：客户端记录进度的时间（毫秒）。离线阅读同步时传入，比服务器上的进度旧就忽略 */
data class ImageClickRequest(val src: String = "")
/** time：客户端记录进度的时间（毫秒）。离线阅读同步时传入，比服务器上的进度旧就忽略 */
data class ProgressRequest(val index: Int = 0, val pos: Int = 0, val title: String? = null, val time: Long? = null)
data class GroupRequest(val name: String = "", val order: Int = 0)
data class CacheRequest(val start: Int = 0, val count: Int = 50)
data class IdsRequest(val ids: List<Long> = emptyList())

private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

data class TxtTocRequest(val tocUrl: String = "")

data class OrderRequest(val ids: List<Long> = emptyList())

fun Route.shelfRoutes(
    shelfService: ShelfService,
    replaceService: ReplaceRuleService,
    proxyService: ProxyService,
    configService: io.legado.web.service.SourceConfigService,
    reviewService: io.legado.web.service.ReviewService,
    localBookService: io.legado.web.service.LocalBookService,
) {
    authenticate(AUTH_JWT) {
        route("/api/shelf") {
            get { call.respond(shelfService.shelf(call.user)) }

            /** 打开书籍（可选加入书架） */
            post("/open") {
                call.respond(shelfService.open(call.user, call.receive<OpenBookRequest>()))
            }
            /** 手动排序：请求体为按新顺序排列的书籍 id */
            put("/order") {
                val ids = call.receive<OrderRequest>().ids
                shelfService.saveOrder(call.user, ids.take(5000))
                call.respond(OkResponse())
            }
            /** 刷新书架所有书籍的目录（后台执行） */
            post("/refresh") {
                val u = call.user
                val books = shelfService.shelf(u).filter { it.canUpdate }
                backgroundScope.launch {
                    books.filter { !it.isLocal }.forEach { b -> runCatching { shelfService.refreshToc(u, b.id) } }
                }
                call.respond(OkResponse(message = "已开始更新 ${books.size} 本书"))
            }
            get("/{id}") { call.respond(shelfService.getBook(call.user, call.longParam("id"))) }
            patch("/{id}") {
                call.respond(shelfService.update(call.user, call.longParam("id"), call.receive<ShelfService.BookUpdate>()))
            }
            delete("/{id}") {
                shelfService.delete(call.user, call.longParam("id"))
                call.respond(OkResponse())
            }
            post("/{id}/refresh") {
                val info = call.request.queryParameters["info"] == "true"
                call.respond(shelfService.refreshToc(call.user, call.longParam("id"), info))
            }
            // 本地 TXT 指定目录规则重新分章：tocUrl 为“规则 + 分隔符 + js 处理”（与 App 相同），空字符串表示自动选择
            post("/{id}/txt-toc") {
                val req = call.receive<TxtTocRequest>()
                call.respond(shelfService.refreshToc(call.user, call.longParam("id"), txtTocUrl = req.tocUrl))
            }
            get("/{id}/chapters") {
                call.respond(shelfService.chapters(call.user, call.longParam("id")))
            }
            /** 书内全文搜索：?q=关键字 */
            get("/{id}/search") {
                val q = call.request.queryParameters["q"].orEmpty()
                call.respond(shelfService.searchBook(call.user, call.longParam("id"), q))
            }
            get("/{id}/content/{index}") {
                val refresh = call.request.queryParameters["refresh"] == "true"
                call.respond(shelfService.content(call.user, call.longParam("id"), call.intParam("index"), refresh))
            }
            // ---------- 本地书籍（EPUB / TXT） ----------
            get("/local/info") { call.respond(localBookService.info(call.user)) }
            /** 上传：请求体为文件内容，name 为原文件名；PDF 另带 pages（浏览器用 pdf.js 读出的页数） */
            post("/local") {
                val u = call.user
                val limit = localBookService.uploadLimit(u)
                val name = call.request.queryParameters["name"].orEmpty()
                val groupId = call.request.queryParameters["groupId"]?.toLongOrNull() ?: 0
                call.request.contentLength()?.let { if (it > limit) throw BadRequestException("文件过大（最大 ${limit / 1024 / 1024}MB）") }
                val tmp = java.io.File.createTempFile("upload-", ".tmp")
                try {
                    val channel = call.receiveChannel()
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = channel.readAvailable(buf, 0, buf.size)
                            if (n < 0) break
                            total += n
                            if (total > limit) throw BadRequestException("文件过大（最大 ${limit / 1024 / 1024}MB）")
                            out.write(buf, 0, n)
                        }
                    }
                    val pages = call.request.queryParameters["pages"]?.toIntOrNull()
                    call.respond(localBookService.import(u, tmp, name, io.legado.web.service.LocalBookService.ImportOptions(groupId, pdfPages = pages)))
                } finally {
                    tmp.delete()
                }
            }
            get("/{id}/local/image") {
                val u = call.user
                val bookUrl = shelfService.localBookUrl(u, call.longParam("id"))
                val (bytes, type) = localBookService.image(u.id, bookUrl, call.request.queryParameters["p"].orEmpty())
                respondLocalImage(call, bytes, type)
            }
            /** PDF 原文件：页面在浏览器里用 pdf.js 渲染 */
            get("/{id}/local/file") {
                val u = call.user
                val bookUrl = shelfService.localBookUrl(u, call.longParam("id"))
                val file = localBookService.pdfFile(u.id, bookUrl)
                call.response.header(io.ktor.http.HttpHeaders.CacheControl, "private, max-age=86400")
                call.respondFile(file)
            }
            /** PDF 封面：浏览器渲染第一页后上传（请求体为 JPEG / PNG） */
            post("/{id}/local/cover") {
                val u = call.user
                val id = call.longParam("id")
                val bookUrl = shelfService.localBookUrl(u, id)
                call.request.contentLength()?.let { if (it > 3 * 1024 * 1024) throw BadRequestException("封面图片过大") }
                val bytes = call.receiveChannel().readRemaining(3L * 1024 * 1024 + 1).readByteArray()
                localBookService.saveCover(u.id, bookUrl, bytes)
                call.respond(shelfService.setLocalCover(u, id))
            }
            get("/{id}/local/cover") {
                val u = call.user
                val bookUrl = shelfService.localBookUrl(u, call.longParam("id"))
                val (bytes, type) = localBookService.cover(u.id, bookUrl)
                respondLocalImage(call, bytes, type)
            }

            // ---------- 原生段评（书源 ruleReview）：para 为段落序号（从 1 开始，-1 为本章说） ----------
            get("/{id}/review/{index}/summary") {
                call.respond(reviewService.summary(call.user, call.longParam("id"), call.intParam("index")))
            }
            get("/{id}/review/{index}/detail") {
                val q = call.request.queryParameters
                call.respond(
                    reviewService.detail(
                        call.user, call.longParam("id"), call.intParam("index"),
                        q["para"]?.toIntOrNull() ?: throw BadRequestException("缺少 para"),
                        q["paraData"], q["page"]?.toIntOrNull() ?: 1, q["cursor"],
                    )
                )
            }
            get("/{id}/review/{index}/replies") {
                val q = call.request.queryParameters
                call.respond(
                    reviewService.replies(
                        call.user, call.longParam("id"), call.intParam("index"),
                        q["para"]?.toIntOrNull() ?: throw BadRequestException("缺少 para"),
                        q["paraData"], q["reviewId"].orEmpty(), q["page"]?.toIntOrNull() ?: 1,
                    )
                )
            }
            /** 点击正文图片（段评、本章说等），body: { src } —— src 必须是本章正文中的图片地址 */
            post("/{id}/content/{index}/click") {
                val src = call.receive<ImageClickRequest>().src
                val (source, book, chapter) = shelfService.imageClickContext(call.user, call.longParam("id"), call.intParam("index"), src)
                call.respond(configService.clickImage(call.user, source, book, chapter, src))
            }
            put("/{id}/progress") {
                val req = call.receive<ProgressRequest>()
                val applied = shelfService.saveProgress(call.user, call.longParam("id"), req.index, req.pos, req.title, req.time)
                call.respond(OkResponse(message = if (applied) null else "服务器上的进度更新，已忽略"))
            }
            post("/{id}/cache") {
                val req = call.receive<CacheRequest>()
                val u = call.user
                val id = call.longParam("id")
                backgroundScope.launch { shelfService.cacheChapters(u, id, req.start, req.count) }
                call.respond(OkResponse(message = "已开始缓存"))
            }
            delete("/{id}/cache") {
                shelfService.clearCache(call.user, call.longParam("id"))
                call.respond(OkResponse())
            }
            post("/{id}/change-source") {
                call.respond(shelfService.changeSource(call.user, call.longParam("id"), call.receive<OpenBookRequest>()))
            }
        }

        route("/api/groups") {
            get { call.respond(shelfService.groups(call.user)) }
            post {
                val req = call.receive<GroupRequest>()
                call.respond(shelfService.saveGroup(call.user, null, req.name, req.order))
            }
            put("/{id}") {
                val req = call.receive<GroupRequest>()
                call.respond(shelfService.saveGroup(call.user, call.longParam("id"), req.name, req.order))
            }
            delete("/{id}") {
                shelfService.deleteGroup(call.user, call.longParam("id"))
                call.respond(OkResponse())
            }
        }

        route("/api/replace-rules") {
            get { call.respond(replaceService.list(call.user.id)) }
            post { call.respond(replaceService.save(call.user.id, call.receive<ReplaceRuleDto>())) }
            post("/import") {
                call.respond(mapOf("count" to replaceService.import(call.user.id, call.receiveText())))
            }
            post("/delete") {
                replaceService.delete(call.user.id, call.receive<IdsRequest>().ids)
                call.respond(OkResponse())
            }
        }

        /** 用户偏好（阅读设置等，JSON 字符串） */
        route("/api/prefs") {
            get("/{key}") {
                val key = call.parameters["key"]!!
                // 以 _ 开头的是服务端内部使用的配置（如 WebDAV 账号），不通过此接口读写
                if (key.startsWith("_")) throw BadRequestException("无效的 key")
                val v = dbQuery {
                    UserPrefs.selectAll().where { (UserPrefs.userId eq call.user.id) and (UserPrefs.key eq key) }
                        .firstOrNull()?.get(UserPrefs.value)
                }
                call.respond(mapOf("value" to v))
            }
            put("/{key}") {
                val key = call.parameters["key"]!!
                if (key.length > 64) throw BadRequestException("key 过长")
                if (key.startsWith("_")) throw BadRequestException("无效的 key")
                val body = call.receiveText()
                if (body.length > 64 * 1024) throw BadRequestException("内容过大")
                val uid = call.user.id
                dbQuery {
                    UserPrefs.upsert {
                        it[userId] = uid
                        it[UserPrefs.key] = key
                        it[value] = body
                    }
                }
                call.respond(OkResponse())
            }
        }

        /**
         * 图片代理：/api/proxy/image?url=&sourceId=&bookId=&cover=true&token=
         */
        get("/api/proxy/image") {
            val q = call.request.queryParameters
            val url = q["url"] ?: throw BadRequestException("缺少 url")
            val img = proxyService.image(
                call.user, url, q["sourceId"]?.toLongOrNull(), q["origin"],
                q["bookId"]?.toLongOrNull(), q["cover"] == "true"
            )
            call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 7 * 86400, visibility = CacheControl.Visibility.Private))
            // 书源图片可能是 SVG（段评气泡等），直接打开代理地址时禁止其中的脚本在本站运行
            call.response.header("Content-Security-Policy", "sandbox; default-src 'none'; img-src data:; style-src 'unsafe-inline'")
            call.response.header("X-Content-Type-Options", "nosniff")
            call.respondBytes(img.bytes, img.contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() })
        }
    }
}

/** 本地书籍中的图片：只输出图片类型；SVG 等直接打开时禁止其中的脚本在本站运行 */
private suspend fun respondLocalImage(call: io.ktor.server.application.ApplicationCall, bytes: ByteArray, type: String) {
    call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 7 * 86400, visibility = CacheControl.Visibility.Private))
    call.response.header("Content-Security-Policy", "sandbox; default-src 'none'; img-src data:; style-src 'unsafe-inline'")
    call.response.header("X-Content-Type-Options", "nosniff")
    call.respondBytes(bytes, ContentType.parse(type))
}
