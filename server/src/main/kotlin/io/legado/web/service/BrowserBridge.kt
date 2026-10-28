package io.legado.web.service

import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.engine.EngineContext
import io.legado.app.help.http.CookieManager
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.tagEngineContext
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.ForbiddenException
import io.legado.web.auth.NotFoundException
import io.legado.web.webview.RemoteBrowserManager
import io.legado.web.webview.RemoteSession
import kotlinx.coroutines.channels.Channel
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 书源脚本的“内置浏览器”（java.startBrowserAwait）和图片验证码（java.getVerificationCode）。
 *
 * App 中这两个方法会弹出界面并阻塞脚本，等用户操作完再把结果交回脚本。网页版的做法：
 * 1. 用户打开的页面通过 SSE（/api/events）与服务器保持连接；
 * 2. 脚本调用时，把网页（HTML 或地址）/ 验证码图片推送给该用户的页面，脚本阻塞等待；
 * 3. 用户在弹窗中操作，点“完成”后前端把网页最终的地址和源码（或输入的验证码）提交回来；
 * 4. 脚本拿到结果继续执行（例如自己解析设置结果并保存书源变量），和在 App 中完全一样。
 *
 * 用户没有打开页面（没有 SSE 连接）时返回 null，按原来的方式处理（新标签页打开 / 重新请求）。
 */
class BrowserBridge : SourceVerificationHelp.Handler {

    private val logger = LoggerFactory.getLogger(BrowserBridge::class.java)

    /** 推送给前端的事件 */
    data class BrowserEvent(
        /** browser：书源网页（等待完成）；captcha：图片验证码；sheet：底部弹窗网页（不等待）；close：请求已结束；notice：只提示（title 为原因） */
        val type: String,
        val token: String,
        val sourceName: String = "",
        val sourceUrl: String = "",
        val title: String = "",
        /** 网页地址（http/https），或验证码图片地址 */
        val url: String = "",
        /** 网页源码（data: 地址或脚本直接给出的 HTML），前端放进隔离的 iframe 中显示 */
        val html: String? = null,
        /** sheet：书源给出的弹窗样式（JSON，如 heightPercentage） */
        val config: String? = null,
        /**
         * browser 且为其他网站的网址时：打开网页后书源是否会读取 Cookie（决定是否显示 Cookie 输入框）；
         * null 表示无法判断
         */
        val needCookie: Boolean? = null,
        /**
         * 网页可以由前端自己渲染、网页中的请求经服务器转发（书源自己生成的网页，或书源自己网站的网址）。
         * 解决网页在隔离的 iframe 中请求书源网站时的跨域问题
         */
        val proxy: Boolean = false,
        /** 网页在服务器上的浏览器里打开，前端显示实时画面并转发操作（远程浏览器） */
        val remote: Boolean = false,
        /**
         * 书源登录、变量设置中，远程浏览器可用时先让用户选择打开方式（远程浏览器 / 下载网页在本页显示）。
         * 选择后用同一个 token 推送正式的事件
         */
        val choose: Boolean = false,
    )

    /** 网页中的请求（经服务器转发） */
    data class ProxyRequest(
        val url: String = "",
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        /** 请求体（Base64） */
        val body: String? = null,
        /** 发起请求的网页地址（作为 Referer） */
        val referer: String? = null,
    )

    /** cookie：请求后该网址的 Cookie（网页中 document.cookie 读取时使用） */
    data class ProxyResponse(val status: Int, val url: String, val headers: Map<String, String>, val body: String, val cookie: String = "")

    /** 网页中 document.cookie 写入的 Cookie */
    data class PageCookie(val url: String = "", val cookie: String = "")

    /** 一个网页的转发权限：只能访问书源自己的网站（书源地址、登录脚本、jsLib、书源变量中出现的网站）和网页本身的网站 */
    private class ProxySession(val userId: Long, val hosts: Set<String>, val created: Long = System.currentTimeMillis())

