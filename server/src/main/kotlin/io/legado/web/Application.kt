package io.legado.web

import com.google.gson.JsonSyntaxException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException as KtorBadRequest
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.excludeContentType
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.routing.route
import io.legado.app.help.engine.Engine
import io.legado.app.exception.NoStackTraceException
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.ForbiddenException
import io.legado.web.auth.JwtService
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.UnauthorizedException
import io.legado.web.auth.configureSecurity
import io.legado.web.config.AppSettings
import io.legado.web.db.DatabaseFactory
import io.legado.web.db.DbEngineStore
import io.legado.web.routes.mediaRoutes
import io.legado.web.routes.bookmarkRoutes
import io.legado.web.routes.readingStatsRoutes
import io.legado.web.routes.ruleRoutes
import io.legado.web.routes.authRoutes
import io.legado.web.routes.shelfRoutes
import io.legado.web.routes.openApiRoutes
import io.legado.web.routes.backupRoutes
import io.legado.web.service.BackupService
import io.legado.web.service.BookmarkService
import io.legado.web.service.ReadingStatsService
import io.legado.web.service.WebDavService
import io.legado.web.service.BrowserBridge
import io.legado.web.routes.browserRoutes
import io.legado.app.help.source.SourceVerificationHelp
import io.ktor.server.sse.SSE
import io.legado.web.service.ApiKeyService
import io.legado.web.routes.sourceRoutes
import io.legado.web.service.ProxyService
import io.legado.web.service.ReplaceRuleService
import io.legado.web.service.SearchService
import io.legado.web.service.SettingService
import io.legado.web.service.ShelfService
import io.legado.web.service.SourceConfigService
import io.legado.web.service.SourceService
import io.legado.web.service.SubscriptionService
import io.legado.web.service.UserService
import io.legado.web.webview.CdpWebViewProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.io.File

data class ErrorResponse(val error: String)

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

