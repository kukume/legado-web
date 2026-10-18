package io.legado.web.webview

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.help.engine.WebViewProvider
import okhttp3.OkHttpClient
import io.legado.web.security.NetworkGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 通过 Chrome DevTools Protocol 驱动外部浏览器（“系统设置”中的浏览器调试地址），实现阅读的 webView 功能：
 * - 加载页面（或直接加载给定的 html）后执行 js 取值
 * - sourceRegex：嗅探页面发出的资源请求地址
 * - overrideUrlRegex：捕获页面跳转地址
 *
 * @param cdp 浏览器的调试地址（如 http://browser:9222；后台设置修改后立即生效，下次使用时重新连接）
 * @param contexts 记录创建的浏览器上下文，服务被强制结束后下次连接时清理
 */
class CdpWebViewProvider(
    private val cdp: () -> String?,
    private val contexts: BrowserContexts,
) : WebViewProvider, AutoCloseable {

    private companion object {
        /** 没有网页打开多久后断开与浏览器的连接 */
        const val IDLE_MS = 5 * 60_000L
    }

    private val logger = LoggerFactory.getLogger(CdpWebViewProvider::class.java)
    private val startLock = Mutex()
    private val pageLimit = Semaphore(4)
    /** 内网防护检查页面请求（需要解析域名，不能在 CDP 消息线程里阻塞） */
    private val guardScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var connection: CdpConnection? = null

    /** 当前连接的浏览器地址 */
    @Volatile
    private var connectedTo: String? = null

    private val active = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var lastUsed = 0L

    private suspend fun connection(): Pair<CdpConnection, String> = startLock.withLock {
        val addr = cdp() ?: throw io.legado.app.exception.NoStackTraceException("没有设置浏览器")
        connection?.takeIf { it.isOpen && connectedTo == addr }?.let { return it to addr }
        // 地址改了（后台设置）或连接断了：重新连接
        connection?.let { runCatching { it.close() } }
        val (wsUrl, host) = kotlinx.coroutines.withContext(Dispatchers.IO) { RemoteCdp.resolve(addr) }
        val conn = CdpConnection(wsUrl, host)
        conn.awaitOpen()
        connection = conn
        connectedTo = addr
        logger.info("已连接浏览器: {}", addr)
        contexts.cleanup(addr, conn)
        conn to addr
    }

    /** 一段时间没有网页打开就断开连接（下次需要时再连接） */
    private fun scheduleIdleDisconnect() {
        lastUsed = System.currentTimeMillis()
        guardScope.launch {
            delay(IDLE_MS)
            startLock.withLock {
                if (active.get() == 0 && System.currentTimeMillis() - lastUsed >= IDLE_MS && connection != null) {
                    runCatching { connection?.close() }
                    connection = null
                    connectedTo = null
                    logger.info("浏览器空闲超过 {} 分钟，已断开连接", IDLE_MS / 60_000)
                }
            }
        }
    }

    override suspend fun load(request: WebViewProvider.Request): WebViewProvider.Result = pageLimit.withPermit {
        val (conn, addr) = connection()
        active.incrementAndGet()
        var contextId: String? = null
        var targetId: String? = null
        try {
            // 每次都在新的浏览器上下文（相当于无痕窗口）中打开，用完销毁：不留 Cookie、缓存等数据，书源之间互不影响
            contextId = conn.send("Target.createBrowserContext", JsonObject().apply { addProperty("disposeOnDetach", true) })
                .get("browserContextId").asString
            contexts.add(addr, contextId)
            targetId = conn.send("Target.createTarget", JsonObject().apply {
                addProperty("url", "about:blank")
                addProperty("browserContextId", contextId)
            }).get("targetId").asString
            val sessionId = conn.send("Target.attachToTarget", JsonObject().apply {
                addProperty("targetId", targetId)
                addProperty("flatten", true)
            }).get("sessionId").asString
            withTimeout(60_000) { runPage(conn, sessionId, request) }
        } finally {
            targetId?.let { id -> runCatching { conn.send("Target.closeTarget", JsonObject().apply { addProperty("targetId", id) }) } }
            contextId?.let { id ->
                runCatching { conn.send("Target.disposeBrowserContext", JsonObject().apply { addProperty("browserContextId", id) }) }
                contexts.remove(addr, id)
            }
            active.decrementAndGet()
            scheduleIdleDisconnect()
        }
    }

    private suspend fun runPage(
        conn: CdpConnection,
        session: String,
        req: WebViewProvider.Request
    ): WebViewProvider.Result {
        suspend fun cmd(method: String, params: JsonObject = JsonObject()) = conn.send(method, params, session)

        cmd("Page.enable")
        cmd("Network.enable")
        val headers = req.headerMap.orEmpty().toMutableMap()
        // User-Agent 不用书源的，始终用浏览器自己的（与浏览器的其他特征一致）
        headers.keys.removeIf { it.equals("User-Agent", true) || it.equals("Cookie", true) }
        if (headers.isNotEmpty()) {
            cmd("Network.setExtraHTTPHeaders", JsonObject().apply {
                add("headers", JsonObject().apply { headers.forEach { (k, v) -> addProperty(k, v) } })
            })
        }
        val pageUrl = req.url?.substringBefore(",{") ?: "about:blank"
        val cookie = req.cookie ?: req.headerMap?.entries?.firstOrNull { it.key.equals("Cookie", true) }?.value
        if (!cookie.isNullOrBlank() && pageUrl.startsWith("http")) {
            val cookies = JsonArray()
            cookie.split(';').mapNotNull { c ->
                val kv = c.split('=', limit = 2)
                if (kv.size == 2 && kv[0].isNotBlank()) kv[0].trim() to kv[1].trim() else null
            }.forEach { (k, v) ->
                cookies.add(JsonObject().apply {
                    addProperty("name", k)
                    addProperty("value", v)
                    addProperty("url", pageUrl)
                })
            }
            cmd("Network.setCookies", JsonObject().apply { add("cookies", cookies) })
        }

        val loaded = CompletableDeferred<Unit>()
        val captured = CompletableDeferred<String>()
        val sourceRegex = req.sourceRegex?.takeIf { it.isNotBlank() }?.toRegex()
        val overrideRegex = req.overrideUrlRegex?.takeIf { it.isNotBlank() }?.toRegex()
        val html = req.html

        val listener: (String, JsonObject) -> Unit = listener@{ method, params ->
            when (method) {
                "Page.loadEventFired" -> loaded.complete(Unit)
                "Network.requestWillBeSent" -> {
                    val url = params.getAsJsonObject("request")?.get("url")?.asString
                    val type = params.get("type")?.asString
                    if (url != null) {
                        if (sourceRegex != null && sourceRegex.matches(url)) captured.complete(url)
                        if (overrideRegex != null && type == "Document" && overrideRegex.matches(url)) captured.complete(url)
                    }
                }
                "Fetch.requestPaused" -> {
                    val requestId = params.get("requestId").asString
                    val reqUrl = params.getAsJsonObject("request")?.get("url")?.asString.orEmpty()
                    val isPage = params.get("resourceType")?.asString == "Document" &&
                        (!pageUrl.startsWith("http") || reqUrl.substringBefore('#') == pageUrl.substringBefore('#'))
                    if (html == null || !isPage) {
                        // 内网防护：页面里的请求（包括跳转、图片、脚本、接口）目标是本机或内网地址时让它失败
                        guardScope.launch {
                            val host = runCatching { java.net.URI(reqUrl).host }.getOrNull()
                            val blocked = host != null && NetworkGuard.isBlockedHost(host)
                            if (blocked) {
                                conn.sendAsync("Fetch.failRequest", JsonObject().apply {
                                    addProperty("requestId", requestId)
                                    addProperty("errorReason", "AccessDenied")
                                }, session)
                            } else {
                                conn.sendAsync("Fetch.continueRequest", JsonObject().apply { addProperty("requestId", requestId) }, session)
                            }
                        }
                        return@listener
                    }
                    // 用给定的 html 响应页面请求
                    conn.sendAsync("Fetch.fulfillRequest", JsonObject().apply {
                        addProperty("requestId", requestId)
                        addProperty("responseCode", 200)
                        add("responseHeaders", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("name", "Content-Type")
                                addProperty("value", "text/html; charset=utf-8")
                            })
                        })
                        addProperty("body", Base64.getEncoder().encodeToString((html ?: "").toByteArray()))
                    }, session)
                }
            }
        }
        conn.addListener(session, listener)
        try {
            if (NetworkGuard.isEnabled) {
                // 内网防护：拦截页面的所有请求逐个检查（给定 html 时页面请求仍由下面的 fulfillRequest 响应）
                cmd("Fetch.enable", JsonObject().apply {
                    add("patterns", JsonArray().apply { add(JsonObject().apply { addProperty("urlPattern", "*") }) })
                })
            } else if (html != null) {
                cmd("Fetch.enable", JsonObject().apply {
                    add("patterns", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("urlPattern", if (pageUrl.startsWith("http")) pageUrl else "*")
                            addProperty("resourceType", "Document")
                        })
                    })
                })
            }
            val navUrl = if (pageUrl.startsWith("http")) pageUrl else "http://localhost/"
            cmd("Page.navigate", JsonObject().apply { addProperty("url", navUrl) })

            if (sourceRegex != null || overrideRegex != null) {
                val url = withTimeoutOrNull(30_000) { captured.await() }
                    ?: throw io.legado.app.exception.NoStackTraceException("webView 未嗅探到匹配的地址")
                return WebViewProvider.Result(url, url)
            }
            withTimeoutOrNull(30_000) { loaded.await() }
            // 与 App 的 BackstageWebView 相同：页面加载完成后再等 1 秒（加上书源设置的延迟）才取值，
            // 让页面里的脚本有时间生成内容（很多页面的正文、列表是加载完成后由脚本填充的）
            delay(1000 + req.delayTime.coerceAtLeast(0))
            val js = req.javaScript?.takeIf { it.isNotBlank() } ?: "document.documentElement.outerHTML"
            var result = ""
            for (i in 0 until 30) {
                val value = evaluate(conn, session, js)
                if (!value.isNullOrEmpty() && value != "null" && value != "undefined") {
                    result = value
                    break
                }
                delay(1000)
            }
            val finalUrl = evaluate(conn, session, "location.href") ?: navUrl
            return WebViewProvider.Result(finalUrl, result)
        } finally {
            conn.removeListener(session, listener)
        }
    }

    private suspend fun evaluate(conn: CdpConnection, session: String, js: String): String? {
        val res = conn.send("Runtime.evaluate", JsonObject().apply {
            addProperty("expression", js)
            addProperty("returnByValue", true)
            addProperty("awaitPromise", true)
        }, session)
        val r = res.getAsJsonObject("result") ?: return null
        val v = r.get("value") ?: return null
        return if (v.isJsonPrimitive) v.asString else v.toString()
    }

    override fun close() {
        runCatching { connection?.close() }
    }

    /**
     * 最小化的 CDP WebSocket 连接
     */
    internal class CdpConnection(url: String, hostHeader: String? = null) {
        private val idGen = AtomicInteger(1)
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
        private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(String, JsonObject) -> Unit>>()
        private val opened = CompletableDeferred<Unit>()

        @Volatile
        var isOpen = false
            private set

        // 单独的客户端：引擎的 okHttpClient 带有改写请求 / 响应的拦截器（UA、Cookie 等），
        // 会破坏 WebSocket 连接（报错 “Web Socket socket missing: bad interceptor?”）
        private val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        private val ws: WebSocket = client.newWebSocket(Request.Builder().url(url).apply { hostHeader?.let { header("Host", it) } }.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isOpen = true
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
                val id = msg.get("id")?.asInt
                if (id != null) {
                    val d = pending.remove(id) ?: return
                    val error = msg.getAsJsonObject("error")
                    if (error != null) {
                        d.completeExceptionally(RuntimeException("CDP: " + error.get("message")?.asString))
                    } else {
                        d.complete(msg.getAsJsonObject("result") ?: JsonObject())
                    }
                } else {
                    val method = msg.get("method")?.asString ?: return
                    val session = msg.get("sessionId")?.asString ?: ""
                    val params = msg.getAsJsonObject("params") ?: JsonObject()
                    listeners[session]?.forEach { runCatching { it(method, params) } }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isOpen = false
                opened.completeExceptionally(t)
                pending.values.forEach { it.completeExceptionally(t) }
                pending.clear()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isOpen = false
            }
        })

        suspend fun awaitOpen() = withTimeout(10_000) { opened.await() }

        fun sendAsync(method: String, params: JsonObject, sessionId: String? = null): CompletableDeferred<JsonObject> {
            val id = idGen.getAndIncrement()
            val d = CompletableDeferred<JsonObject>()
            pending[id] = d
            val msg = JsonObject().apply {
                addProperty("id", id)
                addProperty("method", method)
                add("params", params)
                sessionId?.let { addProperty("sessionId", it) }
            }
            if (!ws.send(msg.toString())) {
                pending.remove(id)
                d.completeExceptionally(IllegalStateException("CDP 连接已断开"))
            }
            return d
        }

        suspend fun send(method: String, params: JsonObject = JsonObject(), sessionId: String? = null): JsonObject =
            withTimeout(30_000) { sendAsync(method, params, sessionId).await() }

        fun addListener(session: String, l: (String, JsonObject) -> Unit) {
            listeners.getOrPut(session) { CopyOnWriteArrayList() }.add(l)
        }

        fun removeListener(session: String, l: (String, JsonObject) -> Unit) {
            listeners[session]?.remove(l)
        }

        fun close() {
            ws.close(1000, null)
        }
    }
}