    data class BrowserResult(
        val url: String = "",
        val body: String = "",
        val cancel: Boolean = false,
        /**
         * 用户手动填写的 Cookie（其他网站的网页在浏览器里登录后，服务器读不到浏览器的 Cookie）。
         * 在脚本继续执行前保存到该网站的 Cookie 中，脚本随后用 cookie.getKey / getCookie 就能读到。
         */
        val cookie: String? = null,
    )

    private class Pending(
        val userId: Long,
        @Volatile var event: BrowserEvent,
        val future: CompletableFuture<BrowserResult> = CompletableFuture(),
        /** choose 事件：用户选择的打开方式（remote / page） */
        val mode: CompletableFuture<String> = CompletableFuture(),
    )

    private class Listener(val clientId: String?, val channel: Channel<String>)

    private val listeners = ConcurrentHashMap<Long, MutableSet<Listener>>()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val sessions = ConcurrentHashMap<String, ProxySession>()

    /** 远程浏览器（服务器上有 Chrome / Edge 时由 Application 设置） */
    var remoteBrowser: RemoteBrowserManager? = null

    /** 后台设置中是否开启远程浏览器（由 Application 设置）；关闭时按原来的方式交给前端显示 */
    var remoteEnabled: () -> Boolean = { false }

    /** 远程浏览器可用：设置了浏览器调试地址，并且开启了“使用远程浏览器” */
    private fun remoteAvailable() = remoteBrowser != null && remoteEnabled()

    /** 远程网页：排队时 session 为 null；轮到后打开，打开失败时 error 不为空 */
    private class RemoteEntry(val userId: Long) {
        @Volatile var session: RemoteSession? = null
        @Volatile var error: String? = null
        /** 已占用浏览器（结束时要释放，让排队的下一个打开） */
        @Volatile var acquired = false
        /** 打开完成（成功或失败）或放弃排队 */
        @Volatile var settled = false
        /** 正在显示画面的连接数；显示过一次后，全部断开的时间 */
        val screens = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile var shown = false
        @Volatile var screenLeftAt = 0L
    }
    private val remoteSessions = ConcurrentHashMap<String, RemoteEntry>()

    /** 用户的页面全部断开（刷新、关闭）的时间 */
    private val offlineSince = ConcurrentHashMap<Long, Long>()

    /**
     * 远程网页已经没人看了：显示过的网页画面连接断开超过 [ABANDON_MS]（刷新后会重新弹出并重连），
     * 或者该用户的页面全部断开超过 [ABANDON_MS]（还在排队、还没显示出来的网页）。
     * 这时自动取消，关闭网页并让排队的下一个打开，不必等到超时
     */
    private fun abandoned(entry: RemoteEntry): Boolean {
        val now = System.currentTimeMillis()
        if (entry.shown && entry.screens.get() == 0 && now - entry.screenLeftAt > ABANDON_MS) return true
        val since = offlineSince[entry.userId] ?: return false
        return !hasListener(entry.userId) && now - since > ABANDON_MS
    }

    /** 远程画面连接建立 / 断开（由画面的 SSE 路由调用） */
    fun screenOpened(token: String) {
        remoteSessions[token]?.let { it.screens.incrementAndGet(); it.shown = true }
    }

    fun screenClosed(token: String) {
        remoteSessions[token]?.let { e ->
            if (e.screens.decrementAndGet() <= 0) e.screenLeftAt = System.currentTimeMillis()
        }
    }

    companion object {
        /** 等待用户操作的最长时间 */
        private const val MAX_WAIT_MS = 10 * 60_000L
        /** 远程网页没人看多久后自动关闭 */
        private const val ABANDON_MS = 15_000L
        /** notice 事件的原因：需要远程浏览器（前端据此显示本地化的提示） */
        const val NOTICE_REMOTE_REQUIRED = "remote-browser-required"
        private const val MAX_HTML = 4 * 1024 * 1024
        private const val SESSION_TTL = 2 * 3600_000L
        private const val MAX_PROXY_BODY = 2 * 1024 * 1024
        private const val MAX_PROXY_RESPONSE = 8 * 1024 * 1024
        private val hostRegex = Regex("""https?://([A-Za-z0-9.\-]+)""")
        private val blockedRequestHeaders = setOf(
            "host", "cookie", "content-length", "connection", "origin", "referer", "accept-encoding", "user-agent",
            "keep-alive", "transfer-encoding", "te", "upgrade", "proxy-authorization", CookieManager.cookieJarHeader.lowercase(),
        )
        private val droppedResponseHeaders = setOf(
            "set-cookie", "set-cookie2", "content-encoding", "content-length", "transfer-encoding", "connection", "keep-alive",
        )
    }

