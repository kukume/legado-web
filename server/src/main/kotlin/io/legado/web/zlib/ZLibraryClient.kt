package io.legado.web.zlib

import io.legado.app.help.http.SSLHelper
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 纯 HTTP 的 Z-Library 客户端（从 zlibrary.py 移植）：登录、搜索、下载，不需要浏览器。
 *
 * 站点用两道反爬：
 *  - 503 的 SHA-1 工作量证明（PoW）：页面里带一个种子，暴力求一个后缀使 sha1(种子+后缀) 的指定位置为 0xB0 0x0B，
 *    结果写进 c_token cookie 再重试。
 *  - 200 的 JS cookie 关卡：由 __js_p_ 推出 __jhash_、__jua_ 两个 cookie。
 * 登录后 remix_userid / remix_userkey 两个 cookie 保持会话。
 *
 * 每个实例独立持有 cookie 和（可选的）代理，短生命周期，用完即弃。
 */
class ZLibraryClient(
    host: String,
    proxy: String? = null,
    private val timeoutSeconds: Long = 60,
) {
    val host: String = host.trimEnd('/')
    private val hostName: String = this.host.toHttpUrlOrNull()?.host ?: "z-library.biz"
    private val jar = SimpleCookieJar()

    var account: Account? = null
        private set

    private val client: OkHttpClient = buildClient(proxy, followRedirects = true, forDownload = false)
    private val noRedirectClient: OkHttpClient = buildClient(proxy, followRedirects = false, forDownload = false)
    private val downloadClient: OkHttpClient = buildClient(proxy, followRedirects = false, forDownload = true)

    private fun buildClient(proxy: String?, followRedirects: Boolean, forDownload: Boolean): OkHttpClient {
        val b = OkHttpClient.Builder()
            .cookieJar(jar)
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(if (forDownload) 300 else timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(if (forDownload) 0 else timeoutSeconds, TimeUnit.SECONDS)
            .sslSocketFactory(SSLHelper.unsafeSSLSocketFactory, SSLHelper.unsafeTrustManager)
            .hostnameVerifier(SSLHelper.unsafeHostnameVerifier)
        applyProxy(b, proxy)
        return b.build()
    }

    /**
     * 代理不经过书源的内网防护（NetworkGuard）：Z-Library 的代理常是本机的 Clash/V2Ray（127.0.0.1），
     * 且目标主机固定是管理员配置的 Z-Library 站点，不是用户可控的任意地址。
     * 格式：http(s)/socks4/socks5://[用户名:密码@]host:port
     */
    private fun applyProxy(b: OkHttpClient.Builder, proxy: String?) {
        val p = proxy?.trim().orEmpty()
        if (p.isEmpty()) return
        val m = PROXY_RE.matchEntire(p) ?: throw IllegalArgumentException(
            "代理格式错误，应为 http://host:port 或 socks5://host:port（可带 用户名:密码@）"
        )
        val scheme = m.groupValues[1]
        val user = m.groupValues[2]
        val pass = m.groupValues[3]
        val h = m.groupValues[4]
        val port = m.groupValues[5].toInt()
        if (scheme == "http") {
            b.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(h, port)))
            if (user.isNotEmpty()) {
                b.proxyAuthenticator { _, response ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(user, pass))
                        .build()
                }
            }
        } else {
            b.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(h, port)))
            // SOCKS5 的用户名/密码认证 OkHttp 的 proxyAuthenticator 不处理，由 JVM 的 Authenticator 回答
            if (user.isNotEmpty()) SocksAuth.register(h, port, user, pass)
        }
    }

    // -- 会话 -------------------------------------------------------------
    fun login(email: String, password: String): Account {
        val body = FormBody.Builder()
            .add("isModal", "true")
            .add("email", email)
            .add("password", password)
            .add("site_mode", "books")
            .add("action", "login")
            .add("redirectUrl", "")
            .add("gg_json_mode", "1")
            .build()
        val req = Request.Builder()
            .url("$host/rpc.php")
            .post(body)
            .header("x-requested-with", "XMLHttpRequest")
            .header("accept", "application/json, text/javascript, */*; q=0.01")
            .header("origin", host)
            .header("referer", "$host/login")
            .header("user-agent", UA)
            .header("accept-language", "zh-CN,zh;q=0.9")
            .build()
        val (code, text) = client.newCall(req).execute().use { it.code to it.body.string() }
        val json = runCatching { gson.fromJson(text, Map::class.java) }.getOrNull()
            ?: throw ZLibraryException("登录失败：HTTP $code")
        @Suppress("UNCHECKED_CAST")
        val resp = (json["response"] as? Map<String, Any?>) ?: emptyMap()
        // 与 Python 的真值判断一致：validationError 为 false/空/0 时不算错误（成功响应常带 "validationError": false）
        if (truthy(resp["validationError"])) {
            throw ZLibraryException("登录被拒绝：${resp["message"] ?: json["errors"] ?: "邮箱或密码错误"}")
        }
        val userKey = resp["user_key"] as? String
            ?: throw ZLibraryException("登录失败：${json["errors"] ?: "邮箱或密码错误"}")
        val userId = (resp["user_id"]?.toString() ?: "").substringBefore(".")
        jar.set("remix_userid", userId, hostName)
        jar.set("remix_userkey", userKey, hostName)
        account = Account(id = userId.toLongOrNull() ?: 0, email = email, key = userKey)
        refreshAccount()
        return account!!
    }

    /** 用会话 cookie 调 Z-Library 的注销接口，使服务端会话失效（不校验、失败时抛异常，由调用方忽略） */
    fun logout(userId: String, userKey: String) {
        jar.set("remix_userid", userId, hostName)
        jar.set("remix_userkey", userKey, hostName)
        val req = Request.Builder()
            .url("$host/papi/user/logout")
            .post(ByteArray(0).toRequestBody(null))
            .header("accept", "*/*")
            .header("x-requested-with", "XMLHttpRequest")
            .header("origin", host)
            .header("referer", "$host/")
            .header("user-agent", UA)
            .build()
        client.newCall(req).execute().use { it.body.string() }
    }

    fun useSession(userId: String, userKey: String, email: String = ""): Account {
        jar.set("remix_userid", userId, hostName)
        jar.set("remix_userkey", userKey, hostName)
        account = Account(id = userId.toLongOrNull() ?: 0, email = email, key = userKey)
        refreshAccount()
        return account!!
    }

    /** 连通性测试：通过（代理）访问站点并通过反爬关卡，失败时抛 [ZLibraryException] */
    fun ping() {
        passChallenge(host).use { it.body.string() }
    }

    fun refreshAccount(): Account {
        val acct = account ?: throw ZLibraryException("未登录")
        val html = passChallenge("$host/profile").use { it.body.string() }
        val m = USER_JSON_RE.find(html) ?: throw ZLibraryException("会话已失效，请重新登录")
        @Suppress("UNCHECKED_CAST")
        val user = gson.fromJson(m.groupValues[1], Map::class.java) as Map<String, Any?>
        acct.id = (user["id"]?.toString() ?: acct.id.toString()).substringBefore(".").toLongOrNull() ?: acct.id
        acct.email = (user["email"] as? String) ?: acct.email
        acct.key = (user["key"] as? String) ?: acct.key
        acct.premium = html.contains("profile-header__status--premium")
        PREMIUM_UNTIL_RE.find(html)?.let { acct.premiumUntil = it.groupValues[1].trim() }
        for (tile in TILE_RE.findAll(html)) {
            val value = tile.groupValues[1].trim()
            val icon = tile.groupValues[2]
            if (icon == "download" && "/" in value) {
                val (used, total) = value.split("/", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                acct.downloadsToday = used.trim().toIntOrNull() ?: 0
                acct.downloadsLimit = total.trim().toIntOrNull() ?: 0
            }
        }
        return acct
    }

    /**
     * 从下载记录页读取额度：已用 / 额度、以及重置倒计时（/profile 没有倒计时）。
     * 尽力而为：页面取不到时保持 [refreshAccount] 已读到的值。会话失效时抛异常
     */
    fun refreshQuota(): Account {
        val acct = account ?: throw ZLibraryException("未登录")
        val html = passChallenge("$host/users/downloads").use { it.body.string() }
        // 未登录时是登录页，没有下载记录的标记
        if (!DL_COUNT_RE.containsMatchIn(html) && !DL_RESET_RE.containsMatchIn(html) && "dstats-row" !in html) {
            throw ZLibraryException("会话已失效，请重新登录")
        }
        DL_COUNT_RE.find(html)?.let {
            acct.downloadsToday = it.groupValues[1].toIntOrNull() ?: acct.downloadsToday
            acct.downloadsLimit = it.groupValues[2].toIntOrNull() ?: acct.downloadsLimit
        }
        DL_RESET_RE.find(html)?.let { acct.downloadsReset = it.groupValues[1].trim() }
        return acct
    }

    // -- 反爬 -------------------------------------------------------------
    private fun passChallenge(url: String, referer: String? = null): Response {
        var r = get(url, referer)
        repeat(6) {
            if (r.code == 200 && !isInterstitial(peek(r))) return r
            val html = r.body.string()
            r.close()
            if (r.code == 200) {
                if (!passInterstitial()) throw ZLibraryException("$url 需要 JS cookie 关卡但没有 __js_p_")
            } else {
                val token = solvePow(html)
                if (token == null) {
                    Thread.sleep(1500L * (it + 1))
                } else {
                    jar.set("c_token", token, hostName)
                }
            }
            r = get(url, referer)
        }
        if (r.code != 200 || isInterstitial(peek(r))) {
            val c = r.code; r.close()
            throw ZLibraryException("$url 反爬关卡未通过（HTTP $c），可能需要换一个镜像地址或代理")
        }
        return r
    }

    private fun passInterstitial(): Boolean {
        val raw = jar.get("__js_p_") ?: return false
        val code = raw.substringBefore(",").toIntOrNull() ?: return false
        jar.set("__jhash_", solveJhash(code).toString(), hostName)
        jar.set("__jua_", percentEncode(UA, "!'()*-._~"), hostName)
        return true
    }

    private fun get(url: String, referer: String? = null): Response {
        // 不手动设置 accept-encoding：OkHttp 会自动加 gzip 并透明解压；手动设置会关闭透明解压，导致读到压缩数据
        val b = Request.Builder()
            .url(url)
            .header("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("user-agent", UA)
            .header("accept-language", "zh-CN,zh;q=0.9")
        if (referer != null) b.header("referer", referer)
        return client.newCall(b.build()).execute()
    }

    /** 读取响应正文但不消费（用于判断是否是关卡页） */
    private fun peek(r: Response): String = r.peekBody(64 * 1024).string()

    // -- 搜索 -------------------------------------------------------------
    fun search(
        query: String,
        page: Int = 1,
        order: String = "popular",
        extensions: List<String> = emptyList(),
        languages: List<String> = emptyList(),
    ): List<Book> {
        val params = StringBuilder("q=").append(enc(query))
        if (page > 1) params.append("&page=").append(page)
        if (order.isNotEmpty() && order != "popular") params.append("&order=").append(enc(order))
        for (e in extensions) params.append("&extensions[]=").append(enc(e))
        for (l in languages) params.append("&languages[]=").append(enc(l))
        val url = "$host/s/?$params"
        val html = passChallenge(url).use { it.body.string() }
        val books = parseCards(html, host)
        if (books.isNotEmpty()) return books
        // z-library.bz 返回空壳，结果从 /api/search 读取
        return searchApi(query, page, order, referer = url)
    }

    private fun searchApi(query: String, page: Int, order: String, referer: String): List<Book> {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("q", query)
            .addFormDataPart("page", page.toString())
            .addFormDataPart("limit", "20")
            .addFormDataPart("order", order)
            .build()
        val req = Request.Builder()
            .url("$host/api/search")
            .post(body)
            .header("accept", "*/*")
            .header("origin", host)
            .header("referer", referer)
            .header("user-agent", UA)
            .build()
        client.newCall(req).execute().use { r ->
            val ctype = r.header("content-type") ?: ""
            if (r.code != 200 || "json" !in ctype) return emptyList()
            @Suppress("UNCHECKED_CAST")
            val json = gson.fromJson(r.body.string(), Map::class.java) as? Map<String, Any?> ?: return emptyList()
            val list = json["books"] as? List<*> ?: return emptyList()
            return list.filterIsInstance<Map<String, Any?>>()
                .filter { it["deleted"] == null || it["deleted"] == false }
                .map { b ->
                    Book(
                        id = (b["id"]?.toString() ?: "").substringBefore("."),
                        title = b["title"] as? String ?: "",
                        author = b["author"] as? String ?: "",
                        extension = b["extension"] as? String ?: "",
                        filesize = b["filesizeString"] as? String ?: "",
                        year = (b["year"]?.toString() ?: "").substringBefore("."),
                        language = b["language"] as? String ?: "",
                        href = absolute(host, b["href"] as? String ?: ""),
                        download = "",
                    )
                }
        }
    }

    // -- 下载 -------------------------------------------------------------
    private fun resolve(book: Book): String {
        if (book.download.isNotEmpty()) return book.download
        val url = absolute(host, book.href)
        if ("/dl/" in url) return url
        val html = passChallenge(url).use { it.body.string() }
        val m = DL_BUTTON_RE.find(html) ?: throw ZLibraryException("没有找到下载按钮，可能需要登录或该书不可下载")
        return absolute(host, m.groupValues[1])
    }

    /**
     * 下载到 [dest] 目录，返回写入的文件和实际文件名。[onProgress] 回调已下载字节和总字节（总字节未知时为 0）。
     */
    fun download(book: Book, dest: File, onProgress: ((Long, Long) -> Unit)? = null): File {
        var url = resolve(book)
        var r = getDl(url)
        try {
            if (r.code in intArrayOf(301, 302, 303, 307, 308)) {
                val loc = r.header("location") ?: throw ZLibraryException("下载重定向缺少地址")
                url = absolute(url, loc)
                r.close()
                r = downloadClient.newCall(
                    Request.Builder().url(url)
                        .header("referer", "$host/")
                        .header("accept", "*/*")
                        .header("user-agent", UA)
                        .build()
                ).execute()
            }
            if (r.code != 200) throw ZLibraryException("下载失败：HTTP ${r.code}")
            val ctype = r.header("content-type") ?: ""
            if ("text/html" in ctype) {
                throw ZLibraryException("下载被拒绝：${htmlMessage(r.peekBody(64 * 1024).string()) ?: "可能已达今日下载上限或需要登录"}")
            }
            if ("epub" !in ctype && "octet-stream" !in ctype && "zip" !in ctype) {
                throw ZLibraryException("下载失败：返回的不是电子书（$ctype）")
            }
            val name = filename(r.header("content-disposition"))
                ?: (url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.takeIf { it.isNotEmpty() } ?: "book").let {
                    if (it.contains('.')) it else "$it.epub"
                }
            dest.mkdirs()
            val out = File(dest, safe(name))
            val total = r.header("content-length")?.toLongOrNull() ?: 0L
            var written = 0L
            r.body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        written += n
                        onProgress?.invoke(written, total)
                    }
                }
            }
            if (total > 0 && written != total) {
                out.delete()
                throw ZLibraryException("下载不完整：$written/$total 字节")
            }
            return out
        } finally {
            r.close()
        }
    }

    private fun getDl(url: String): Response {
        val build = {
            Request.Builder().url(url)
                .header("referer", "$host/")
                .header("accept", "*/*")
                .header("user-agent", UA)
                .build()
        }
        var r = noRedirectClient.newCall(build()).execute()
        repeat(4) {
            if (r.code != 503) return@repeat
            val html = r.peekBody(64 * 1024).string()
            val token = solvePow(html)
            r.close()
            if (token == null) Thread.sleep(1500L * (it + 1)) else jar.set("c_token", token, hostName)
            r = noRedirectClient.newCall(build()).execute()
        }
        if (r.code == 200 && "text/html" in (r.header("content-type") ?: "")) {
            val msg = htmlMessage(r.peekBody(64 * 1024).string())
            r.close()
            throw ZLibraryException("下载被拒绝：${msg ?: "可能已达今日下载上限或需要登录"}")
        }
        return r
    }

    private fun enc(s: String) = percentEncode(s, "")

    /** 单主机的内存 cookie 容器，支持手动设置（c_token、__jhash_ 等） */
    private class SimpleCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) store[c.name] = c
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = store.values.filter { it.matches(url) }
        fun set(name: String, value: String, host: String) {
            store[name] = Cookie.Builder().name(name).value(value).domain(host).path("/").build()
        }
        fun get(name: String): String? = store[name]?.value
    }

    companion object {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36"
        const val DEFAULT_HOST = "https://z-library.biz"
        // 整数按 Long 解析：默认 Gson 把 JSON 数字当 Double，user_id 等大整数会变成 "1.23E8"，
        // 截断后得到错误的 id（登录后 remix_userid cookie 不对，会话失效）
        private val gson = com.google.gson.GsonBuilder()
            .setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE)
            .create()
        // scheme://[用户名:密码@]host:port
        private val PROXY_RE = Regex("(http|socks4|socks5)://(?:([^:@/]+):([^@/]+)@)?([^:@/]+):(\\d{1,5})")
        private val POW_ARRAY_RE = Regex("a0_0x2a54=\\[([^\\]]+)]")
        private val HEX_RE = Regex("[0-9A-Fa-f]{20,}")
        private val QUOTED_RE = Regex("'([^']*)'")
        private val CARD_RE = Regex("<z-bookcard\\b([^>]*)>(.*?)</z-bookcard>", RegexOption.DOT_MATCHES_ALL)
        private val ATTR_RE = Regex("(\\w+)=\"([^\"]*)\"")
        private val USER_JSON_RE = Regex("const CurrentUser = new User\\((\\{.*?})\\);", RegexOption.DOT_MATCHES_ALL)
        private val PREMIUM_UNTIL_RE = Regex("profile-header__status_date\">\\s*Till ([^<]+?)\\s*<")
        private val DL_COUNT_RE = Regex("""<div class="d-count">\s*(\d+)/(\d+)""")
        private val DL_RESET_RE = Regex("""<div class="d-reset">\s*Downloads will be reset in\s*([^<]+?)\s*</div>""")

        /** 仅供测试：从下载记录页解析 已用、额度、重置倒计时 */
        internal fun parseQuotaForTest(html: String): Triple<Int, Int, String> {
            val c = DL_COUNT_RE.find(html)
            val r = DL_RESET_RE.find(html)
            return Triple(c?.groupValues?.get(1)?.toInt() ?: -1, c?.groupValues?.get(2)?.toInt() ?: -1, r?.groupValues?.get(1)?.trim() ?: "")
        }
        private val TILE_RE = Regex(
            "caret-scroll__title\">\\s*([^<]*?)\\s*</div>\\s*<div class=\"caret-scroll__desc\">\\s*" +
                "<i class=\"zlibicon-([^\"]*)\"></i>\\s*<span>([^<]*)</span>"
        )
        private val DL_BUTTON_RE = Regex("class=\"[^\"]*\\bdlButton\\b[^\"]*\"\\s+href=\"([^\"]+)\"")
        private val SLOT_RE = { name: String -> Regex("<div slot=\"$name\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL) }
        private val CD_UTF8_RE = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
        private val CD_PLAIN_RE = Regex("filename=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        private val ERR_ARTICLE_RE = Regex("<article[^>]*class=\"[^\"]*error[^\"]*\"[^>]*>(.*?)</article>", RegexOption.DOT_MATCHES_ALL)
        private val ERR_H1_RE = Regex("<h1[^>]*>(.*?)</h1>", RegexOption.DOT_MATCHES_ALL)
        private val TAG_RE = Regex("<[^>]+>")
        private val WS_RE = Regex("\\s+")
        private val LIBRARY_RE = Regex("(?:^|\\s)library(?:\\s|$)")

        fun solvePow(html: String): String? {
            val m = POW_ARRAY_RE.find(html) ?: return null
            val parts = QUOTED_RE.findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
            val seed = parts.filter { HEX_RE.matches(it) }.maxByOrNull { it.length } ?: return null
            val check = seed[0].digitToInt(16)
            val md = MessageDigest.getInstance("SHA-1")
            var i = 0L
            while (true) {
                md.reset()
                val digest = md.digest("$seed$i".toByteArray())
                if ((digest[check].toInt() and 0xff) == 0xB0 && (digest[check + 1].toInt() and 0xff) == 0x0B) {
                    return "$seed$i"
                }
                i++
            }
        }

        fun solveJhash(code: Int): Int {
            var x = 123456789L
            var k = 0
            for (i in 0 until 1677696) {
                x = ((x + code) xor (x + (x % 3) + (x % 17) + code) xor i.toLong()) % 16776960
                if (x % 117 == 0L) k = (k + 1) % 1111
            }
            return k
        }

        private fun isInterstitial(html: String) = "get_jhash" in html || "gorizontal-vertikal" in html

        /** 仿 Python 的真值判断：null/false/空串/0 为假 */
        private fun truthy(v: Any?): Boolean = when (v) {
            null -> false
            is Boolean -> v
            is String -> v.isNotEmpty() && v != "false" && v != "0"
            is Number -> v.toDouble() != 0.0
            else -> true
        }

        private fun parseCards(html: String, host: String): List<Book> {
            val books = ArrayList<Book>()
            for (mm in CARD_RE.findAll(html)) {
                val attrs = mm.groupValues[1]
                val inner = mm.groupValues[2]
                val a = ATTR_RE.findAll(attrs).associate { it.groupValues[1] to it.groupValues[2] }
                if ((a["deleted"] ?: "0") != "0" || a["href"].isNullOrEmpty()) continue
                books.add(
                    Book(
                        id = a["id"] ?: "",
                        title = slot(inner, "title").ifEmpty { a["title"] ?: "" },
                        author = slot(inner, "author"),
                        extension = a["extension"] ?: "",
                        filesize = a["filesize"] ?: "",
                        year = a["year"] ?: "",
                        language = a["language"] ?: "",
                        href = absolute(host, a["href"]!!),
                        download = a["download"]?.let { absolute(host, it) } ?: "",
                        premium = "premium" in attrs,
                        inLibrary = LIBRARY_RE.containsMatchIn(attrs),
                    )
                )
            }
            // 页面里混有一个形如卡片的 JS 模板，其 id 为 "${book.id}"，要求是数字 id
            return books
        }

        private fun slot(inner: String, name: String): String {
            val m = SLOT_RE(name).find(inner) ?: return ""
            return unescape(m.groupValues[1].trim())
        }

        private fun unescape(s: String) = s
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")

        private fun absolute(base: String, ref: String): String {
            if (ref.isEmpty()) return ref
            if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
            val b = base.toHttpUrlOrNull() ?: return ref
            return b.resolve(ref)?.toString() ?: ref
        }

        private fun filename(disposition: String?): String? {
            if (disposition.isNullOrEmpty()) return null
            CD_UTF8_RE.find(disposition)?.let {
                return runCatching { URLDecoder.decode(it.groupValues[1], "UTF-8") }.getOrNull()
            }
            return CD_PLAIN_RE.find(disposition)?.groupValues?.get(1)
        }

        private fun safe(name: String): String {
            val cleaned = name.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1f]"), "_").trim(' ', '.')
            return cleaned.ifEmpty { "book.epub" }
        }

        private val IPV4_RE = Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b")
        private val IPV6_RE = Regex("\\b(?:[0-9A-Fa-f]{1,4}:){2,}[0-9A-Fa-f]{0,4}\\b")
        private val DL_LIMIT_RE = Regex("(more than \\d+ downloads|downloads from your ip|daily limit)", RegexOption.IGNORE_CASE)

        /** 仅供测试：校验错误提示已去除服务器 IP */
        internal fun sanitizeMessageForTest(html: String): String? = htmlMessage(html)

        private fun htmlMessage(html: String): String? {
            val m = ERR_ARTICLE_RE.find(html) ?: ERR_H1_RE.find(html) ?: return null
            val text = WS_RE.replace(TAG_RE.replace(m.groupValues[1], " "), " ").trim()
            if (text.isEmpty()) return null
            // 下载上限的提示里带着本服务器的公网 IP，换成简洁提示，避免泄露服务器 IP
            if (DL_LIMIT_RE.containsMatchIn(text)) return "已达该站点今日下载上限，登录 Z-Library 账号后可继续下载"
            // 其它提示也去掉可能出现的 IP
            return IPV6_RE.replace(IPV4_RE.replace(text, "***"), "***")
        }

        /** 仿 Python urllib.parse.quote：保留字母数字和 unreserved、再加 [safe] 里的字符，其余按 UTF-8 百分号编码 */
        private fun percentEncode(s: String, safe: String): String {
            val sb = StringBuilder()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xff
                val ch = c.toChar()
                if (ch.isLetterOrDigit() && c < 128 || ch in "_.-~" || ch in safe) {
                    sb.append(ch)
                } else {
                    sb.append('%').append("%02X".format(c))
                }
            }
            return sb.toString()
        }
    }
}

