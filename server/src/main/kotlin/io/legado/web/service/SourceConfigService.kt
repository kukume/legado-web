package io.legado.web.service

import io.legado.web.security.ScriptUrls
import io.legado.app.utils.NetworkUtils
import io.legado.app.help.http.CookieStore
import java.io.StringReader
import com.google.gson.Strictness
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeUrl
import com.script.rhino.runScriptWithContext
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.entities.rule.FlexChildStyle
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.help.engine.DebugSink
import io.legado.app.help.engine.EngineContext
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreInfoMap
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.Debug
import io.legado.app.model.jsSource.JsSourceEngine
import io.legado.app.model.login.LoginUiV2
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.InfoMap
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isAbsUrl
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.UserPrincipal
import io.legado.app.help.SourceUiJs
import io.legado.web.engine.runEngine
import org.htmlunit.corejs.javascript.Undefined

/**
 * 书源登录界面。与阅读 App 一致，分三种：
 * - v1：loginUi 为 RowUi JSON 数组
 * - v1-js：loginUi 为 <js>/@js: 脚本，执行后返回 RowUi JSON 数组
 * - v2：loginUi 为 {"version":2}，由 loginUrl 中的 loginUi(state)/loginAction(action,state,form) 驱动
 */
data class LoginConfig(
    val sourceId: Long,
    val sourceName: String,
    /** none / v1 / v2 */
    val mode: String,
    /** v1 的界面行（v2 通过 render 接口获取） */
    val loginUi: List<RowUi>,
    val loginInfo: Map<String, String>,
    val hasLoginUrl: Boolean,
    /** loginUrl 是网址时，前端可直接打开 */
    val loginUrl: String?,
    val loginHeader: String?,
    val variable: String,
    val variableComment: String?,
    /** v1-js 界面脚本执行出错时的信息 */
    val renderError: String? = null,
    /** 远程浏览器可用：打开登录网址时可以选择在远程浏览器中登录 */
    val remoteBrowser: Boolean = false,
)

data class ActionResult(
    val ok: Boolean,
    val message: String?,
    val result: String?,
    /** 脚本中 java.toast 的提示 */
    val messages: List<String> = emptyList(),
    /** 脚本请求打开的网页（startBrowser / openUrl / showBrowser） */
    val openUrls: List<String> = emptyList(),
    /** 脚本请求的界面刷新：reLoginView / refreshExplore 等 */
    val events: List<String> = emptyList(),
    /** 脚本通过 upLoginData 更新的表单值 */
    val uiData: Map<String, String?> = emptyMap(),
    /** 脚本通过 copyText 要复制的文本 */
    val clipboard: String? = null,
)

/** 登录界面 v2 的渲染结果 */
data class LoginV2Render(
    val state: String,
    val rows: List<RowUi>,
    val stored: Map<String, String>,
)

/** 登录界面 v2 的动作结果 */
data class LoginV2ActionResult(
    val ok: Boolean,
    val message: String?,
    /** 字段校验错误：key -> 提示 */
    val errors: Map<String, String> = emptyMap(),
    /** 关闭登录界面 */
    val close: Boolean = false,
    /** 状态变化后的新界面（为空表示界面不变） */
    val render: LoginV2Render? = null,
    val effects: ActionResult? = null,
)

/** 发现页的一项（在 ExploreKind 基础上补充计算后的显示名） */
data class ExploreKindView(
    val title: String,
    val url: String?,
    val type: String,
    val action: String?,
    val chars: List<String>,
    val default: String?,
    /** viewName 计算后的显示名 */
    val displayName: String,
    val style: FlexChildStyle?,
)

data class ExploreKindsResult(val kinds: List<ExploreKindView>, val infoMap: Map<String, String>)

class SourceConfigService(private val sourceService: SourceService) {

    /** 远程浏览器是否可用（由 Application 设置） */
    var remoteBrowser: () -> Boolean = { false }

