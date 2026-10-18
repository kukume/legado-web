package io.legado.web.routes

import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.legado.web.auth.AUTH_JWT
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.user
import io.legado.web.service.ReadingReport
import io.legado.web.service.ReadingStatsService

/**
 * 阅读统计
 * - POST /api/stats/reading           上报阅读时长、字数的增量 { day, bookId, seconds, chars }
 * - GET  /api/stats/reading?from=     汇总：总计、from（含）以来每天的数据、阅读时间最长的书
 */
fun Route.readingStatsRoutes(statsService: ReadingStatsService) {
    authenticate(AUTH_JWT) {
        post("/api/stats/reading") {
            statsService.report(call.user.id, call.receive<ReadingReport>())
            call.respond(OkResponse())
        }
        get("/api/stats/reading") {
            val from = call.request.queryParameters["from"] ?: throw BadRequestException("缺少 from")
            call.respond(statsService.summary(call.user.id, from))
        }
    }
}
