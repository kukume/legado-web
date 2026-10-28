package io.legado.web.webview

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.legado.web.security.NetworkGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 远程浏览器：书源要打开网页让用户操作（java.startBrowserAwait 等，如网站的人机验证、登录页）时，
 * 在外部浏览器（“系统设置”中的浏览器调试地址）里打开真实的网页，把画面实时推给用户，用户的点击、滚动、输入再传回浏览器。
 *
 * 与 App 的内置浏览器相同：网页在真正的网站域名下打开，完成后网页写入的 Cookie 保存到服务器，书源随后的请求可以直接使用。
 * 每个网页都在新的浏览器上下文（相当于无痕窗口）中打开，用完销毁，不会在那个浏览器里留下数据。
 *
 * @param cdp 浏览器的调试地址（如 http://browser:9222；后台设置修改后立即生效，下次打开网页时重新连接）
 * @param contexts 记录创建的浏览器上下文，服务被强制结束后下次连接时清理
 */
class RemoteBrowserManager(
    private val cdp: () -> String?,
    private val contexts: BrowserContexts,
) : AutoCloseable {

    private companion object {
        /** 没有网页打开多久后断开与浏览器的连接 */
        const val IDLE_MS = 5 * 60_000L
    }

    private val logger = LoggerFactory.getLogger(RemoteBrowserManager::class.java)
    private val startLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var connection: CdpWebViewProvider.CdpConnection? = null

    /** 当前连接的浏览器地址 */
    @Volatile
    private var connectedTo: String? = null

    private suspend fun connection(): Pair<CdpWebViewProvider.CdpConnection, String> = startLock.withLock {
        val addr = cdp() ?: throw io.legado.app.exception.NoStackTraceException("没有设置浏览器")
        connection?.takeIf { it.isOpen && connectedTo == addr }?.let { return it to addr }
        // 地址改了（后台设置）或连接断了：重新连接
        connection?.let { runCatching { it.close() } }
        val (wsUrl, host) = withContext(Dispatchers.IO) { RemoteCdp.resolve(addr) }
        val conn = CdpWebViewProvider.CdpConnection(wsUrl, host)
        conn.awaitOpen()
        connection = conn
        connectedTo = addr
        logger.info("远程浏览器已连接: {}", addr)
        contexts.cleanup(addr, conn)
        conn to addr
    }

    // ---------------- 排队：同一时间只打开一个网页 ----------------

    /** 公平信号量：先来的先打开 */
    private val slot = java.util.concurrent.Semaphore(1, true)
    private val waiting = java.util.concurrent.CopyOnWriteArrayList<String>()

    /**
     * 排队等候打开网页（在脚本线程中阻塞）：轮到时返回 true，用完后必须调用 [release]；
     * [cancelled] 返回 true（请求取消、用户关闭弹窗）时放弃排队并返回 false
     */
    fun acquire(token: String, cancelled: () -> Boolean): Boolean {
        waiting.add(token)
        try {
            while (true) {
                if (cancelled()) return false
                if (slot.tryAcquire(500, java.util.concurrent.TimeUnit.MILLISECONDS)) return true
            }
        } finally {
            waiting.remove(token)
        }
    }

    fun release() {
        slot.release()
        lastReleased = System.currentTimeMillis()
        // 一段时间没有网页打开就断开连接（下次需要时再连接）
        scope.launch {
            kotlinx.coroutines.delay(IDLE_MS)
            if (isIdle()) shutdownIfIdle()
        }
    }

    @Volatile
    private var lastReleased = 0L

    private fun isIdle() = slot.availablePermits() > 0 && waiting.isEmpty() &&
        System.currentTimeMillis() - lastReleased >= IDLE_MS

    private suspend fun shutdownIfIdle() = startLock.withLock {
        // 加锁后再确认一次：期间可能有新的网页要打开
        if (!isIdle()) return@withLock
        // 外部浏览器不归本服务管：只断开连接
        val conn = connection ?: return@withLock
        runCatching { conn.close() }
        connection = null
        connectedTo = null
        logger.info("远程浏览器空闲超过 {} 分钟，已断开连接", IDLE_MS / 60_000)
    }

    /**
     * 连接一次浏览器，清理上次运行（服务被强制结束）留下的网页；之后空闲时自动断开。
     * 服务启动、浏览器地址修改时调用
     */
    fun cleanupLeftovers() {
        scope.launch {
            runCatching { connection() }.onFailure { logger.warn("连接浏览器失败: {}", it.message) }
            kotlinx.coroutines.delay(IDLE_MS)
            if (isIdle()) shutdownIfIdle()
        }
    }

    /** 排在第几位（1 表示下一个），不在排队中时为 0 */
    fun queuePosition(token: String) = waiting.indexOf(token) + 1

    /** 打开网页的请求 */
    data class OpenRequest(
        val method: String,
        val url: String,
        val body: ByteArray? = null,
        val contentType: String? = null,
        /** 书源的请求头（其中的 User-Agent 不使用，浏览器用自己的） */
        val headers: Map<String, String> = emptyMap(),
        /** 该网站已有的 Cookie（a=1; b=2） */
        val cookie: String? = null,
        /** 书源地址：网页只能在书源和打开的网页所在的网站（含子域名）内跳转，见 [SiteScope] */
        val sourceUrl: String? = null,
    )

    suspend fun open(req: OpenRequest): RemoteSession {
        val (conn, addr) = connection()
        val contextId = conn.send("Target.createBrowserContext", JsonObject().apply {
            addProperty("disposeOnDetach", true)
        }).get("browserContextId").asString
        contexts.add(addr, contextId)
        val targetId = conn.send("Target.createTarget", JsonObject().apply {
            addProperty("url", "about:blank")
            addProperty("browserContextId", contextId)
        }).get("targetId").asString
        val sessionId = conn.send("Target.attachToTarget", JsonObject().apply {
            addProperty("targetId", targetId)
            addProperty("flatten", true)
        }).get("sessionId").asString
        val session = RemoteSession(conn, contextId, targetId, sessionId, req, scope) { contexts.remove(addr, contextId) }
        try {
            session.start()
        } catch (e: Exception) {
            session.close()
            throw e
        }
        return session
    }

    override fun close() {
        runCatching { connection?.close() }
    }
}