    // ---------------- 网页请求转发 ----------------

    private fun hostsOf(vararg texts: String?): Set<String> =
        texts.filterNotNull().flatMap { t -> hostRegex.findAll(t.take(4_000_000)).map { it.groupValues[1].lowercase().trimEnd('.') }.toList() }.toSet()

    private fun sourceHosts(source: BaseSource): Set<String> =
        hostsOf(source.getKey(), source.loginUrl, source.jsLib, source.header, runCatching { source.getVariable() }.getOrNull())

    /** 为网页建立转发权限，返回前端是否可以自己渲染该网页 */
    private fun openSession(token: String, userId: Long, source: BaseSource, url: String, html: String?): Boolean {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { now - it.value.created > SESSION_TTL }
        val own = sourceHosts(source)
        val pageHost = url.toHttpUrlOrNull()?.host?.lowercase()
        sessions[token] = ProxySession(userId, own + hostsOf(html) + listOfNotNull(pageHost))
        return html != null || (pageHost != null && pageHost in own)
    }

    /**
     * 转发网页中的请求：带上该用户保存的 Cookie，响应中的 Cookie 保存下来（与书源请求共用，和 App 中 WebView 与书源共用 Cookie 相同）
     */
    fun fetch(userId: Long, token: String, req: ProxyRequest): ProxyResponse {
        val session = sessions[token] ?: throw NotFoundException("网页已关闭，请重新打开")
        if (session.userId != userId) throw ForbiddenException()
        val url = req.url.toHttpUrlOrNull() ?: throw BadRequestException("地址无效")
        if (url.host.lowercase() !in session.hosts) throw ForbiddenException("只能访问书源自己的网站（${url.host}）")
        val method = req.method.uppercase()
        if (method !in setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")) throw BadRequestException("不支持的请求方法")
        val bodyBytes = req.body?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() ?: throw BadRequestException("请求体无效") }
        if ((bodyBytes?.size ?: 0) > MAX_PROXY_BODY) throw BadRequestException("请求体过大")
        val builder = Request.Builder().url(url)
        req.headers.forEach { (k, v) -> if (k.lowercase() !in blockedRequestHeaders && k.isNotBlank()) builder.header(k, v.take(8192)) }
        builder.header(CookieManager.cookieJarHeader, "1")
        req.referer?.takeIf { it.startsWith("http", true) }?.let { builder.header("Referer", it.take(2048)) }
        val contentType = req.headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value
        val body = if (method == "GET" || method == "HEAD") null else (bodyBytes ?: ByteArray(0)).toRequestBody(contentType?.toMediaTypeOrNull())
        builder.method(method, body)
        return EngineContext.with(EngineContext(userId)) {
            okHttpClient.newCall(builder.tagEngineContext().build()).execute().use { res ->
                val bytes = res.body.byteStream().use { it.readNBytes(MAX_PROXY_RESPONSE + 1) }
                if (bytes.size > MAX_PROXY_RESPONSE) throw BadRequestException("网页内容过大")
                val headers = res.headers.names()
                    .filter { it.lowercase() !in droppedResponseHeaders }
                    .associate { it.lowercase() to res.headers(it).joinToString(", ") }
                val finalUrl = res.request.url.toString()
                ProxyResponse(res.code, finalUrl, headers, Base64.getEncoder().encodeToString(bytes), CookieStore.getCookie(finalUrl))
            }
        }
    }

