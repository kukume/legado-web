package io.legado.web.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.launch
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.web.auth.AUTH_JWT
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.user
import io.legado.web.service.SearchEvent
import io.legado.web.service.SearchService
import io.legado.web.service.SourceConfigService
import io.legado.web.service.SourceService
import io.legado.web.service.SubscriptionService

data class ImportUrlRequest(val url: String = "", val isPublic: Boolean = false)
data class ImportTextRequest(val text: String = "", val isPublic: Boolean = false)
data class BatchRequest(val ids: List<Long> = emptyList(), val action: String = "", val value: String? = null)
data class SaveSourceRequest(val source: String = "", val isPublic: Boolean? = null)
data class LoginRequestBody(val info: Map<String, String> = emptyMap())
data class ActionRequestBody(val action: String = "", val info: Map<String, String> = emptyMap(), val isLongClick: Boolean = false)
data class LoginV2RenderBody(val state: String? = null)
data class LoginV2ActionBody(val action: String = "", val state: String? = null, val form: Map<String, String> = emptyMap())
data class VariableBody(val variable: String? = null)
data class CookieBody(val url: String? = null, val cookie: String = "")
data class DebugRequest(val sourceId: Long? = null, val source: String? = null, val key: String = "")
data class ExploreRequest(val sourceId: Long = 0, val url: String = "", val page: Int = 1)
data class ExploreActionBody(
    val title: String = "",
    val value: String? = null,
    val action: String? = null,
    val infoMap: Map<String, String> = emptyMap(),
)
data class SubscriptionRequest(val name: String = "", val url: String = "", val isPublic: Boolean = false, val autoUpdate: Boolean = true)
data class SourceDetail(val id: Long, val isPublic: Boolean, val canEdit: Boolean, val source: BookSource)
data class CheckRequest(val ids: List<Long> = emptyList(), val keyword: String? = null)