    private fun ActionResult.withEffects(ctx: EngineContext) = copy(
        messages = ctx.messages.toList(),
        openUrls = ctx.openUrls.distinct(),
        events = ctx.events.distinct(),
        uiData = ctx.uiData.toMap(),
        clipboard = ctx.clipboard,
    )

    private fun effectsOf(ctx: EngineContext) = ActionResult(true, null, null).withEffects(ctx)

    private fun Any?.normalized(): String? =
        takeUnless { it is Undefined }?.let { JsSourceEngine.normalizeJsResult(it) }

    // ---------------- 登录界面 ----------------

    /** 书源登录、变量设置用的执行上下文：没有远程浏览器时允许下载网页交给前端显示 */
    private fun loginContext(user: UserPrincipal) = EngineContext(user.id).apply { allowPageFallback = true }

    suspend fun loginConfig(user: UserPrincipal, sourceId: Long): LoginConfig {
        val src = sourceService.get(user, sourceId).source
        return runEngine(user.id, 60_000, ctx = loginContext(user)) {
            val loginUrl = src.loginUrl
            val loginInfo = src.getLoginInfoMap() ?: emptyMap()
            val mode = when {
                src.isLoginUiV2() -> "v2"
                src.hasLogin() -> "v1"
                else -> "none"
            }
            var renderError: String? = null
            val rows = if (mode == "v1") {
                runCatching { loginRowsV1(src, loginInfo) }
                    .onFailure { renderError = it.message ?: it.javaClass.simpleName }
                    .getOrDefault(emptyList())
            } else emptyList()
            LoginConfig(
                sourceId = sourceId,
                sourceName = src.getTag(),
                mode = mode,
                loginUi = rows,
                loginInfo = loginInfo,
                hasLoginUrl = !loginUrl.isNullOrBlank(),
                loginUrl = loginUrl?.takeIf { it.isAbsUrl() },
                loginHeader = src.getLoginHeader(),
                variable = src.getVariable(),
                variableComment = src.variableComment,
                renderError = renderError,
                remoteBrowser = remoteBrowser(),
            )
        }
    }

    /**
     * 登录地址是网址：在远程浏览器中打开登录页（新标签页由前端直接打开），等用户操作完成；
     * 网页里的 Cookie 由打开网页的流程保存。远程浏览器连不上时只提示，不改用其他方式
     */
    suspend fun webLogin(user: UserPrincipal, sourceId: Long): ActionResult {
        val src = sourceService.get(user, sourceId).source
        val url = src.loginUrl?.takeIf { it.isAbsUrl() } ?: throw BadRequestException("登录地址不是网址")
        // 不允许改用本页显示：不弹选择，直接用远程浏览器
        val ctx = EngineContext(user.id)
        val r = runEngine(user.id, 120_000, ctx = ctx) {
            try {
                io.legado.app.help.source.SourceVerificationHelp.getVerificationResult(
                    src, url, "登录", useBrowser = true, refetchAfterSuccess = false,
                )
                ActionResult(true, null, null)
            } catch (e: Throwable) {
                ActionResult(false, e.message ?: e.javaClass.simpleName, null)
            }
        }
        return r.withEffects(ctx)
    }

    /** v1：静态 JSON 或脚本生成的界面行（需在引擎上下文中调用） */
    private suspend fun loginRowsV1(src: BaseSource, loginInfo: Map<String, String>): List<RowUi> {
        val codeStr = src.getLoginUiJs()
        val json = if (codeStr != null) {
            val loginJs = src.getLoginJs() ?: ""
            runScriptWithContext {
                src.evalJS("$loginJs\n$codeStr") {
                    put("java", SourceUiJs(src))
                    put("result", loginInfo)
                    put("book", null)
                    put("chapter", null)
                }.normalized().orEmpty()
            }
        } else {
            src.loginUi
        }
        if (json.isNullOrBlank()) return emptyList()
        return GSON.fromJsonArray<RowUi>(json).getOrElse {
            throw BadRequestException("loginUi 格式错误: ${it.message}")
        }
    }