@Suppress("unused")
fun Application.module() {
    val log = LoggerFactory.getLogger("legado.web")
    val appSettings = AppSettings.from(environment.config)

    // ---------- 数据库 & 引擎 ----------
    DatabaseFactory.init(appSettings.db)
    val engineStore = DbEngineStore()
    Engine.store = engineStore
    Engine.cacheDir = File(appSettings.dataDir, "cache").apply { mkdirs() }
    Engine.secret = appSettings.engineSecret
    Engine.threadCount = appSettings.threadCount
    appSettings.userAgent?.let { Engine.userAgent = it }
    // ---------- 服务 ----------
    val settingService = SettingService()
    // 书源请求的内网防护：始终开启，只能在系统设置里放行指定的内网地址（设置有缓存，每次连接读取的开销很小）
    io.legado.web.security.NetworkGuard.configure { settingService.get(SettingService.PRIVATE_NETWORK_ALLOWLIST) }
    val captchaService = io.legado.web.service.CaptchaService(settingService, appSettings.captchaDisabled)
    val userService = UserService(settingService)
    val jwtService = JwtService(appSettings)
    val sourceService = SourceService(settingService, userService)
    val replaceService = ReplaceRuleService()
    val bookmarkService = BookmarkService()
    val readingStatsService = ReadingStatsService()
    val shelfService = ShelfService(sourceService, replaceService, appSettings)
    val searchService = SearchService(sourceService, appSettings)
    val configService = SourceConfigService(sourceService)
    val subscriptionService = SubscriptionService(sourceService)
    // 本地书籍（EPUB / TXT）
    // 字典规则、TXT 目录规则
    val dictRuleService = io.legado.web.service.DictRuleService(sourceService)
    val txtTocRuleService = io.legado.web.service.TxtTocRuleService(sourceService)
    val localBookService = io.legado.web.service.LocalBookService(appSettings, settingService).also {
        it.shelfService = shelfService
        it.tocRules = txtTocRuleService
        shelfService.localBooks = it
    }
    val backupService = BackupService(sourceService, shelfService, replaceService, dictRuleService, txtTocRuleService)
    val reviewService = io.legado.web.service.ReviewService(shelfService)
    // 音频书源播放、在线朗读引擎
    val audioService = io.legado.web.service.AudioService(shelfService)
    val ttsService = io.legado.web.service.TtsService(sourceService)
    val webDavService = WebDavService(backupService, userService)
    // 构建镜像时写入的构建时间和镜像名（见 Dockerfile）；本地开发运行时没有，不检查更新
    val updateService = io.legado.web.service.UpdateService(
        buildTime = System.getenv("LEGADO_BUILD_TIME")?.trim()?.toLongOrNull(),
        image = System.getenv("LEGADO_IMAGE")?.trim()?.ifEmpty { null } ?: "ghcr.io/kukume/legado-web",
        enabled = { settingService.getBool(SettingService.UPDATE_CHECK_ENABLED) },
    )
    // 书源脚本的内置浏览器 / 验证码：推送给用户页面并等待操作结果
    val browserBridge = BrowserBridge()
    SourceVerificationHelp.handler = browserBridge
    // 远程浏览器：书源要打开外部网页让用户操作（人机验证、登录）时，在服务器的浏览器里打开并把画面推给用户
    // 浏览器：webView 书源和远程浏览器连接浏览器的调试地址（另一个容器、另一台机器或本机启动的浏览器）。
    // 地址在后台“系统设置”中设置；没有地址时不使用浏览器
    val browserCdp: () -> String? = { settingService.get(SettingService.BROWSER_CDP).trim().ifEmpty { null } }
    val browserContexts = io.legado.web.webview.BrowserContexts(File(appSettings.dataDir, "browser-contexts.txt"))
    val webViewProvider = CdpWebViewProvider(browserCdp, browserContexts)
    val remoteBrowser = io.legado.web.webview.RemoteBrowserManager(browserCdp, browserContexts)
    fun applyBrowser() {
        val cdp = browserCdp()
        Engine.webViewProvider = if (cdp != null) webViewProvider else null
        browserBridge.remoteBrowser = if (cdp != null) remoteBrowser else null
        if (cdp != null) {
            log.info("使用浏览器: {}", cdp)
            remoteBrowser.cleanupLeftovers()
        }
        else log.warn("没有设置浏览器调试地址（系统设置），webView:true 的书源会退化为普通请求，远程浏览器不可用")
    }
    applyBrowser()
    settingService.onChange { keys -> if (SettingService.BROWSER_CDP in keys) applyBrowser() }
    browserBridge.remoteEnabled = { settingService.getBool(SettingService.REMOTE_BROWSER_ENABLED) }
    val proxyService = ProxyService(sourceService, shelfService)
    val apiKeyService = ApiKeyService(userService)

    // ---------- 插件 ----------
    install(ContentNegotiation) {
        gson {
            disableHtmlEscaping()
            serializeNulls()
        }
    }
    install(CallLogging) {
        level = Level.INFO
        filter {
            val p = it.request.path()
            (p.startsWith("/api") && !p.startsWith("/api/proxy")) || p.startsWith("/openapi")
        }
    }
    install(Compression) {
        gzip {
            excludeContentType(ContentType.parse("application/x-ndjson"))
            excludeContentType(ContentType.Text.EventStream)
            excludeContentType(ContentType.Image.Any)
            excludeContentType(ContentType.Audio.Any)
            excludeContentType(ContentType.Video.Any)
            excludeContentType(ContentType.Application.OctetStream)
        }
    }
    if (appSettings.corsHosts.isNotEmpty()) {
        install(CORS) {
            appSettings.corsHosts.forEach { h ->
                if (h == "*") anyHost() else allowHost(h.removePrefix("http://").removePrefix("https://"), listOf("http", "https"))
            }
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Patch)
            allowMethod(HttpMethod.Delete)
        }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val (status, msg) = when (cause) {
                is UnauthorizedException -> HttpStatusCode.Unauthorized to cause.message
                is ForbiddenException -> HttpStatusCode.Forbidden to cause.message
                is NotFoundException -> HttpStatusCode.NotFound to cause.message
                is BadRequestException -> HttpStatusCode.BadRequest to cause.message
                is KtorBadRequest -> HttpStatusCode.BadRequest to (cause.cause?.message ?: cause.message)
                is JsonSyntaxException -> HttpStatusCode.BadRequest to "JSON 格式错误"
                is TimeoutCancellationException -> HttpStatusCode.GatewayTimeout to "书源响应超时"
                is NoStackTraceException -> HttpStatusCode.BadGateway to cause.message
                is java.io.IOException -> HttpStatusCode.BadGateway to "网络错误: ${cause.message}"
                else -> {
                    log.warn("请求处理失败 {}: {}", call.request.path(), cause.toString(), cause)
                    HttpStatusCode.InternalServerError to (cause.message ?: cause.javaClass.simpleName)
                }
            }
            // 按请求的界面语言翻译提示（见 i18n/Messages）
            call.respond(status, ErrorResponse(io.legado.web.i18n.Messages.translate(call, msg ?: "未知错误")))
        }
        status(HttpStatusCode.Unauthorized) { call, status ->
            if (call.request.path().startsWith("/api")) {
                call.respond(status, ErrorResponse(io.legado.web.i18n.Messages.translate(call, "未登录或登录已过期")))
            }
        }
    }
    install(SSE)
    // 前端静态文件的缓存：/assets/ 下的文件名带内容哈希，长期缓存；页面、Service Worker、清单每次都向服务器确认，
    // 发布新版本后浏览器能立刻发现（否则浏览器可能按修改时间自行缓存，很久才提示更新）
    intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (!path.startsWith("/api") && !path.startsWith("/openapi")) {
            call.response.headers.append(
                HttpHeaders.CacheControl,
                if (path.startsWith("/assets/")) "public, max-age=31536000, immutable" else "no-cache",
            )
        }
    }
    // 记录发起请求的标签页，书源脚本的弹窗只推送给它
    intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
        val tab = call.request.headers["X-Client-Id"]?.takeIf { it.length in 8..64 }
        if (tab != null) kotlinx.coroutines.withContext(io.legado.web.engine.ClientTab(tab)) { proceed() } else proceed()
    }
    configureSecurity(jwtService, userService)

    routing {
        authRoutes(userService, jwtService, settingService, captchaService, updateService)
        sourceRoutes(sourceService, configService, searchService, subscriptionService, io.legado.web.service.SourceCheckService(sourceService, appSettings))
        shelfRoutes(shelfService, replaceService, proxyService, configService, reviewService, localBookService)
        openApiRoutes(apiKeyService, shelfService)
        backupRoutes(backupService, webDavService)
        mediaRoutes(audioService, ttsService, io.legado.web.service.FontService(appSettings.dataDir))
        bookmarkRoutes(bookmarkService)
        readingStatsRoutes(readingStatsService)
        ruleRoutes(dictRuleService, txtTocRuleService)
        browserRoutes(browserBridge)
        route("/openapi/{...}") {
            handle { call.respond(HttpStatusCode.NotFound, ErrorResponse(io.legado.web.i18n.Messages.translate(call, "接口不存在"))) }
        }
        route("/api/{...}") {
            handle { call.respond(HttpStatusCode.NotFound, ErrorResponse(io.legado.web.i18n.Messages.translate(call, "接口不存在"))) }
        }

        // 前端：优先使用外部目录（legado.webDir），否则使用打包进 jar 的静态资源
        val webDir = environment.config.propertyOrNull("legado.webDir")?.getString()?.takeIf { File(it).isDirectory }
        singlePageApplication {
            if (webDir != null) {
                filesPath = webDir
            } else {
                useResources = true
                filesPath = "static"
            }
            defaultPage = "index.html"
        }
    }

    // ---------- 定时任务 ----------
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    scope.launch {
        delay(60_000)
        while (true) {
            runCatching { subscriptionService.refreshAll() }.onFailure { log.warn("订阅刷新失败", it) }
            runCatching { shelfService.cleanupTemporary() }
            runCatching { engineStore.cleanExpired() }
            runCatching { webDavService.autoBackupAll() }.onFailure { log.warn("自动 WebDAV 备份失败", it) }
            delay(3600_000)
        }
    }
    monitor.subscribe(io.ktor.server.application.ApplicationStopped) {
        webViewProvider.close()
        remoteBrowser.close()
    }
    log.info("阅读 Web 服务已启动，数据目录: {}", appSettings.dataDir)
}
