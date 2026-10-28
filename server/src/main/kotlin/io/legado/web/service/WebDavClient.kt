package io.legado.web.service

import io.legado.web.auth.BadRequestException
import io.legado.web.security.NetworkGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class WebDavFile(val name: String, val size: Long, val lastModified: Long, val isDir: Boolean)

/**
 * 简单的 WebDAV 客户端（与阅读 App 的 WebDav 用法一致：PROPFIND 列目录、MKCOL 建目录、PUT 上传、GET 下载）
 */
class WebDavClient(
    private val account: String,
    private val password: String,
    /** 管理员可访问内网；普通用户走带内网防护的客户端 */
    private val allowPrivate: Boolean = false,
) {
    companion object {
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
        }

        // 普通用户用：与书源请求共用同一套内网防护，在建立连接时按真实 IP 检查，
        // 域名解析到内网、重定向到内网、DNS 重绑定都能拦住
        private val guardedClient: OkHttpClient by lazy {
            client.newBuilder().socketFactory(NetworkGuard.socketFactory).build()
        }

        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<a:propfind xmlns:a="DAV:"><a:prop><a:displayname/><a:resourcetype/><a:getcontentlength/><a:getlastmodified/></a:prop></a:propfind>"""

        /**
         * 服务端代替用户访问 WebDAV 地址：校验地址合法；非管理员先按内网规则拒绝一次给出友好提示，
         * 真正的拦截由 [guardedClient] 在连接时按真实 IP 完成（防 DNS 重绑定）
         */
        fun checkUrl(url: String, allowPrivate: Boolean) {
            val uri = runCatching { URI(url) }.getOrNull()
            if (uri == null || (uri.scheme != "http" && uri.scheme != "https") || uri.host.isNullOrBlank()) {
                throw BadRequestException("WebDAV 地址无效，应以 http:// 或 https:// 开头")
            }
            if (allowPrivate) return
            if (NetworkGuard.isBlockedHost(uri.host)) {
                throw BadRequestException("不允许使用内网或本机的 WebDAV 地址（请联系管理员）")
            }
        }
    }

    private val http: OkHttpClient get() = if (allowPrivate) client else guardedClient

    private fun request(url: String) = Request.Builder().url(url)
        .header("Authorization", Credentials.basic(account, password, Charsets.UTF_8))

    private suspend fun <T> exec(req: Request, block: (Response) -> T): T = withContext(Dispatchers.IO) {
        try {
            http.newCall(req).execute().use(block)
        } catch (e: BadRequestException) {
            throw e
        } catch (e: java.io.IOException) {
            throw BadRequestException("连接 WebDAV 失败：${e.message}")
        }
    }

    private fun Response.fail(action: String): Nothing = throw BadRequestException(
        when (code) {
            401 -> "WebDAV 账号或密码错误（坚果云需使用“应用密码”）"
            403 -> "没有权限（$action）"
            404 -> "路径不存在（$action）"
            else -> "WebDAV $action 失败：HTTP $code"
        }
    )

    /** 检查地址和账号是否可用 */
    suspend fun check(url: String) {
        val req = request(url).method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml".toMediaType()))
            .header("Depth", "0").build()
        exec(req) { if (!it.isSuccessful && it.code != 404) it.fail("连接") }
    }

    /** 创建目录（已存在时忽略） */
    suspend fun makeDir(url: String) {
        val req = request(url).method("MKCOL", null).build()
        exec(req) {
            // 201 创建成功；405 已存在；部分服务器已存在时返回 409 以外的错误
            if (!it.isSuccessful && it.code != 405 && it.code != 301) it.fail("创建目录")
        }
    }

    suspend fun list(url: String): List<WebDavFile> {
        val req = request(url).method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml".toMediaType()))
            .header("Depth", "1").build()
        val xml = exec(req) {
            if (it.code == 404) return@exec null
            if (!it.isSuccessful) it.fail("列出文件")
            it.body.string()
        } ?: return emptyList()
        val selfPath = URI(url).path.trimEnd('/')
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        return doc.getAllElements().filter { it.localName() == "response" }.mapNotNull { r ->
            val href = r.child("href")?.text() ?: return@mapNotNull null
            val path = URLDecoder.decode(runCatching { URI(href).rawPath ?: href }.getOrDefault(href), "UTF-8").trimEnd('/')
            if (path == selfPath || path.isEmpty()) return@mapNotNull null
            val isDir = r.getAllElements().any { it.localName() == "collection" }
            WebDavFile(
                name = path.substringAfterLast('/'),
                size = r.child("getcontentlength")?.text()?.toLongOrNull() ?: 0,
                lastModified = r.child("getlastmodified")?.text()?.let { parseDate(it) } ?: 0,
                isDir = isDir,
            )
        }
    }

    suspend fun upload(url: String, bytes: ByteArray, contentType: String = "application/zip") {
        val req = request(url).put(bytes.toRequestBody(contentType.toMediaType())).build()
        exec(req) { if (!it.isSuccessful) it.fail("上传") }
    }

    suspend fun download(url: String, maxSize: Long): ByteArray {
        val req = request(url).get().build()
        return exec(req) { res ->
            if (!res.isSuccessful) res.fail("下载")
            val len = res.body.contentLength()
            if (len > maxSize) throw BadRequestException("文件过大")
            val bytes = res.body.source().let { src ->
                src.request(maxSize + 1)
                if (src.buffer.size > maxSize) throw BadRequestException("文件过大")
                src.readByteArray()
            }
            bytes
        }
    }

    private fun Element.localName() = tagName().substringAfter(':').lowercase()

    private fun Element.child(name: String): Element? = getAllElements().firstOrNull { it.localName() == name }

    private fun parseDate(s: String): Long =
        runCatching { ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrDefault(0)
}
