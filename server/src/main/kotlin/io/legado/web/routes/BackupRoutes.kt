package io.legado.web.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.server.auth.authenticate
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import io.legado.web.auth.AUTH_JWT
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.user
import io.legado.web.service.BackupService
import io.legado.web.service.WebDavService
import io.ktor.server.request.receive
import io.ktor.server.routing.delete
import io.ktor.server.routing.put
import kotlinx.io.readByteArray
import java.time.LocalDate

/** 备份最大 100MB */
private const val MAX_BACKUP_SIZE = 100L * 1024 * 1024

/** 导入结果里的警告按界面语言翻译 */
private fun BackupService.ImportSummary.localized(call: io.ktor.server.application.ApplicationCall) =
    copy(warnings = warnings.map { io.legado.web.i18n.Messages.translate(call, it) }.toMutableList())

private fun Parameters.parts() = BackupService.Parts(
    books = this["books"] != "false",
    sources = this["sources"] != "false",
    rules = this["rules"] != "false",
    subs = this["subs"] != "false",
)

data class WebDavRestoreRequest(val name: String = "")

/**
 * 与阅读 App 互通的备份：
 * - GET  /api/backup/export?books=&sources=&rules=&subs=   下载 zip（可在 App 的“恢复”中使用）
 * - POST /api/backup/import?books=&sources=&rules=&subs=   请求体为 App 的备份 zip
 * - /api/webdav/…：WebDAV 配置、备份、列出和恢复云端备份
 */
fun Route.backupRoutes(backupService: BackupService, webDavService: WebDavService) {
    authenticate(AUTH_JWT) {
        get("/api/backup/export") {
            val bytes = backupService.export(call.user, call.request.queryParameters.parts())
            val fileName = "backup${LocalDate.now()}.zip"
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, fileName).toString(),
            )
            call.respondBytes(bytes, ContentType.Application.Zip)
        }
        post("/api/backup/import") {
            val len = call.request.contentLength()
            if (len != null && len > MAX_BACKUP_SIZE) throw BadRequestException("备份文件过大（最大 100MB）")
            val packet = call.receiveChannel().readRemaining(MAX_BACKUP_SIZE + 1)
            val bytes = packet.readByteArray()
            if (bytes.size > MAX_BACKUP_SIZE) throw BadRequestException("备份文件过大（最大 100MB）")
            if (bytes.isEmpty()) throw BadRequestException("没有收到文件")
            call.respond(backupService.import(call.user, bytes, call.request.queryParameters.parts()).localized(call))
        }

        // ---------- WebDAV（与 App 使用相同的目录和文件名） ----------
        get("/api/webdav/config") { call.respond(webDavService.config(call.user)) }
        put("/api/webdav/config") { call.respond(webDavService.save(call.user, call.receive<WebDavService.ConfigInput>())) }
        delete("/api/webdav/config") {
            webDavService.clear(call.user)
            call.respond(OkResponse())
        }
        post("/api/webdav/backup") {
            call.respond(webDavService.backup(call.user, call.request.queryParameters.parts()))
        }
        get("/api/webdav/backups") { call.respond(webDavService.list(call.user)) }
        post("/api/webdav/restore") {
            val name = call.receive<WebDavRestoreRequest>().name
            call.respond(webDavService.restore(call.user, name, call.request.queryParameters.parts()).localized(call))
        }
    }
}