    /**
     * 网页中 document.cookie 写入的 Cookie：保存到该网站（App 中 WebView 的 Cookie 与书源共用，
     * 书源随后用 cookie.getCookie 读取网页写入的结果，如书源设置中心的气泡样式）
     */
    fun setPageCookie(userId: Long, token: String, c: PageCookie) {
        val session = sessions[token] ?: throw NotFoundException("网页已关闭，请重新打开")
        if (session.userId != userId) throw ForbiddenException()
        val url = c.url.toHttpUrlOrNull() ?: throw BadRequestException("地址无效")
        if (url.host.lowercase() !in session.hosts) throw ForbiddenException("只能访问书源自己的网站（${url.host}）")
        val pair = c.cookie.substringBefore(';').trim()
        val name = pair.substringBefore('=', "").trim()
        if (name.isEmpty() || pair.length > 8192) throw BadRequestException("Cookie 无效")
        EngineContext.with(EngineContext(userId)) { CookieStore.replaceCookie(url.toString(), pair) }
    }

    // ---------------- SSE 连接 ----------------

    fun register(userId: Long, clientId: String?): Channel<String> {
        val ch = Channel<String>(Channel.BUFFERED)
        listeners.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(Listener(clientId, ch))
        offlineSince.remove(userId)
        // 新打开的页面也能看到正在等待的请求
        pending.values.filter { it.userId == userId }.forEach { ch.trySend(GSON.toJson(it.event)) }
        return ch
    }

    fun unregister(userId: Long, ch: Channel<String>) {
        listeners[userId]?.let { set ->
            set.removeIf { it.channel === ch }
            if (set.isEmpty()) {
                listeners.remove(userId, set)
                offlineSince[userId] = System.currentTimeMillis()
            }
        }
        ch.close()
    }

    /** 发给发起请求的标签页；该标签页没有连接时发给该用户的所有页面 */
    private fun broadcast(userId: Long, event: BrowserEvent, clientId: String? = null) {
        val json = GSON.toJson(event)
        val all = listeners[userId] ?: return
        val targets = clientId?.let { id -> all.filter { it.clientId == id } }?.takeIf { it.isNotEmpty() } ?: all
        targets.forEach { it.channel.trySend(json) }
    }

    fun hasListener(userId: Long) = !listeners[userId].isNullOrEmpty()

    // ---------------- 结果提交 ----------------

    suspend fun submit(userId: Long, token: String, result: BrowserResult) {
        val p = pending[token] ?: throw NotFoundException("该网页请求已结束")
        if (p.userId != userId) throw ForbiddenException()
        // 远程浏览器：网址、源码由服务器从浏览器取；网页写入的 Cookie 保存下来供书源随后的请求使用
        remoteSessions[token]?.let { entry ->
            val session = entry.session
            // 取消，或还在排队、打开失败时点了完成：都按取消处理
            if (result.cancel || session == null) {
                p.future.complete(BrowserResult(cancel = true))
                return
            }
            val r = session.result()
            r.cookies.forEach { (u, c) -> saveCookie(userId, u, c) }
            p.future.complete(BrowserResult(r.url, r.html))
            return
        }
        val cookie = result.cookie?.let { normalizeCookie(it) }
        if (!result.cancel && cookie != null && p.event.type == "browser" && p.event.url.startsWith("http", true)) {
            saveCookie(userId, p.event.url, cookie)
        }
        p.future.complete(result)
    }

    // ---------------- 远程浏览器 ----------------

    /**
     * 远程浏览器打开网页的请求：按书源的请求头（User-Agent）、Cookie 和请求方式（POST / 编码），与 App 的内置浏览器相同。
     * 没开启远程浏览器、没有浏览器或不是网址时返回 null（按原来的方式交给前端显示）
     */
    private fun remoteRequest(source: BaseSource, url: String): RemoteBrowserManager.OpenRequest? {
        // 只接管 http(s) 网址；书源自己生成的 data:text/html 网页（如设置中心）在用户当前的浏览器里渲染
        if (remoteBrowser == null || !url.startsWith("http", true) || !remoteEnabled()) return null
        return try {
            val analyzeUrl = AnalyzeUrl(url, source = source)
            val req = analyzeUrl.browserRequest()
            RemoteBrowserManager.OpenRequest(
                method = req.method, url = req.url, body = req.body, contentType = req.contentType,
                headers = analyzeUrl.headerMap.toMap(), cookie = CookieStore.getCookie(req.url),
                sourceUrl = source.getKey(),
            )
        } catch (e: Exception) {
            logger.warn("无法解析网址 {}，改用前端显示: {}", url, e.message)
            null
        }
    }

