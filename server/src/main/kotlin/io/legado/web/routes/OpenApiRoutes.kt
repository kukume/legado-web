package io.legado.web.routes

import io.ktor.server.application.ApplicationCall
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFileExtension
import io.ktor.server.response.respondBytes
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.legado.web.ErrorResponse
import io.legado.web.auth.AUTH_JWT
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.UnauthorizedException
import io.legado.web.auth.UserPrincipal
import io.legado.web.auth.user
import io.legado.web.service.ApiKeyService
import io.legado.web.service.BookDto
import io.legado.web.service.ChapterDto
import io.legado.web.service.ShelfService
import java.util.Properties

data class CreateApiKeyRequest(val name: String = "")

/** 开放接口：书本详情 */
data class OpenBookDetail(val book: BookDto, val chapters: List<ChapterDto>)

/** 开放接口：正文 */
data class OpenChapterContent(
    val bookId: Long,
    val bookName: String,
    val index: Int,
    val title: String,
    val content: String,
    /** 上一章 / 下一章序号，没有时为 null（会跳过卷名） */
    val prevIndex: Int?,
    val nextIndex: Int?,
    val totalChapters: Int,
    /** 是否来自服务端缓存 */
    val cached: Boolean,
)

/**
 * 从请求中取 API Key：
 * - 请求头 `X-API-Key: lgd_xxx`
 * - 请求头 `Authorization: Bearer lgd_xxx`
 * - 查询参数 `?api_key=lgd_xxx`
 */
private fun ApplicationCall.apiKey(): String? {
    request.headers["X-API-Key"]?.let { return it.trim() }
    request.headers["Authorization"]?.let {
        if (it.startsWith("Bearer ", true)) return it.substring(7).trim()
    }
    return request.queryParameters["api_key"]?.trim()
}

private fun resourceText(path: String): String =
    OpenBookDetail::class.java.classLoader.getResource(path)?.readText()
        ?: error("缺少资源 $path")

/** swagger-ui webjar 的资源目录（版本号从 webjar 自带的 pom.properties 读取） */
private val swaggerUiDir: String? by lazy {
    OpenBookDetail::class.java.classLoader
        .getResourceAsStream("META-INF/maven/org.webjars/swagger-ui/pom.properties")
        ?.use { Properties().apply { load(it) }.getProperty("version") }
        ?.let { "META-INF/resources/webjars/swagger-ui/$it" }
}

/**
 * 开放接口文档：只包含 /openapi/v1 下的接口
 * - /openapi/docs          Swagger UI
 * - /openapi/openapi.yaml  OpenAPI 描述
 */
private fun Route.openApiDocs() {
    val spec by lazy { resourceText("openapi/openapi.yaml") }
    val page by lazy { resourceText("openapi/docs.html") }
    get("/openapi/openapi.yaml") { call.respondText(spec, ContentType.parse("application/yaml; charset=utf-8")) }
    get("/openapi/docs") { call.respondText(page, ContentType.Text.Html) }
    get("/openapi/docs/") { call.respondRedirect("/openapi/docs") }
    // Swagger UI 静态资源（webjar 目录名带版本号里的点，staticResources 会把点当包分隔符，所以手动读取）
    get("/openapi/docs/assets/{file}") {
        val file = call.parameters["file"].orEmpty()
        val bytes = swaggerUiDir
            ?.takeIf { file.matches(Regex("[A-Za-z0-9._-]+")) && !file.startsWith(".") }
            ?.let { OpenBookDetail::class.java.classLoader.getResourceAsStream("$it/$file") }
            ?.use { it.readBytes() }
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("文件不存在"))
        call.response.header("Cache-Control", "public, max-age=86400")
        call.respondBytes(bytes, ContentType.defaultForFileExtension(file.substringAfterLast('.')))
    }
}