    /**
     * v1：保存登录信息并执行书源的 login() 函数
     */
    suspend fun login(user: UserPrincipal, sourceId: Long, info: Map<String, String>): ActionResult {
        val src = sourceService.get(user, sourceId).source
        val ctx = loginContext(user)
        val r = runEngine(user.id, 120_000, ctx = ctx) {
            if (info.isEmpty()) {
                src.removeLoginInfo()
                return@runEngine ActionResult(true, "已清除登录信息", null)
            }
            if (!src.putLoginInfo(GSON.toJson(info))) {
                return@runEngine ActionResult(false, "保存登录信息失败", null)
            }
            val loginJs = src.getLoginJs()
            if (loginJs.isNullOrBlank() || src.loginUrl.isAbsUrl()) {
                ActionResult(true, "登录信息已保存", null)
            } else {
                try {
                    val fn = "if (typeof login=='function'){ login.apply(this); } " +
                        "else { throw('Function login not implements!!!') }"
                    runScriptWithContext {
                        src.evalJS("$loginJs\n$fn") {
                            put("java", SourceUiJs(src))
                            put("result", info)
                            put("book", null)
                            put("chapter", null)
                            put("isLongClick", false)
                        }
                    }
                    ActionResult(true, "登录成功", null)
                } catch (e: Throwable) {
                    ActionResult(false, e.message ?: e.javaClass.simpleName, null)
                }
            }
        }
        return r.withEffects(ctx)
    }

    /** v1：保存登录界面中填写的内容（与已保存的合并），不执行 login 函数 */
    suspend fun saveLoginInfo(user: UserPrincipal, sourceId: Long, info: Map<String, String>) {
        val src = sourceService.get(user, sourceId).source
        runEngine(user.id, 30_000) {
            val merged = (src.getLoginInfoMap() ?: emptyMap()) + info
            if (!src.putLoginInfo(GSON.toJson(merged))) throw BadRequestException("保存失败")
        }
    }

    /**
     * v1：执行 loginUi 中按钮的 action
     */
    suspend fun runAction(
        user: UserPrincipal,
        sourceId: Long,
        action: String,
        info: Map<String, String>,
        isLongClick: Boolean = false,
    ): ActionResult {
        val src = sourceService.get(user, sourceId).source
        if (action.isAbsUrl()) return ActionResult(true, null, action, openUrls = listOf(action))
        val ctx = loginContext(user)
        val r = runEngine(user.id, 120_000, ctx = ctx) {
            try {
                val loginJs = src.getLoginJs() ?: ""
                val stored = src.getLoginInfoMap() ?: emptyMap()
                val result = runScriptWithContext {
                    src.evalJS("$loginJs\n$action") {
                        put("java", SourceUiJs(src))
                        put("result", stored + info)
                        put("book", null)
                        put("chapter", null)
                        put("isLongClick", isLongClick)
                    }
                }
                ActionResult(true, null, result.normalized())
            } catch (e: Throwable) {
                ActionResult(false, e.message ?: e.javaClass.simpleName, null)
            }
        }
        return r.withEffects(ctx)
    }

    /**
     * 正文图片点击（与 App 的 ReadBookActivity.clickImg / oldClickImg 一致）：
     * - 图片参数中的 click：以登录界面相同的 java 对象执行，可用 book、chapter，result 为完整图片地址
     * - 旧写法 js：在正文解析上下文中执行，result 为去掉参数的图片地址
     * 段评、本章说等通过 java.showBrowser 打开的网页会推送到页面以底部弹窗显示。
     */
    suspend fun clickImage(user: UserPrincipal, source: BookSource, book: Book, chapter: BookChapter, src: String): ActionResult {
        sourceService.requireTrustedUrls(user, src)
        val matcher = AnalyzeUrl.paramPattern.matcher(src)
        if (!matcher.find()) return ActionResult(false, "该图片没有点击操作", null)
        val urlNoOption = src.substring(0, matcher.start())
        val option = runCatching {
            val reader = JsonReader(StringReader(src.substring(matcher.end()))).apply { strictness = Strictness.LENIENT }
            JsonParser.parseReader(reader).asJsonObject
        }.getOrNull() ?: return ActionResult(false, "图片参数格式错误", null)
        fun opt(key: String) = option.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        val click = opt("click")
        val js = opt("js")
        if (click == null && js == null) return ActionResult(false, "该图片没有点击操作", null)
        val ctx = EngineContext(user.id)
        val r = runEngine(user.id, 120_000, ctx = ctx) {
            try {
                val result = if (click != null) {
                    runScriptWithContext {
                        source.evalJS(click) {
                            put("java", SourceUiJs(source))
                            put("book", book)
                            put("chapter", chapter)
                            put("result", src)
                        }
                    }
                } else {
                    AnalyzeRule(book, source).setBaseUrl(chapter.url).setChapter(chapter).evalJS(js!!, urlNoOption)
                }
                ActionResult(true, null, result.normalized())
            } catch (e: Throwable) {
                ActionResult(false, e.message ?: e.javaClass.simpleName, null)
            }
        }
        return r.withEffects(ctx)
    }

