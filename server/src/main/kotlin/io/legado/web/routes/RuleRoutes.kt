package io.legado.web.routes

import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.TxtTocRule
import io.legado.web.auth.AUTH_JWT
import io.legado.web.auth.user
import io.legado.web.service.DictRuleService
import io.legado.web.service.TxtTocRuleService

data class DictRuleList(val rules: List<DictRule>, val canEdit: Boolean)
data class SaveDictRuleRequest(val rule: DictRule = DictRule(), val oldName: String? = null)
data class NamesRequest(val names: List<String> = emptyList())
data class DictSearchRequest(val name: String = "", val word: String = "")
/** baseUrl：字典网页的地址，结果中的相对地址（图片、链接）按它解析；data: 地址时为空 */
data class DictSearchResponse(val html: String, val baseUrl: String? = null)
data class TxtTocRuleList(val rules: List<TxtTocRule>, val canEdit: Boolean)
data class CountResponse(val count: Int)

/** 字典规则、TXT 目录规则（与 App 的 JSON 格式兼容） */
fun Route.ruleRoutes(dictService: DictRuleService, tocService: TxtTocRuleService) {
    authenticate(AUTH_JWT) {
        route("/api/dict-rules") {
            get { call.respond(DictRuleList(dictService.list(call.user.id), dictService.canEdit(call.user))) }
            post {
                val req = call.receive<SaveDictRuleRequest>()
                call.respond(dictService.save(call.user, req.rule, req.oldName))
            }
            post("/delete") {
                dictService.delete(call.user.id, call.receive<NamesRequest>().names)
                call.respond(OkResponse())
            }
            post("/import") { call.respond(CountResponse(dictService.import(call.user, call.receiveText()))) }
            post("/defaults") {
                dictService.importDefaults(call.user.id)
                call.respond(OkResponse())
            }
            post("/search") {
                val req = call.receive<DictSearchRequest>()
                val (html, url) = dictService.search(call.user, req.name, req.word)
                call.respond(DictSearchResponse(html, url.takeIf { it.startsWith("http://") || it.startsWith("https://") }))
            }
        }
        route("/api/txt-toc-rules") {
            get { call.respond(TxtTocRuleList(tocService.list(call.user.id), tocService.canEdit(call.user))) }
            post { call.respond(tocService.save(call.user, call.receive<TxtTocRule>())) }
            post("/delete") {
                tocService.delete(call.user.id, call.receive<IdsRequest>().ids)
                call.respond(OkResponse())
            }
            post("/import") { call.respond(CountResponse(tocService.import(call.user, call.receiveText()))) }
            post("/defaults") {
                tocService.importDefaults(call.user.id)
                call.respond(OkResponse())
            }
        }
    }
}