fun Route.openApiRoutes(apiKeyService: ApiKeyService, shelfService: ShelfService) {

    openApiDocs()

    // ---------- 密钥管理（网页登录后使用） ----------
    authenticate(AUTH_JWT) {
        route("/api/apikeys") {
            get { call.respond(apiKeyService.list(call.user.id)) }
            post { call.respond(apiKeyService.create(call.user.id, call.receive<CreateApiKeyRequest>().name)) }
            delete("/{id}") {
                apiKeyService.delete(call.user.id, call.longParam("id"))
                call.respond(OkResponse())
            }
        }
    }

    // ---------- 开放接口（API Key 鉴权） ----------
    route("/openapi/v1") {
        suspend fun ApplicationCall.apiUser(): UserPrincipal =
            apiKeyService.authenticate(apiKey())
                ?: throw UnauthorizedException("API Key 无效或已被删除")

        /** 书架 */
        get("/shelf") {
            val u = call.apiUser()
            call.respond(shelfService.shelf(u))
        }

        /** 书本详情（含章节目录） */
        get("/books/{id}") {
            val u = call.apiUser()
            val id = call.longParam("id")
            call.respond(OpenBookDetail(shelfService.getBook(u, id), shelfService.chapters(u, id)))
        }

        /**
         * 阅读正文
         * - index：章节序号（从 0 开始），或 current / next / prev（相对当前阅读进度），默认 current
         * - saveProgress：是否把该章记为阅读进度，默认 true（因此连续调用 next 可以一章章往后读）
         * - refresh：忽略缓存重新从书源获取，默认 false
         */
        get("/books/{id}/content") {
            val u = call.apiUser()
            val id = call.longParam("id")
            val q = call.request.queryParameters
            val book = shelfService.getBook(u, id)
            val chapters = shelfService.chapters(u, id)
            if (chapters.isEmpty()) throw BadRequestException("该书没有章节，请先在网页中更新目录")
            val readable = chapters.filter { !it.isVolume }.map { it.index }
            val current = book.durChapterIndex.coerceIn(0, chapters.size - 1)
            fun next(from: Int) = readable.firstOrNull { it > from }
            fun prev(from: Int) = readable.lastOrNull { it < from }
            val indexParam = q["index"]?.trim()?.lowercase() ?: "current"
            val index = when (indexParam) {
                "current" -> current
                "next" -> next(current) ?: throw BadRequestException("已经是最后一章")
                "prev" -> prev(current) ?: throw BadRequestException("已经是第一章")
                else -> indexParam.toIntOrNull()
                    ?.takeIf { it in chapters.indices }
                    ?: throw BadRequestException("index 无效，应为 0-${chapters.size - 1} 或 current/next/prev")
            }
            val content = shelfService.content(u, id, index, q["refresh"] == "true")
            if (q["saveProgress"] != "false") {
                shelfService.saveProgress(u, id, index, 0, content.title)
            }
            call.respond(
                OpenChapterContent(
                    bookId = id,
                    bookName = book.name,
                    index = index,
                    title = content.title,
                    content = content.content,
                    prevIndex = prev(index),
                    nextIndex = next(index),
                    totalChapters = chapters.size,
                    cached = content.cached,
                )
            )
        }

        /**
         * 保存阅读进度：{ index, pos?, title? }
         * index 为章节序号；pos 为章内位置（比例 0~10000，默认 0 即章节开头）。
         * 以服务器时间记录，会覆盖其他设备更早的进度
         */
        put("/books/{id}/progress") {
            val u = call.apiUser()
            val id = call.longParam("id")
            val req = call.receive<ProgressRequest>()
            val chapters = shelfService.chapters(u, id)
            if (req.index !in chapters.indices) throw BadRequestException("index 无效，应为 0-${chapters.size - 1}")
            val title = req.title?.takeIf { it.isNotBlank() } ?: chapters[req.index].title
            shelfService.saveProgress(u, id, req.index, req.pos.coerceIn(0, 10000), title, System.currentTimeMillis())
            call.respond(OkResponse())
        }
    }
}