    /** 排队（同一时间只打开一个网页），轮到后打开；用户取消或请求结束时放弃排队 */
    private fun startRemote(manager: RemoteBrowserManager, token: String, req: RemoteBrowserManager.OpenRequest, entry: RemoteEntry, p: Pending, ctx: EngineContext) {
        try {
            if (!manager.acquire(token) { ctx.cancelled || p.future.isDone || abandoned(entry) }) {
                if (abandoned(entry)) {
                    logger.info("排队中的网页已无人查看，退出排队")
                    p.future.complete(BrowserResult(cancel = true))
                }
                return
            }
            entry.acquired = true
            entry.session = kotlinx.coroutines.runBlocking { manager.open(req) }
        } catch (e: Exception) {
            logger.warn("远程浏览器打开网页失败: {}", e.message)
            entry.error = e.message ?: e.javaClass.simpleName
        } finally {
            entry.settled = true
        }
    }

    /**
     * 等待远程网页打开（排队期间每秒回调排在第几位），返回打开的网页
     */
    suspend fun awaitRemote(userId: Long, token: String, onQueue: suspend (position: Int) -> Unit): RemoteSession {
        while (true) {
            val entry = remoteSessions[token] ?: throw NotFoundException("网页已关闭，请重新打开")
            if (entry.userId != userId) throw ForbiddenException()
            entry.session?.let { return it }
            if (entry.settled) throw BadRequestException("无法打开网页：${entry.error ?: "已取消"}")
            onQueue(remoteBrowser?.queuePosition(token) ?: 0)
            kotlinx.coroutines.delay(1000)
        }
    }

    /** 该用户正在操作的远程网页（还在排队时为 null） */
    fun remoteSession(userId: Long, token: String): RemoteSession? {
        val entry = remoteSessions[token] ?: throw NotFoundException("网页已关闭，请重新打开")
        if (entry.userId != userId) throw ForbiddenException()
        return entry.session
    }

    // ---------------- 引擎回调（在脚本线程中阻塞） ----------------