    data class CookieInfo(val url: String, val domain: String, val cookie: String)

    /**
     * 手动设置 Cookie：网页版无法读取用户浏览器里的 Cookie，需要“在网页中登录”的书源由用户粘贴 Cookie。
     * url 为空时使用 loginUrl（网址）或书源地址；保存后与书源请求共用（cookie.getKey / 请求自动携带）。
     */
    suspend fun setCookie(user: UserPrincipal, sourceId: Long, url: String?, cookie: String): CookieInfo {
        val src = sourceService.get(user, sourceId).source
        val target = cookieTarget(src, url)
        val value = normalizeCookieInput(cookie) ?: throw BadRequestException("Cookie 格式不正确，应为 name=value; name2=value2")
        return EngineContext.with(EngineContext(user.id)) {
            CookieStore.replaceCookie(target, value)
            CookieInfo(target, NetworkUtils.getSubDomain(target), CookieStore.getCookie(target))
        }
    }

    suspend fun getCookie(user: UserPrincipal, sourceId: Long, url: String?): CookieInfo {
        val src = sourceService.get(user, sourceId).source
        val target = cookieTarget(src, url)
        return EngineContext.with(EngineContext(user.id)) {
            CookieInfo(target, NetworkUtils.getSubDomain(target), CookieStore.getCookie(target))
        }
    }

    private fun cookieTarget(src: BookSource, url: String?): String {
        val u = url?.trim()?.takeIf { it.isNotEmpty() }
            ?: src.loginUrl?.trim()?.takeIf { it.isAbsUrl() }
            ?: src.bookSourceUrl
        val clean = u.substringBefore(',').substringBefore('#').trim()
        if (!clean.startsWith("http", true)) throw BadRequestException("无法确定 Cookie 所属的网站")
        return clean
    }

    /** v2：渲染界面 */
    suspend fun loginV2Render(user: UserPrincipal, sourceId: Long, state: String?): LoginV2Render {
        val src = sourceService.get(user, sourceId).source
        return runEngine(user.id, 60_000, ctx = loginContext(user)) { renderV2(src, state?.ifBlank { null } ?: "{}") }
    }

    private suspend fun renderV2(src: BaseSource, state: String): LoginV2Render {
        val rows = runScriptWithContext { LoginUiV2.parseRender(src.evalLoginUiV2(state)) }
            ?: throw BadRequestException("登录界面(v2)渲染结果格式错误")
        return LoginV2Render(state, rows, src.getLoginInfoMap() ?: emptyMap())
    }