/**
 * SOCKS5 的用户名/密码认证：OkHttp / JDK 的 SOCKS 实现通过默认的 [java.net.Authenticator] 取凭据。
 * 只对登记过的代理（host:port）回答，其它一律返回 null，不影响 JVM 里的其它连接。
 */
private object SocksAuth : java.net.Authenticator() {
    private val creds = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
    @Volatile private var installed = false

    fun register(host: String, port: Int, user: String, pass: String) {
        creds["$host:$port"] = user to pass
        if (!installed) synchronized(this) {
            if (!installed) {
                setDefault(this)
                installed = true
            }
        }
    }

    override fun getPasswordAuthentication(): java.net.PasswordAuthentication? {
        val c = creds["$requestingHost:$requestingPort"] ?: return null
        return java.net.PasswordAuthentication(c.first, c.second.toCharArray())
    }
}

class ZLibraryException(message: String) : RuntimeException(message)

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val extension: String,
    val filesize: String,
    val year: String,
    val language: String,
    val href: String,
    val download: String,
    val premium: Boolean = false,
    val inLibrary: Boolean = false,
)

data class Account(
    var id: Long,
    var email: String,
    var key: String,
    var premium: Boolean = false,
    var premiumUntil: String = "",
    var downloadsToday: Int = 0,
    var downloadsLimit: Int = 0,
    /** 额度重置倒计时（站点原文，如 "5 hours 30 minutes"），只在下载记录页能拿到 */
    var downloadsReset: String = "",
)
