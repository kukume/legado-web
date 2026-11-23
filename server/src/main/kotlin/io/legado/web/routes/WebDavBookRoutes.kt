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
import io.legado.web.i18n.Messages
import io.legado.web.service.LocalBookService
import io.legado.web.service.ShelfService
import io.legado.web.service.WebDavService

data class WebDavBookEntry(
    val name: String,
    val size: Long,
    val lastModified: Long,
    val isDir: Boolean,
    /** 书架上已有同名文件导入的本地书（再导入会更新那本书） */
    val imported: Boolean,
)

data class WebDavBookImportRequest(val path: String = "", val groupId: Long = 0)

/**
 * 从 WebDAV（使用备份设置里的 WebDAV 账号）选择书籍导入：
 * - GET  /api/webdav/books?path=      列出目录（path 相对 WebDAV 地址）
 * - POST /api/webdav/books/import     下载一个文件并按本地书籍导入（与上传相同：zip 压缩包解压后逐本导入）；
 *                                     书架上已有同一本书时更新它，保留阅读进度
 */
fun Route.webDavBookRoutes(webDavService: WebDavService, localBookService: LocalBookService, shelfService: ShelfService) {
    authenticate(AUTH_JWT) {
        get("/api/webdav/books") {
            val u = call.user
            val files = webDavService.browse(u, call.request.queryParameters["path"].orEmpty())
            val imported = shelfService.localOriginNames(u.id)
            call.respond(files.map { WebDavBookEntry(it.name, it.size, it.lastModified, it.isDir, !it.isDir && it.name in imported) })
        }
        post("/api/webdav/books/import") {
            val u = call.user
            val req = call.receive<WebDavBookImportRequest>()
            val name = req.path.trimEnd('/').substringAfterLast('/')
            if (name.isBlank()) throw BadRequestException("路径无效")
            // PDF 的页数和封面要在浏览器里用 pdf.js 读取，服务器不解析 PDF
            if (name.endsWith(".pdf", ignoreCase = true)) throw BadRequestException("PDF 请下载后在“导入本地书籍”中上传")
            val limit = localBookService.uploadLimit(u)
            // 在后台处理时原请求可能已经返回（见 respondLong），先取出界面语言
            val lang = Messages.langOf(call)
            call.respondLong {
                val tmp = java.io.File.createTempFile("webdav-", ".tmp")
                try {
                    webDavService.download(u, req.path, tmp, limit)
                    val result = localBookService.importUpload(u, tmp, name, LocalBookService.ImportOptions(req.groupId, updateExisting = true))
                    if (result is LocalBookService.ArchiveImportResult) {
                        result.copy(skipped = result.skipped.map { it.copy(reason = Messages.translate(it.reason, lang)) })
                    } else result
                } finally {
                    tmp.delete()
                }
            }
        }
    }
}