    /** v2：执行动作 */
    suspend fun loginV2Action(
        user: UserPrincipal,
        sourceId: Long,
        action: String,
        state: String?,
        form: Map<String, String>,
    ): LoginV2ActionResult {
        val src = sourceService.get(user, sourceId).source
        val ctx = loginContext(user)
        val stateJson = state?.ifBlank { null } ?: "{}"
        return runEngine(user.id, 120_000, ctx = ctx) {
            val raw = try {
                runScriptWithContext { src.evalLoginActionV2(action, stateJson, GSON.toJson(form)) }
            } catch (e: Throwable) {
                return@runEngine LoginV2ActionResult(false, "动作 $action 出错: ${e.message}", effects = effectsOf(ctx))
            }
            val command = LoginUiV2.parseActionResult(raw)
            if (command.malformed) {
                return@runEngine LoginV2ActionResult(false, "动作 $action 返回了无效命令", effects = effectsOf(ctx))
            }
            command.loginJson?.let {
                if (!src.putLoginInfo(it)) {
                    return@runEngine LoginV2ActionResult(false, "保存登录信息失败", effects = effectsOf(ctx))
                }
            }
            val errors = command.error.orEmpty()
            if (command.close) {
                return@runEngine LoginV2ActionResult(true, null, errors, close = true, effects = effectsOf(ctx))
            }
            val render = command.stateJson?.let {
                runCatching { renderV2(src, it) }.getOrNull()
            }
            LoginV2ActionResult(true, null, errors, render = render, effects = effectsOf(ctx))
        }
    }

    suspend fun logout(user: UserPrincipal, sourceId: Long) {
        val src = sourceService.get(user, sourceId).source
        runEngine(user.id, ctx = loginContext(user)) {
            src.removeLoginInfo()
            src.removeLoginHeader()
        }
    }

    suspend fun setVariable(user: UserPrincipal, sourceId: Long, variable: String?) {
        val src = sourceService.get(user, sourceId).source
        runEngine(user.id, ctx = loginContext(user)) { src.setVariable(variable?.ifEmpty { null }) }
    }

    // ---------------- 发现页 ----------------

    suspend fun exploreKinds(user: UserPrincipal, sourceId: Long, refresh: Boolean): ExploreKindsResult {
        val src = sourceService.get(user, sourceId).source
        return runEngine(user.id, 60_000) {
            if (refresh) src.clearExploreKindsCache()
            val infoMap = src.exploreInfoMap()
            val kinds = src.exploreKinds(infoMap)
            val views = kinds.map { k -> k.toView(src, infoMap) }
            // 与 App 一致：toggle/select 没有值时写入默认值
            if (infoMap.needSave) infoMap.saveNow()
            ScriptUrls.remember(user.id, views.map { it.url })
            ExploreKindsResult(views, infoMap.get().toMap())
        }
    }

    private suspend fun ExploreKind.toView(src: BookSource, infoMap: InfoMap): ExploreKindView {
        val chars = chars?.filterNotNull() ?: emptyList()
        if (type == ExploreKind.Type.toggle || type == ExploreKind.Type.select) {
            if (infoMap[title].isNullOrEmpty()) {
                (default ?: chars.firstOrNull())?.let {
                    infoMap[title] = it
                    infoMap.save()
                }
            }
        }
        val vn = viewName
        val quoted = vn != null && vn.length in 3..19 && vn.startsWith("'") && vn.endsWith("'")
        val displayName = when {
            vn == null -> title
            quoted -> vn.substring(1, vn.length - 1)
            else -> runCatching {
                runScriptWithContext {
                    src.evalJS(vn) { put("infoMap", infoMap) }.normalized()
                }
            }.getOrNull()?.ifEmpty { null } ?: title
        }
        return ExploreKindView(title, url, type, action, chars, default, displayName, style)
    }

    /**
     * 发现页控件：保存取值并执行 action（与 App 相同，绑定 java=SourceUiJs、infoMap）
     */
    suspend fun exploreAction(
        user: UserPrincipal,
        sourceId: Long,
        title: String,
        value: String?,
        action: String?,
    ): ActionResult {
        val src = sourceService.get(user, sourceId).source
        val ctx = EngineContext(user.id)
        val r = runEngine(user.id, 120_000, ctx = ctx) {
            val infoMap = src.exploreInfoMap()
            if (value != null) infoMap[title] = value
            infoMap.saveNow()
            if (action.isNullOrBlank()) return@runEngine ActionResult(true, null, null)
            try {
                val result = runScriptWithContext {
                    src.evalJS(action) {
                        put("java", SourceUiJs(src))
                        put("infoMap", infoMap)
                    }
                }
                // action 中可能修改了 infoMap
                infoMap.saveNow()
                ActionResult(true, null, result.normalized())
            } catch (e: Throwable) {
                ActionResult(false, e.message ?: e.javaClass.simpleName, null)
            }
        }
        return r.withEffects(ctx)
    }