    override fun getVerificationResult(
        source: BaseSource,
        url: String,
        title: String,
        useBrowser: Boolean,
        html: String?,
    ): Pair<String, String>? {
        val ctx = EngineContext.current ?: return null
        val userId = ctx.userId
        if (userId == 0L || !hasListener(userId)) return null
        val token = UUID.randomUUID().toString()
        // 没有远程浏览器：只有书源的登录、变量设置中才改为下载网页交给前端显示；
        // 搜索、发现、阅读等地方只提示，不打开网页（结果按用户取消处理，返回空内容）
        if (useBrowser && !remoteAvailable() && !ctx.allowPageFallback) {
            broadcast(userId, BrowserEvent("notice", token, source.getTag(), source.getKey(), NOTICE_REMOTE_REQUIRED), ctx.clientId)
            return url to ""
        }
        val remoteReq = if (useBrowser && html.isNullOrBlank()) remoteRequest(source, url) else null
        // 登录、变量设置中两种方式都可用：先让用户选择
        val choose = remoteReq != null && ctx.allowPageFallback
        val pageTitle = title.ifBlank { "书源网页" }
        /** 下载网页交给前端显示的事件（会建立网页请求的转发权限，选定后才调用） */
        fun pageEvent(): BrowserEvent {
            val (pageUrl, pageHtml) = resolveSourceUrl(source, url, html) ?: pageContent(url, html)
            val needCookie = if (pageHtml == null && pageUrl.startsWith("http", true)) BrowserCookieHint.needsCookie(source) else null
            val proxy = openSession(token, userId, source, pageUrl, pageHtml)
            return BrowserEvent("browser", token, source.getTag(), source.getKey(), pageTitle, pageUrl, pageHtml, needCookie = needCookie, proxy = proxy)
        }
        val event = when {
            choose -> BrowserEvent("browser", token, source.getTag(), source.getKey(), pageTitle, remoteReq!!.url, choose = true)
            remoteReq != null -> BrowserEvent("browser", token, source.getTag(), source.getKey(), pageTitle, remoteReq.url, remote = true)
            useBrowser -> pageEvent()
            else -> BrowserEvent("captcha", token, source.getTag(), source.getKey(), title.ifBlank { "请输入验证码" }, url)
        }
        val p = Pending(userId, event)
        pending[token] = p
        var remoteEntry = if (remoteReq != null && !choose) RemoteEntry(userId).also { e -> remoteSessions[token] = e } else null
        ctx.waitingForUser.incrementAndGet()
        val started = System.currentTimeMillis()
        try {
            broadcast(userId, event, ctx.clientId)
            if (choose && waitForMode(p, ctx) == "remote") {
                remoteEntry = RemoteEntry(userId).also { e -> remoteSessions[token] = e }
                p.event = event.copy(choose = false, remote = true)
                broadcast(userId, p.event, ctx.clientId)
            } else if (choose && !p.future.isDone) {
                p.event = pageEvent()
                broadcast(userId, p.event, ctx.clientId)
            }
            // 远程浏览器：先弹出窗口（显示排队情况），轮到后再打开网页
            val entry = remoteEntry
            if (remoteReq != null && entry != null) remoteBrowser?.let { startRemote(it, token, remoteReq, entry, p, ctx) }
            val result = waitFor(p, ctx) {
                // 远程网页没人看了（刷新、关闭了页面）：自动取消，释放浏览器给排队的下一个
                entry != null && abandoned(entry).also { if (it) logger.info("远程网页已无人查看，自动关闭") }
            }
            // 取消：返回空内容（App 中关闭浏览器也不会报错），书源读取不到结果时不会保存
            if (result.cancel) return url to ""
            return (result.url.ifBlank { url }) to result.body
        } finally {
            pending.remove(token)
            sessions.remove(token)
            remoteSessions.remove(token)?.let { e ->
                e.session?.close()
                if (e.acquired) remoteBrowser?.release()
            }
            ctx.userWaitMs.addAndGet(System.currentTimeMillis() - started)
            ctx.waitingForUser.decrementAndGet()
            broadcast(userId, BrowserEvent("close", token))
        }
    }

    override fun showBrowser(source: BaseSource, url: String, html: String?, config: String?): Boolean {
        val ctx = EngineContext.current ?: return false
        if (ctx.userId == 0L || !hasListener(ctx.userId)) return false
        // 普通网址由前端直接加载（网页能拿到自己的地址参数和存储）；其他情况交给前端显示源码
        val (pageUrl, pageHtml) = if (url.startsWith("http", true)) url to null else pageContent(url, html)
        val title = runCatching { GSON.fromJsonObject<Map<String, Any?>>(config).getOrNull()?.get("title") as? String }.getOrNull()
        val token = UUID.randomUUID().toString()
        val event = BrowserEvent(
            "sheet", token, source.getTag(), source.getKey(),
            title ?: source.getTag(), pageUrl, pageHtml, config?.take(4096),
            proxy = openSession(token, ctx.userId, source, pageUrl, pageHtml),
        )
        broadcast(ctx.userId, event, ctx.clientId)
        return true
    }