fun Route.sourceRoutes(
    sourceService: SourceService,
    configService: SourceConfigService,
    searchService: SearchService,
    subscriptionService: SubscriptionService,
    checkService: io.legado.web.service.SourceCheckService,
) {
    authenticate(AUTH_JWT) {
        route("/api/sources") {
            get {
                call.respond(sourceService.list(call.user))
            }
            get("/groups") {
                call.respond(sourceService.groups(call.user))
            }
            get("/export") {
                val ids = call.request.queryParameters["ids"]?.split(',')?.mapNotNull { it.trim().toLongOrNull() }
                val list = sourceService.export(call.user, ids)
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "bookSource.json").toString()
                )
                call.respondText(GSON.toJson(list), ContentType.Application.Json)
            }
            get("/{id}") {
                val u = call.user
                val s = sourceService.get(u, call.longParam("id"))
                call.respond(SourceDetail(s.id, s.isPublic, s.ownerId == u.id || u.isAdmin, s.source))
            }
            post("/import") {
                val req = call.receive<ImportTextRequest>()
                call.respond(sourceService.import(call.user, sourceService.parseAny(call.user, req.text), req.isPublic))
            }
            post("/import-preview") {
                val req = call.receive<ImportUrlRequest>()
                val u = call.user
                call.respond(sourceService.previewImport(u, sourceService.parseAny(u, sourceService.fetchText(req.url.trim()))))
            }
            post("/import-url") {
                val req = call.receive<ImportUrlRequest>()
                call.respond(sourceService.importFromUrl(call.user, req.url, req.isPublic))
            }
            post {
                val req = call.receive<SaveSourceRequest>()
                val src = GSON.fromJsonObject<BookSource>(req.source).getOrElse {
                    throw BadRequestException("书源 JSON 格式错误: ${it.message}")
                }
                call.respond(mapOf("id" to sourceService.save(call.user, null, src, req.isPublic)))
            }
            put("/{id}") {
                val req = call.receive<SaveSourceRequest>()
                val src = GSON.fromJsonObject<BookSource>(req.source).getOrElse {
                    throw BadRequestException("书源 JSON 格式错误: ${it.message}")
                }
                call.respond(mapOf("id" to sourceService.save(call.user, call.longParam("id"), src, req.isPublic)))
            }
            delete("/{id}") {
                sourceService.delete(call.user, listOf(call.longParam("id")))
                call.respond(OkResponse())
            }
            post("/batch") {
                val req = call.receive<BatchRequest>()
                sourceService.batch(call.user, req.ids, req.action, req.value)
                call.respond(OkResponse())
            }
            /** 校验书源：每校验完一个推送一行结果（NDJSON） */
            post("/check") {
                val req = call.receive<CheckRequest>()
                if (req.ids.isEmpty()) throw BadRequestException("请先勾选书源")
                val u = call.user
                call.respondNdjson { emit ->
                    checkService.check(u, req.ids, req.keyword) { done, total, r ->
                        emit(mapOf("type" to "result", "done" to done, "total" to total, "result" to r))
                    }
                    emit(mapOf("type" to "end"))
                }
            }

            // ---- 书源登录 / 配置（按用户保存） ----
            get("/{id}/login") {
                call.respond(configService.loginConfig(call.user, call.longParam("id")))
            }
            post("/{id}/login") {
                val body = call.receive<LoginRequestBody>()
                call.respond(configService.login(call.user, call.longParam("id"), body.info))
            }
            // 只保存登录界面中填写的内容（不执行 login 函数）
            post("/{id}/login-info") {
                val body = call.receive<LoginRequestBody>()
                configService.saveLoginInfo(call.user, call.longParam("id"), body.info)
                call.respond(OkResponse())
            }
            post("/{id}/action") {
                val body = call.receive<ActionRequestBody>()
                call.respond(configService.runAction(call.user, call.longParam("id"), body.action, body.info, body.isLongClick))
            }
            // 登录界面 v2（{"version":2}）
            post("/{id}/login/v2/render") {
                val body = call.receive<LoginV2RenderBody>()
                call.respond(configService.loginV2Render(call.user, call.longParam("id"), body.state))
            }
            post("/{id}/login/v2/action") {
                val body = call.receive<LoginV2ActionBody>()
                call.respond(configService.loginV2Action(call.user, call.longParam("id"), body.action, body.state, body.form))
            }
            delete("/{id}/login") {
                configService.logout(call.user, call.longParam("id"))
                call.respond(OkResponse())
            }
            /** 手动设置书源网站的 Cookie（网页版无法读取浏览器里的 Cookie） */
            get("/{id}/cookie") {
                call.respond(configService.getCookie(call.user, call.longParam("id"), call.request.queryParameters["url"]))
            }
            post("/{id}/cookie") {
                val body = call.receive<CookieBody>()
                call.respond(configService.setCookie(call.user, call.longParam("id"), body.url, body.cookie))
            }
            put("/{id}/variable") {
                configService.setVariable(call.user, call.longParam("id"), call.receive<VariableBody>().variable)
                call.respond(OkResponse())
            }

            // ---- 发现 ----
            get("/{id}/explore") {
                val refresh = call.request.queryParameters["refresh"] == "true"
                call.respond(configService.exploreKinds(call.user, call.longParam("id"), refresh))
            }
            post("/{id}/explore/action") {
                val body = call.receive<ExploreActionBody>()
                call.respond(
                    configService.exploreAction(call.user, call.longParam("id"), body.title, body.value, body.action)
                )
            }
        }

        post("/api/explore") {
            val req = call.receive<ExploreRequest>()
            call.respond(searchService.explore(call.user, req.sourceId, req.url, req.page))
        }

        /**
         * 书源调试（NDJSON 流）
         */
        post("/api/sources/debug") {
            val req = call.receive<DebugRequest>()
            val u = call.user
            val source = when {
                !req.source.isNullOrBlank() -> {
                    // 调试未保存的书源：需要有添加书源的权限
                    if (!sourceService.canAddSource(u)) throw BadRequestException("没有权限调试自定义书源")
                    GSON.fromJsonObject<BookSource>(req.source).getOrElse {
                        throw BadRequestException("书源 JSON 格式错误: ${it.message}")
                    }
                }
                req.sourceId != null -> sourceService.get(u, req.sourceId).source
                else -> throw BadRequestException("缺少书源")
            }
            if (req.key.isBlank()) throw BadRequestException("调试关键字不能为空")
            // 调试关键字可以是书籍、目录、正文、发现地址：带脚本的需要添加书源的权限
            if (req.source.isNullOrBlank()) sourceService.requireTrustedUrls(u, req.key)
            call.respondNdjson { emit ->
                val channel = kotlinx.coroutines.channels.Channel<Pair<Int, String>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                kotlinx.coroutines.coroutineScope {
                    val job = launch {
                        try {
                            configService.debug(u, source, req.key) { state, msg -> channel.trySend(state to msg) }
                        } finally {
                            channel.close()
                        }
                    }
                    for ((state, msg) in channel) {
                        emit(mapOf("state" to state, "msg" to msg))
                    }
                    job.join()
                }
            }
        }

        /**
         * 多书源搜索（NDJSON 流）
         * GET /api/search?key=&page=&group=&sourceIds=1,2
         */
        get("/api/search") {
            val key = call.request.queryParameters["key"]?.trim()
            if (key.isNullOrEmpty()) throw BadRequestException("搜索关键字不能为空")
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
            val group = call.request.queryParameters["group"]
            val ids = call.request.queryParameters["sourceIds"]?.split(',')?.mapNotNull { it.toLongOrNull() }?.toSet()
            val u = call.user
            call.respondNdjson { emit ->
                searchService.search(u, key, page, group, ids) { event ->
                    when (event) {
                        is SearchEvent.Result -> emit(mapOf("type" to "books", "books" to event.books))
                        is SearchEvent.Progress -> emit(
                            mapOf(
                                "type" to "progress", "done" to event.done, "total" to event.total,
                                "source" to event.sourceName, "error" to event.error
                            )
                        )
                    }
                }
                emit(mapOf("type" to "end"))
            }
        }

        /** 单书源搜索（换源用） */
        get("/api/search/source/{id}") {
            val key = call.request.queryParameters["key"]?.trim()
            if (key.isNullOrEmpty()) throw BadRequestException("搜索关键字不能为空")
            call.respond(searchService.searchInSource(call.user, call.longParam("id"), key, 1))
        }

        route("/api/subscriptions") {
            get { call.respond(subscriptionService.list(call.user)) }
            post {
                val req = call.receive<SubscriptionRequest>()
                call.respond(mapOf("id" to subscriptionService.create(call.user, req.name, req.url, req.isPublic, req.autoUpdate)))
            }
            post("/{id}/refresh") {
                call.respond(subscriptionService.refresh(call.user, call.longParam("id")))
            }
            delete("/{id}") {
                subscriptionService.delete(call.user, call.longParam("id"))
                call.respond(OkResponse())
            }
        }
    }
}