    /**
     * 书源调试，与阅读 App 的调试关键字规则一致：
     * - 普通关键字：搜索
     * - 以 http 开头：详情页
     * - 标题::url：发现
     * - ++url：目录
     * - --url：正文
     */
    suspend fun debug(user: UserPrincipal, source: BookSource, key: String, sink: DebugSink) {
        runEngine(user.id, 300_000, sink) {
            Debug.log(source.bookSourceUrl, "︾开始解析")
            try {
                when {
                    key.isAbsUrl() -> {
                        val book = Book(bookUrl = key, origin = source.bookSourceUrl, originName = source.bookSourceName)
                        infoDebug(source, book)
                    }
                    key.contains("::") -> {
                        val url = key.substringAfter("::")
                        Debug.log(source.bookSourceUrl, "︾开始解析发现页")
                        val books = WebBook.exploreBookAwait(source, url, 1)
                        Debug.log(source.bookSourceUrl, "︽发现页解析完成")
                        books.firstOrNull()?.let { infoDebug(source, it.toBook()) }
                    }
                    key.startsWith("++") -> {
                        val book = Book(bookUrl = key.substring(2), tocUrl = key.substring(2), origin = source.bookSourceUrl)
                        tocDebug(source, book)
                    }
                    key.startsWith("--") -> {
                        val book = Book(bookUrl = key.substring(2), origin = source.bookSourceUrl)
                        val chapter = BookChapter(url = key.substring(2), title = "调试", baseUrl = key.substring(2))
                        contentDebug(source, book, chapter, null)
                    }
                    else -> {
                        Debug.log(source.bookSourceUrl, "︾开始解析搜索页")
                        val books = WebBook.searchBookAwait(source, key, 1)
                        Debug.log(source.bookSourceUrl, "︽搜索页解析完成")
                        if (books.isEmpty()) {
                            Debug.log(source.bookSourceUrl, "︽未获取到书籍", state = -1)
                        } else {
                            infoDebug(source, books.first().toBook())
                        }
                    }
                }
                Debug.log(source.bookSourceUrl, "︽解析完成", state = 1000)
            } catch (e: Throwable) {
                Debug.log(source.bookSourceUrl, "✗ ${e.message ?: e.javaClass.name}", state = -1)
                if (e !is io.legado.app.exception.NoStackTraceException) {
                    Debug.log(source.bookSourceUrl, e.stackTraceToString().take(3000), state = -1)
                }
            } finally {
                Debug.reset(sink)
            }
        }
    }

    private suspend fun infoDebug(source: BookSource, book: Book) {
        Debug.log(source.bookSourceUrl, "︾开始解析详情页")
        WebBook.getBookInfoAwait(source, book)
        Debug.log(source.bookSourceUrl, "︽详情页解析完成")
        tocDebug(source, book)
    }

    private suspend fun tocDebug(source: BookSource, book: Book) {
        Debug.log(source.bookSourceUrl, "︾开始解析目录页")
        val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
        Debug.log(source.bookSourceUrl, "︽目录页解析完成")
        val toc = chapters.filter { !(it.isVolume && it.url.startsWith(it.title)) }
        if (toc.isEmpty()) {
            Debug.log(source.bookSourceUrl, "≡没有正文章节")
            return
        }
        contentDebug(source, book, toc.first(), toc.getOrNull(1)?.url)
    }

    private suspend fun contentDebug(source: BookSource, book: Book, chapter: BookChapter, next: String?) {
        Debug.log(source.bookSourceUrl, "︾开始解析正文页")
        WebBook.getContentAwait(source, book, chapter, next, needSave = false)
        Debug.log(source.bookSourceUrl, "︽正文页解析完成")
    }
}