    /**
     * 等用户选择打开方式；用户取消（结果已提交）或页面长时间无人查看时返回 null（随后按取消处理）
     */
    private fun waitForMode(p: Pending, ctx: EngineContext): String? {
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        while (!p.future.isDone) {
            if (ctx.cancelled) throw kotlinx.coroutines.CancellationException("请求已取消")
            val since = offlineSince[p.userId]
            if (since != null && !hasListener(p.userId) && System.currentTimeMillis() - since > ABANDON_MS) {
                p.future.complete(BrowserResult(cancel = true))
                return null
            }
            if (System.currentTimeMillis() > deadline) throw NoStackTraceException("等待网页操作超时")
            try {
                return p.mode.get(500, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // 继续等待
            }
        }
        return null
    }

    /** 用户选择打开方式：remote（远程浏览器）或 page（下载网页在本页显示） */
    fun chooseMode(userId: Long, token: String, mode: String) {
        val p = pending[token] ?: throw NotFoundException("该网页请求已结束")
        if (p.userId != userId) throw ForbiddenException()
        if (!p.event.choose) throw BadRequestException("这个网页不需要选择打开方式")
        if (mode != "remote" && mode != "page") throw BadRequestException("打开方式无效")
        p.mode.complete(mode)
    }

    private fun waitFor(p: Pending, ctx: EngineContext, abandoned: () -> Boolean = { false }): BrowserResult {
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        while (true) {
            if (ctx.cancelled) throw kotlinx.coroutines.CancellationException("请求已取消")
            if (abandoned()) return BrowserResult(cancel = true)
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw NoStackTraceException("等待网页操作超时")
            try {
                return p.future.get(minOf(left, 1000), TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // 继续等待，同时检查请求是否已取消
            }
        }
    }

    /**
     * 整理用户粘贴的 Cookie：支持 “a=1; b=2”、带 “Cookie:” 前缀的请求头、每行一个的格式
     * @return null 表示没有有效内容
     */
    fun normalizeCookie(raw: String): String? = normalizeCookieInput(raw)

    /** 以指定用户身份把 Cookie 合并保存到该网站（与书源请求共用） */
    fun saveCookie(userId: Long, url: String, cookie: String) {
        EngineContext.with(EngineContext(userId)) { CookieStore.replaceCookie(url, cookie) }
    }

    /**
     * 网页内容：data: 地址解码成 HTML 交给前端（浏览器不允许直接打开 data: 地址）；
     * 普通网址由前端直接加载。
     */
    /**
     * 书源格式的网址（“地址,{"method":"POST","body":…,"charset":…}”）：与 App 的内置浏览器相同，按参数请求。
     * POST 请求由服务器发出（带上书源的请求头、按书源的编码），把得到的网页交给前端显示；GET 只取出真正的地址。
     * 不是这种网址时返回 null
     */
    private fun resolveSourceUrl(source: BaseSource, url: String, html: String?): Pair<String, String?>? {
        if (!html.isNullOrBlank() || !url.startsWith("http", true)) return null
        if (!AnalyzeUrl.paramPattern.matcher(url).find()) return null
        return try {
            val analyzeUrl = AnalyzeUrl(url, source = source)
            if (analyzeUrl.isPost()) {
                val res = analyzeUrl.getStrResponse(useWebView = false)
                res.url to res.body?.take(MAX_HTML)
            } else {
                analyzeUrl.url to null
            }
        } catch (e: Exception) {
            logger.debug("无法解析书源网址 {}: {}", url, e.message)
            null
        }
    }

    private fun pageContent(url: String, html: String?): Pair<String, String?> {
        if (!html.isNullOrBlank()) return url to html.take(MAX_HTML)
        if (url.startsWith("data:", true)) {
            val comma = url.indexOf(',')
            if (comma > 0) {
                val meta = url.substring(5, comma)
                val data = url.substring(comma + 1)
                val text = runCatching {
                    if (meta.endsWith(";base64", true)) String(Base64.getMimeDecoder().decode(data), Charsets.UTF_8)
                    else URLDecoder.decode(data, "UTF-8")
                }.getOrElse {
                    logger.debug("无法解析 data 地址: {}", it.message)
                    null
                }
                if (text != null) return "about:blank" to text.take(MAX_HTML)
            }
        }
        return url to null
    }
}

/**
 * 整理用户粘贴的 Cookie：支持 “a=1; b=2”、带 “Cookie:” 前缀的请求头、每行一个的格式
 * @return null 表示没有有效内容
 */
fun normalizeCookieInput(raw: String): String? {
    val pairs = raw.trim().removePrefix("Cookie:").removePrefix("cookie:")
        .split(";", "\n", "\r")
        .map { it.trim() }
        .filter { it.contains("=") && !it.startsWith("=") }
    if (pairs.isEmpty()) return null
    return pairs.joinToString("; ").take(8192)
}
