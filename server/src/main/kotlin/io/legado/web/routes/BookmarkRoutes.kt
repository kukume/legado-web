package io.legado.web.routes

import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.legado.web.auth.AUTH_JWT
import io.legado.web.auth.user
import io.legado.web.service.BookmarkService
import io.legado.web.service.SaveBookmarkRequest
import io.legado.web.service.UpdateBookmarkRequest

/**
 * 书签
 * - GET  /api/shelf/{id}/bookmarks        某本书的书签（按章节、位置排序）
 * - POST /api/shelf/{id}/bookmarks        添加书签 { chapterIndex, chapterName, chapterPos, bookText, content }
 * - PUT  /api/bookmarks/{id}              修改备注 { content }
 * - POST /api/bookmarks/delete            删除 { ids }
 */
fun Route.bookmarkRoutes(bookmarkService: BookmarkService) {
    authenticate(AUTH_JWT) {
        get("/api/shelf/{id}/bookmarks") {
            call.respond(bookmarkService.list(call.user.id, call.longParam("id")))
        }
        post("/api/shelf/{id}/bookmarks") {
            call.respond(bookmarkService.add(call.user.id, call.longParam("id"), call.receive<SaveBookmarkRequest>()))
        }
        put("/api/bookmarks/{id}") {
            bookmarkService.update(call.user.id, call.longParam("id"), call.receive<UpdateBookmarkRequest>())
            call.respond(OkResponse())
        }
        post("/api/bookmarks/delete") {
            bookmarkService.delete(call.user.id, call.receive<IdsRequest>().ids)
            call.respond(OkResponse())
        }
    }
}