/** 一个打开的远程网页 */
class RemoteSession internal constructor(
    private val conn: CdpWebViewProvider.CdpConnection,
    private val contextId: String,
    private val targetId: String,
    private val sessionId: String,
    private val req: RemoteBrowserManager.OpenRequest,
    private val scope: CoroutineScope,
    /** 网页销毁后（从记录中移除） */
    private val onDisposed: () -> Unit = {},
) {
    /** 一帧画面：JPEG（Base64）；width / height 为网页视口的 CSS 像素（点击坐标按它换算） */
    data class Frame(val data: String, val width: Double, val height: Double, val offsetTop: Double)

    /** 最新的一帧（推送慢时只保留最新的，不积压） */
    val frames = MutableStateFlow<Frame?>(null)

    /** 其他通知：地址变化、网页弹出的对话框 */
    val events = Channel<Pair<String, String>>(Channel.BUFFERED)

    @Volatile
    var currentUrl: String = req.url
        private set

    private val closed = AtomicBoolean(false)
    private val closedSignal = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** 等到网页关闭（用户完成、取消或等待超时） */
    suspend fun awaitClosed() = closedSignal.await()
    /** 网页视口：跟着前端弹窗里画面区域的大小（打开时和弹窗大小变化时由前端告知），按手机模式排版 */
    private var viewport = 412 to 860

    private suspend fun cmd(method: String, params: JsonObject = JsonObject()) = conn.send(method, params, sessionId)
    private fun cmdAsync(method: String, params: JsonObject = JsonObject()) = conn.sendAsync(method, params, sessionId)

    private val listener: (String, JsonObject) -> Unit = { method, params -> onEvent(method, params) }

    /**
     * 网页只能在书源的网站内跳转（防止点了外链去浏览其他网站）：书源地址和打开的网页所在的可注册域名及其子域名。
     * 只限制主页面的跳转；网页加载的图片、脚本、接口和内嵌框架（如人机验证）不限制
     */
    private val siteScope = SiteScope(listOf(req.sourceUrl, req.url))

    /** 第一个页面请求要换成 POST（书源格式的网址带 method / body 时） */
    private val firstDocument = AtomicBoolean(true)

    internal suspend fun start() {
        conn.addListener(sessionId, listener)
        cmd("Page.enable")
        cmd("Network.enable")
        // 新建的标签页在后台、没有焦点：点击输入框后打字没有反应。让网页始终认为自己有焦点
        runCatching { cmd("Page.bringToFront") }
        runCatching { cmd("Emulation.setFocusEmulationEnabled", JsonObject().apply { addProperty("enabled", true) }) }
        val headers = req.headers.toMutableMap()
        // Referer 只用于第一次打开页面。全局覆盖会连验证码服务（challenges.cloudflare.com）的请求一起改掉，验证框就不出现
        val referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
            ?.takeIf { it.startsWith("http", true) }
        // 由浏览器自己生成的请求头不要覆盖
        headers.keys.removeIf { k -> listOf("User-Agent", "Cookie", "Content-Type", "Content-Length", "Host", "Accept-Encoding", "Connection", "Referer").any { it.equals(k, true) } }
        // User-Agent 不用书源的，始终用浏览器自己的（与浏览器的其他特征一致）
        // 手机的触摸屏：网页按触摸设备处理（navigator.maxTouchPoints、pointer: coarse）
        runCatching { cmd("Emulation.setTouchEmulationEnabled", JsonObject().apply { addProperty("enabled", true); addProperty("maxTouchPoints", 5) }) }
        if (headers.isNotEmpty()) {
            cmd("Network.setExtraHTTPHeaders", JsonObject().apply {
                add("headers", JsonObject().apply { headers.forEach { (k, v) -> addProperty(k, v) } })
            })
        }
        applyViewport()
        setCookies()
        // 拦截请求：第一个页面请求按书源的方式发（POST）；主页面跳转限制在书源的网站内；其余请求做内网防护检查
        cmd("Fetch.enable", JsonObject().apply {
            add("patterns", JsonArray().apply { add(JsonObject().apply { addProperty("urlPattern", "*") }) })
        })
        cmd("Page.startScreencast", JsonObject().apply {
            addProperty("format", "jpeg")
            addProperty("quality", 70)
            // 2 倍像素密度下限制画面尺寸，控制每帧的大小
            addProperty("maxWidth", 900)
            addProperty("maxHeight", 1900)
            addProperty("everyNthFrame", 1)
        })
        cmd("Page.navigate", JsonObject().apply {
            addProperty("url", req.url)
            referer?.let { addProperty("referrer", it) }
        })
    }

    private suspend fun applyViewport() {
        val (w, h) = viewport
        cmd("Emulation.setDeviceMetricsOverride", JsonObject().apply {
            addProperty("width", w)
            addProperty("height", h)
            // 2 倍像素密度：画面上的文字清晰（与手机屏幕一样）
            addProperty("deviceScaleFactor", 2)
            addProperty("mobile", true)
        })
    }

    private suspend fun setCookies() {
        val cookie = req.cookie?.takeIf { it.isNotBlank() } ?: return
        val cookies = JsonArray()
        cookie.split(';').mapNotNull { c ->
            val kv = c.split('=', limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank()) kv[0].trim() to kv[1].trim() else null
        }.forEach { (k, v) ->
            cookies.add(JsonObject().apply {
                addProperty("name", k)
                addProperty("value", v)
                addProperty("url", req.url)
            })
        }
        if (cookies.size() > 0) cmd("Network.setCookies", JsonObject().apply { add("cookies", cookies) })
    }

    private fun onEvent(method: String, params: JsonObject) {
        when (method) {
            "Page.screencastFrame" -> {
                cmdAsync("Page.screencastFrameAck", JsonObject().apply { addProperty("sessionId", params.get("sessionId").asInt) })
                val meta = params.getAsJsonObject("metadata")
                frames.value = Frame(
                    params.get("data").asString,
                    meta.get("deviceWidth").asDouble,
                    meta.get("deviceHeight").asDouble,
                    meta.get("offsetTop")?.asDouble ?: 0.0,
                )
            }
            "Page.frameNavigated" -> {
                val frame = params.getAsJsonObject("frame")
                if (frame != null && !frame.has("parentId")) {
                    currentUrl = frame.get("url").asString
                    events.trySend("url" to currentUrl)
                }
            }
            // 网页的 alert / confirm：转告用户并自动确认（否则网页会一直卡住）
            "Page.javascriptDialogOpening" -> {
                events.trySend("dialog" to (params.get("message")?.asString ?: ""))
                cmdAsync("Page.handleJavaScriptDialog", JsonObject().apply { addProperty("accept", true) })
            }
            "Fetch.requestPaused" -> onRequestPaused(params)
        }
    }

    private fun onRequestPaused(params: JsonObject) {
        val requestId = params.get("requestId").asString
        val request = params.getAsJsonObject("request")
        val reqUrl = request?.get("url")?.asString.orEmpty()
        val isDocument = params.get("resourceType")?.asString == "Document"
        if (isDocument && req.method == "POST" && firstDocument.compareAndSet(true, false)) {
            val headers = JsonArray()
            request?.getAsJsonObject("headers")?.entrySet()?.forEach { (k, v) ->
                if (!k.equals("Content-Type", true)) headers.add(JsonObject().apply { addProperty("name", k); addProperty("value", v.asString) })
            }
            headers.add(JsonObject().apply { addProperty("name", "Content-Type"); addProperty("value", req.contentType ?: "application/x-www-form-urlencoded") })
            cmdAsync("Fetch.continueRequest", JsonObject().apply {
                addProperty("requestId", requestId)
                addProperty("method", "POST")
                addProperty("postData", Base64.getEncoder().encodeToString(req.body ?: ByteArray(0)))
                add("headers", headers)
            })
            return
        }
        if (isDocument) firstDocument.set(false)
        // 页面目标的主框架 id 就是 targetId；内嵌框架的跳转不限制
        if (isDocument && params.get("frameId")?.asString == targetId && !siteScope.allows(reqUrl)) {
            // Aborted：放弃这次跳转，停留在当前网页（其他错误原因会显示浏览器的错误页）
            cmdAsync("Fetch.failRequest", JsonObject().apply {
                addProperty("requestId", requestId)
                addProperty("errorReason", "Aborted")
            })
            events.trySend("blocked" to (runCatching { java.net.URI(reqUrl).host }.getOrNull() ?: reqUrl))
            return
        }
        if (!NetworkGuard.isEnabled) {
            cmdAsync("Fetch.continueRequest", JsonObject().apply { addProperty("requestId", requestId) })
            return
        }
        // 内网防护：网页里的请求目标是本机或内网地址时让它失败（解析域名不能在 CDP 消息线程里阻塞）
        scope.launch {
            val host = runCatching { java.net.URI(reqUrl).host }.getOrNull()
            if (host != null && NetworkGuard.isBlockedHost(host)) {
                cmdAsync("Fetch.failRequest", JsonObject().apply {
                    addProperty("requestId", requestId)
                    addProperty("errorReason", "AccessDenied")
                })
            } else {
                cmdAsync("Fetch.continueRequest", JsonObject().apply { addProperty("requestId", requestId) })
            }
        }
    }

    // ---------------- 用户操作 ----------------

    /** 用户的操作（坐标为网页视口的 CSS 像素） */
    data class Input(
        /** mouse / wheel / text / key / back / forward / reload / resize */
        val type: String = "",
        /** mouse：down / up / move */
        val action: String = "",
        val x: Double = 0.0,
        val y: Double = 0.0,
        val dx: Double = 0.0,
        val dy: Double = 0.0,
        val text: String = "",
        val key: String = "",
        val code: String = "",
        val keyCode: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
    )

    suspend fun input(e: Input) {
        if (closed.get()) return
        when (e.type) {
            "mouse" -> {
                val type = when (e.action) {
                    "down" -> "mousePressed"
                    "up" -> "mouseReleased"
                    else -> "mouseMoved"
                }
                cmd("Input.dispatchMouseEvent", JsonObject().apply {
                    addProperty("type", type)
                    addProperty("x", e.x)
                    addProperty("y", e.y)
                    addProperty("button", if (type == "mouseMoved") "none" else "left")
                    if (type != "mouseMoved") addProperty("clickCount", 1)
                })
            }
            "wheel" -> cmd("Input.dispatchMouseEvent", JsonObject().apply {
                addProperty("type", "mouseWheel")
                addProperty("x", e.x)
                addProperty("y", e.y)
                addProperty("deltaX", e.dx)
                addProperty("deltaY", e.dy)
            })
            // 输入法选好的文字整段输入
            "text" -> if (e.text.isNotEmpty()) cmd("Input.insertText", JsonObject().apply { addProperty("text", e.text.take(2000)) })
            // 回车、退格、方向键等
            "key" -> {
                for (type in listOf("rawKeyDown", "keyUp")) {
                    cmd("Input.dispatchKeyEvent", JsonObject().apply {
                        addProperty("type", type)
                        addProperty("key", e.key)
                        addProperty("code", e.code)
                        addProperty("windowsVirtualKeyCode", e.keyCode)
                        addProperty("nativeVirtualKeyCode", e.keyCode)
                    })
                    if (type == "rawKeyDown" && e.key == "Enter") {
                        cmd("Input.dispatchKeyEvent", JsonObject().apply { addProperty("type", "char"); addProperty("text", "\r") })
                    }
                }
            }
            "back" -> cmd("Runtime.evaluate", JsonObject().apply { addProperty("expression", "history.back()") })
            "forward" -> cmd("Runtime.evaluate", JsonObject().apply { addProperty("expression", "history.forward()") })
            "reload" -> cmd("Page.reload")
            "resize" -> if (e.width > 0 && e.height > 0) {
                val v = e.width.coerceIn(280, 1600) to e.height.coerceIn(280, 1600)
                if (v != viewport) {
                    viewport = v
                    applyViewport()
                }
            }
        }
    }

    /** 完成：当前网址、网页源码和网页里的 Cookie（按网站整理成 a=1; b=2） */
    data class Result(val url: String, val html: String, val cookies: Map<String, String>)

    suspend fun result(): Result {
        val url = evaluate("location.href") ?: currentUrl
        val html = evaluate("'<!DOCTYPE html>' + document.documentElement.outerHTML").orEmpty()
        val urls = JsonArray().apply { listOf(url, req.url).distinct().forEach { add(it.substringBefore(",{")) } }
        val cookies = runCatching { cmd("Network.getCookies", JsonObject().apply { add("urls", urls) }) }.getOrNull()
            ?.getAsJsonArray("cookies")
        val byHost = LinkedHashMap<String, MutableList<String>>()
        cookies?.forEach { c ->
            val o = c.asJsonObject
            val domain = o.get("domain").asString.trimStart('.')
            val secure = o.get("secure")?.asBoolean == true
            byHost.getOrPut("${if (secure) "https" else "http"}://$domain/") { mutableListOf() }
                .add("${o.get("name").asString}=${o.get("value").asString}")
        }
        return Result(url, html, byHost.mapValues { it.value.joinToString("; ") })
    }

    private suspend fun evaluate(js: String): String? {
        val res = cmd("Runtime.evaluate", JsonObject().apply {
            addProperty("expression", js)
            addProperty("returnByValue", true)
        })
        val v = res.getAsJsonObject("result")?.get("value") ?: return null
        return if (v.isJsonPrimitive) v.asString else v.toString()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        conn.removeListener(sessionId, listener)
        events.close()
        closedSignal.complete(Unit)
        scope.launch {
            runCatching { conn.send("Target.closeTarget", JsonObject().apply { addProperty("targetId", targetId) }) }
            runCatching { conn.send("Target.disposeBrowserContext", JsonObject().apply { addProperty("browserContextId", contextId) }) }
            onDisposed()
        }
    }
}
