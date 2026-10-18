package io.legado.app.help

import android.webkit.JavascriptInterface
import cn.hutool.core.codec.Base64
import cn.hutool.core.util.HexUtil
import com.script.rhino.rhinoContext
import com.script.rhino.rhinoContextOrNull
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.dateFormat
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.BackstageWebView
import io.legado.app.help.http.CookieManager.cookieJarHeader
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.SSLHelper
import io.legado.app.help.http.StrResponse
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.help.source.shouldSuppressSourceNavigation
import io.legado.app.help.source.VerificationResult
import io.legado.app.help.source.getSourceType
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.engine.Engine
import io.legado.app.help.engine.EngineContext
import io.legado.app.exception.UnsupportedOnServerException
import io.legado.app.help.book.BookHelp
import io.legado.app.model.BatchContentContext
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.QueryTTF
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.EncoderUtils
import io.legado.app.utils.EncodingDetect
import io.legado.app.utils.GSON
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.JsURL
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isMainThread
import io.legado.app.utils.mapAsync
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toStringArray
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.use
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.htmlunit.corejs.javascript.Function
import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.ScriptableObject
import org.htmlunit.corejs.javascript.Undefined
import org.htmlunit.corejs.javascript.Wrapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

internal fun parseJsRequestHeaders(headers: Any?): Map<String, String> {
    val rawHeaders = when (headers) {
        null -> return emptyMap()
        is String -> GSON.fromJsonObject<Map<String, Any?>>(headers).getOrElse {
            throw IllegalArgumentException("Invalid request header JSON", it)
        }
        is Map<*, *> -> headers
        else -> throw IllegalArgumentException(
            "Request headers must be a map or JSON string, got ${headers.javaClass.name}"
        )
    }
    return buildMap(rawHeaders.size) {
        rawHeaders.forEach { (name, value) ->
            require(name != null && value != null) { "Request header names and values cannot be null" }
            put(name.toString(), value.toString())
        }
    }
}

/**
 * js扩展类, 在js中通过java变量调用
 * 添加方法，请更新文档/legado/app/src/main/assets/help/JsHelp.md
 * 所有对于文件的读写删操作都是相对路径,只能操作阅读缓存内的文件
 * /android/data/{package}/cache/...
 */
@Suppress("unused")
interface JsExtensions : JsEncodeUtils {

    fun getSource(): BaseSource?
    fun getTag(): String?
    fun getSourceNavigationContext(): CoroutineContext = EmptyCoroutineContext

    /**
     * 当前批量正文下载上下文,非批量流程返回 null。
     * 由 AnalyzeRule / JsSourceEngine 在执行 contentBatch 规则时提供。
     */
    fun getBatchContext(): BatchContentContext? = null

    /**
     * 批量下载时回存单章正文。
     *
     * 书源在 contentBatch 规则(或 JS 源的 getContentBatch 函数)里每处理完一章即可调用,
     * 内容会立刻写入缓存目录,不必等整批结束。只能在批量流程内调用。
     *
     * @param chapter 本批章节数组里的章节对象,也可传章节 url;
     *   目录里存在多章共用同一 url 时必须传章节对象,否则无法区分是哪一章
     * @param content 章节正文,会套用书源的正文替换规则后保存
     * @return 是否成功保存
     */
    @JavascriptInterface
    fun cacheContent(chapter: Any?, content: String): Boolean {
        rhinoContextOrNull?.ensureActive()
        val batchContext = getBatchContext()
            ?: throw NoStackTraceException("java.cacheContent 只能在批量正文规则中调用")
        val identifier = if (chapter is Wrapper) chapter.unwrap() else chapter
        return batchContext.saveContent(identifier, content)
    }

    // 网页版：刷新请求记录到上下文，由 Web 层决定是否处理
    fun refreshBookInfo() {
        EngineContext.current?.events?.add("refreshBookInfo")
    }

    fun refreshBookToc() {
        EngineContext.current?.events?.add("refreshBookToc")
    }

    fun refreshContent() {
        EngineContext.current?.events?.add("refreshContent")
    }

    private val context: CoroutineContext
        get() = (rhinoContextOrNull?.coroutineContext ?: EmptyCoroutineContext) +
            getSourceNavigationContext()

    /**
     * 访问网络,返回String
     */
    fun ajax(url: Any): String? {
        return ajax(url, null)
    }

    fun ajax(url: Any, callTimeout: Long?): String? {
        val urlStr = if (url is List<*>) {
            url.firstOrNull().toString()
        } else {
            url.toString()
        }
        val analyzeUrl = AnalyzeUrl(urlStr, source = getSource(), callTimeout = callTimeout, coroutineContext = context)
        return kotlin.runCatching {
            analyzeUrl.getStrResponse().body
        }.onFailure {
            rhinoContextOrNull?.ensureActive()
            AppLog.put("ajax(${urlStr}) error\n${it.localizedMessage}", it)
        }.getOrElse {
            it.stackTraceStr
        }
    }

    /**
     * 并发访问网络
     */
    fun ajaxAll(urlList: Array<String>): Array<StrResponse> {
        return ajaxAll(urlList, false)
    }
    fun ajaxAll(urlList: Array<String>, skipRateLimit: Boolean): Array<StrResponse> {
        return runBlocking(context) {
            urlList.asFlow().mapAsync(AppConfig.threadCount) { url ->
                val analyzeUrl = AnalyzeUrl(
                    url,
                    source = getSource(),
                    coroutineContext = coroutineContext
                )
                analyzeUrl.getStrResponseAwait(skipRateLimit = skipRateLimit)
            }.flowOn(IO).toList().toTypedArray()
        }
    }

    /**
     * 并发测试网络
     */
    fun ajaxTestAll(urlList: Array<String>, timeout: Int): Array<StrResponse> {
        return ajaxTestAll(urlList, timeout, false)
    }
    fun ajaxTestAll(urlList: Array<String>, timeout: Int, skipRateLimit: Boolean): Array<StrResponse> {
        return runBlocking(context) {
            urlList.asFlow().mapAsync(AppConfig.threadCount) { url ->
                val analyzeUrl = AnalyzeUrl(
                    url,
                    source = getSource(),
                    coroutineContext = coroutineContext,
                    callTimeout = timeout.toLong()
                )
                analyzeUrl.getStrResponseAwait(isTest = true, skipRateLimit = skipRateLimit)
            }.flowOn(IO).toList().toTypedArray()
        }
    }


    /**
     * 访问网络,返回Response<String>
     */
    fun connect(urlStr: String): StrResponse {
        val analyzeUrl = AnalyzeUrl(
            urlStr,
            source = getSource(),
            coroutineContext = context
        )
        return kotlin.runCatching {
            analyzeUrl.getStrResponse()
        }.onFailure {
            rhinoContextOrNull?.ensureActive()
            AppLog.put("connect(${urlStr}) error\n${it.localizedMessage}", it)
        }.getOrElse {
            StrResponse(analyzeUrl.url, it.stackTraceStr)
        }
    }

    fun connect(urlStr: String, header: String?): StrResponse {
        return connect(urlStr, header, null)
    }

    fun connect(urlStr: String, header: String?, callTimeout: Long?): StrResponse {
        val headerMap = GSON.fromJsonObject<Map<String, String>>(header).getOrNull()
        val analyzeUrl = AnalyzeUrl(
            urlStr,
            headerMapF = headerMap,
            source = getSource(),
            callTimeout = callTimeout,
            coroutineContext = context
        )
        return kotlin.runCatching {
            analyzeUrl.getStrResponse()
        }.onFailure {
            rhinoContextOrNull?.ensureActive()
            AppLog.put("connect($urlStr,$header) error\n${it.localizedMessage}", it)
        }.getOrElse {
            StrResponse(analyzeUrl.url, it.stackTraceStr)
        }
    }

    fun webView(html: String?, url: String?, js: String?): String? {
        return webView(html, url, js, false)
    }

    /**
     * 使用webView访问网络
     * @param html 直接用webView载入的html, 如果html为空直接访问url
     * @param url html内如果有相对路径的资源不传入url访问不了
     * @param js 用来取返回值的js语句, 没有就返回整个源代码
     * @param cacheFirst 优先使用缓存,为true能提高访问速度
     * @return 返回js获取的内容
     */
    fun webView(html: String?, url: String?, js: String?, cacheFirst: Boolean): String? {
        if (isMainThread) {
            error("webView must be called on a background thread")
        }
        return runBlocking(context) {
            BackstageWebView(
                url = url,
                html = html,
                javaScript = js,
                headerMap = getSource()?.getHeaderMap(true),
                tag = getSource()?.getKey(),
                cacheFirst = cacheFirst
            ).getStrResponse().body
        }
    }

    fun webViewGetSource(html: String?, url: String?, js: String?, sourceRegex: String): String? {
        return webViewGetSource(html, url, js, sourceRegex, false, 0)
    }
    fun webViewGetSource(html: String?, url: String?, js: String?, sourceRegex: String, cacheFirst: Boolean): String? {
        return webViewGetSource(html, url, js, sourceRegex, cacheFirst, 0)
    }

    /**
     * 使用webView获取资源url
     */
    fun webViewGetSource(
        html: String?,
        url: String?,
        js: String?,
        sourceRegex: String,
        cacheFirst: Boolean,
        delayTime:Long
    ): String? {
        if (isMainThread) {
            error("webViewGetSource must be called on a background thread")
        }
        return runBlocking(context) {
            BackstageWebView(
                url = url,
                html = html,
                javaScript = js,
                headerMap = getSource()?.getHeaderMap(true),
                tag = getSource()?.getKey(),
                sourceRegex = sourceRegex,
                cacheFirst = cacheFirst,
                delayTime = delayTime
            ).getStrResponse().body
        }
    }

    fun webViewGetOverrideUrl(html: String?, url: String?, js: String?, overrideUrlRegex: String): String? {
        return webViewGetOverrideUrl(html, url, js, overrideUrlRegex, false, 0)
    }
    fun webViewGetOverrideUrl(html: String?, url: String?, js: String?, overrideUrlRegex: String, cacheFirst: Boolean): String? {
        return webViewGetOverrideUrl(html, url, js, overrideUrlRegex, cacheFirst, 0)
    }

    /**
     * 使用webView获取跳转url
     */
    fun webViewGetOverrideUrl(
        html: String?,
        url: String?,
        js: String?,
        overrideUrlRegex: String,
        cacheFirst: Boolean,
        delayTime:Long
    ): String? {
        if (isMainThread) {
            error("webViewGetOverrideUrl must be called on a background thread")
        }
        return runBlocking(context) {
            BackstageWebView(
                url = url,
                html = html,
                javaScript = js,
                headerMap = getSource()?.getHeaderMap(true),
                tag = getSource()?.getKey(),
                overrideUrlRegex = overrideUrlRegex,
                cacheFirst = cacheFirst,
                delayTime = delayTime
            ).getStrResponse().body
        }
    }

    @JavascriptInterface
    fun openVideoPlayer(url: String, title: String) {
        openVideoPlayer(url, title, false)
    }

    /**
     * 打开内置视频播放器
     * @param url 视频播放链接
     * @param title 视频的标题
     * @param isFloat 是否悬浮窗打开
     */
    @JavascriptInterface
    fun openVideoPlayer(url: String, title: String, isFloat: Boolean) {
        if (!canOpenSourceUi()) return
        SourceHelp.openVideoPlayer(getSource(), url, title, isFloat)
    }

    /**
     * 使用内置浏览器打开链接，手动验证网站防爬
     * @param url 要打开的链接
     * @param title 浏览器页面的标题
     */
    fun startBrowser(url: String, title: String) {
        return startBrowser(url, title, null)
    }

    fun startBrowser(url: String, title: String, html: String?) {
        rhinoContext.ensureActive()
        if (!canOpenSourceUi()) return
        SourceVerificationHelp.startBrowser(getSource(), url, title, html=html)
    }

    /**
     * 使用内置浏览器打开链接，并等待网页结果
     */
    fun startBrowserAwait(url: String, title: String): StrResponse {
        return startBrowserAwait(url, title, true, null)
    }

    fun startBrowserAwait(url: String, title: String, refetchAfterSuccess: Boolean): StrResponse {
        return startBrowserAwait(url, title, refetchAfterSuccess, null)
    }

    fun startBrowserAwait(url: String, title: String, refetchAfterSuccess: Boolean, html: String?): StrResponse {
        rhinoContext.ensureActive()
        if (!canOpenSourceUi()) throw NoStackTraceException("已阻止当前操作中的书源网页跳转")
        return when (val result = SourceVerificationHelp.getVerificationResult(
            getSource(), url, title, true, refetchAfterSuccess, html, context
        )) {
            is VerificationResult.Response -> {
                val (url2, body) = result.value
                StrResponse(url2.ifEmpty { url }, body)
            }

            VerificationResult.Refetch -> AnalyzeUrl(
                url,
                source = getSource(),
                coroutineContext = context
            ).getStrResponse(useWebView = false)
        }
    }

    /**
     * 打开图片验证码对话框，等待返回验证结果
     */
    fun getVerificationCode(imageUrl: String): String {
        rhinoContext.ensureActive()
        val result = SourceVerificationHelp.getVerificationResult(
            getSource(), imageUrl, "", false, coroutineContext = context
        )
        check(result is VerificationResult.Response)
        return result.value.second
    }

    /**
     * 可从网络，本地文件(阅读私有数据目录相对路径)导入JavaScript脚本
     */
    @JavascriptInterface
    fun importScript(path: String): String {
        val result = when {
            path.startsWith("http") -> cacheFile(path)
            else -> readTxtFile(path)
        }
        if (result.isBlank()) throw NoStackTraceException("$path 内容获取失败或者为空")
        return result
    }

    /**
     * 缓存以文本方式保存的文件 如.js .txt等
     * @param urlStr 网络文件的链接
     * @return 返回缓存后的文件内容
     */
    @JavascriptInterface
    fun cacheFile(urlStr: String): String {
        return cacheFile(urlStr, 0)
    }

    /**
     * 缓存以文本方式保存的文件 如.js .txt等
     * @param saveTime 缓存时间，单位：秒
     */
    @JavascriptInterface
    fun cacheFile(urlStr: String, saveTime: Int): String {
        val key = md5Encode16(urlStr)
        val cachePath = CacheManager.get(key)
        return if (
            cachePath.isNullOrBlank() ||
            !getFile(cachePath).exists()
        ) {
            val path = downloadFile(urlStr)
            log("首次下载 $urlStr >> $path")
            CacheManager.put(key, path, saveTime)
            readTxtFile(path)
        } else {
            readTxtFile(cachePath)
        }
    }

    /**
     *js实现读取cookie
     */
    @JavascriptInterface
    fun getCookie(tag: String): String {
        return getCookie(tag, null)
    }

    @JavascriptInterface
    fun getCookie(tag: String, key: String?): String {
        return if (key != null) {
            CookieStore.getKey(tag, key)
        } else {
            CookieStore.getCookie(tag)
        }
    }

    /**
     * 下载文件
     * @param url 下载地址:可带参数type
     * @return 下载的文件相对路径
     */
    @JavascriptInterface
    fun downloadFile(url: String): String {
        rhinoContextOrNull?.ensureActive()
        val analyzeUrl = AnalyzeUrl(url, source = getSource(), coroutineContext = context)
        val type = analyzeUrl.type ?: JsFileHelper.getSuffix(url)
        val file = JsFileHelper.resolve("${MD5Utils.md5Encode16(url)}.${type}")
        file.delete()
        analyzeUrl.getInputStream().use { iStream ->
            file.parentFile?.mkdirs()
            try {
                file.outputStream().buffered().use { oStream ->
                    iStream.copyTo(oStream)
                }
            } catch (e: Throwable) {
                file.delete()
                throw e
            }
        }
        return JsFileHelper.relative(file)
    }


    /**
     * 实现16进制字符串转文件
     * @param content 需要转成文件的16进制字符串
     * @param url 通过url里的参数来判断文件类型
     * @return 相对路径
     */
    @Deprecated(
        "Deprecated",
        ReplaceWith("downloadFile(url)")
    )
    @JavascriptInterface
    fun downloadFile(content: String, url: String): String {
        rhinoContextOrNull?.ensureActive()
        val type = AnalyzeUrl(url, source = getSource(), coroutineContext = context).type
            ?: return ""
        val file = JsFileHelper.resolve("${MD5Utils.md5Encode16(url)}.${type}")
        file.parentFile?.mkdirs()
        HexUtil.decodeHex(content).let {
            if (it.isNotEmpty()) {
                file.writeBytes(it)
            }
        }
        return JsFileHelper.relative(file)
    }

    /**
     * js实现重定向拦截,网络访问get
     */
    fun get(urlStr: String, headers: Any?): Connection.Response {
        return get(urlStr, headers, null)
    }

    fun get(urlStr: String, headers: Any?, timeout: Int?): Connection.Response {
        val headerMap = parseJsRequestHeaders(headers)
        val requestHeaders = if (getSource()?.enabledCookieJar == true) {
            headerMap.toMutableMap().apply { put(cookieJarHeader, "1") }
        } else headerMap
        val rateLimiter = ConcurrentRateLimiter(getSource())
        val response = rateLimiter.withLimitBlocking {
            rhinoContextOrNull?.ensureActive()
            Jsoup.connect(urlStr)
                .sslSocketFactory(SSLHelper.unsafeSSLSocketFactory)
                .timeout(timeout ?: 30000)
                .ignoreContentType(true)
                .followRedirects(false)
                .headers(requestHeaders)
                .method(Connection.Method.GET)
                .execute()
        }
        return response
    }

    /**
     * js实现重定向拦截,网络访问head,不返回Response Body更省流量
     */
    fun head(urlStr: String, headers: Any?): Connection.Response {
        return head(urlStr, headers, null)
    }

    fun head(urlStr: String, headers: Any?, timeout: Int?): Connection.Response {
        val headerMap = parseJsRequestHeaders(headers)
        val requestHeaders = if (getSource()?.enabledCookieJar == true) {
            headerMap.toMutableMap().apply { put(cookieJarHeader, "1") }
        } else headerMap
        val rateLimiter = ConcurrentRateLimiter(getSource())
        val response = rateLimiter.withLimitBlocking {
            rhinoContextOrNull?.ensureActive()
            Jsoup.connect(urlStr)
                .sslSocketFactory(SSLHelper.unsafeSSLSocketFactory)
                .timeout(timeout ?: 30000)
                .ignoreContentType(true)
                .followRedirects(false)
                .headers(requestHeaders)
                .method(Connection.Method.HEAD)
                .execute()
        }
        return response
    }

    /**
     * 网络访问post
     */
    fun post(urlStr: String, body: String, headers: Any?): Connection.Response {
        return post(urlStr, body, headers, null)
    }

    fun post(urlStr: String, body: String, headers: Any?, timeout: Int?): Connection.Response {
        val headerMap = parseJsRequestHeaders(headers)
        val requestHeaders = if (getSource()?.enabledCookieJar == true) {
            headerMap.toMutableMap().apply { put(cookieJarHeader, "1") }
        } else headerMap
        val rateLimiter = ConcurrentRateLimiter(getSource())
        val response = rateLimiter.withLimitBlocking {
            rhinoContextOrNull?.ensureActive()
            Jsoup.connect(urlStr)
                .sslSocketFactory(SSLHelper.unsafeSSLSocketFactory)
                .timeout(timeout ?: 30000)
                .ignoreContentType(true)
                .followRedirects(false)
                .requestBody(body)
                .headers(requestHeaders)
                .method(Connection.Method.POST)
                .execute()
        }
        return response
    }

    /* Str转ByteArray */
    fun strToBytes(str: String): ByteArray {
        return str.toByteArray(charset("UTF-8"))
    }

    fun strToBytes(str: String, charset: String): ByteArray {
        return str.toByteArray(charset(charset))
    }

    /* ByteArray转Str */
    fun bytesToStr(bytes: ByteArray): String {
        return String(bytes, charset("UTF-8"))
    }

    fun bytesToStr(bytes: ByteArray, charset: String): String {
        return String(bytes, charset(charset))
    }

    /**
     * js实现base64解码,不能删
     */
    @JavascriptInterface
    fun base64Decode(str: String?): String {
        return Base64.decodeStr(str)
    }

    @JavascriptInterface
    fun base64Decode(str: String?, charset: String): String {
        return Base64.decodeStr(str, charset(charset))
    }

    @JavascriptInterface
    fun base64Decode(str: String, flags: Int): String {
        return EncoderUtils.base64Decode(str, flags)
    }

    fun base64DecodeToByteArray(str: String?): ByteArray? {
        if (str.isNullOrBlank()) {
            return null
        }
        return EncoderUtils.base64DecodeToByteArray(str, 0)
    }

    fun base64DecodeToByteArray(str: String?, flags: Int): ByteArray? {
        if (str.isNullOrBlank()) {
            return null
        }
        return EncoderUtils.base64DecodeToByteArray(str, flags)
    }

    @JavascriptInterface
    fun base64Encode(str: String): String? {
        return EncoderUtils.base64Encode(str, 2)
    }

    @JavascriptInterface
    fun base64Encode(str: String, flags: Int): String? {
        return EncoderUtils.base64Encode(str, flags)
    }

    /* HexString 解码为字节数组 */
    fun hexDecodeToByteArray(hex: String): ByteArray? {
        return HexUtil.decodeHex(hex)
    }

    /* hexString 解码为utf8String*/
    @JavascriptInterface
    fun hexDecodeToString(hex: String): String? {
        return HexUtil.decodeHexStr(hex)
    }

    /* utf8 编码为hexString */
    @JavascriptInterface
    fun hexEncodeToString(utf8: String): String? {
        return HexUtil.encodeHexStr(utf8)
    }

    /**
     * 格式化时间
     */
    @JavascriptInterface
    fun timeFormatUTC(time: Long, format: String, sh: Int): String? {
        val utc = SimpleTimeZone(sh, "UTC")
        return SimpleDateFormat(format, Locale.getDefault()).run {
            timeZone = utc
            format(Date(time))
        }
    }

    /**
     * 时间格式化
     */
    @JavascriptInterface
    fun timeFormat(time: Long): String {
        return dateFormat.format(Date(time))
    }

    @JavascriptInterface
    fun encodeURI(str: String): String {
        return try {
            URLEncoder.encode(str, "UTF-8")
        } catch (e: Exception) {
            ""
        }
    }

    @JavascriptInterface
    fun encodeURI(str: String, enc: String): String {
        return try {
            URLEncoder.encode(str, enc)
        } catch (e: Exception) {
            ""
        }
    }

    @JavascriptInterface
    fun htmlFormat(str: String): String {
        return HtmlFormatter.formatKeepImg(str)
    }

    @JavascriptInterface
    fun htmlFormat(str: String, redirectUrl: String): String {
        val url = kotlin.runCatching { URL(redirectUrl) }.getOrNull()
        return HtmlFormatter.formatKeepImg(str, url)
    }

    @JavascriptInterface
    fun t2s(text: String): String {
        return ChineseUtils.t2s(text)
    }

    @JavascriptInterface
    fun s2t(text: String): String {
        return ChineseUtils.s2t(text)
    }

    @JavascriptInterface
    fun getWebViewUA(): String {
        return Engine.userAgent
    }

//****************文件操作******************//

    /**
     * 获取本地文件（服务端为每个用户独立的沙箱目录）
     * @param path 相对路径
     * @return File
     */
    fun getFile(path: String): File {
        return JsFileHelper.resolve(path)
    }

    fun readFile(path: String): ByteArray? {
        val file = getFile(path)
        if (file.exists()) {
            return file.readBytes()
        }
        return null
    }

    @JavascriptInterface
    fun readTxtFile(path: String): String {
        val file = getFile(path)
        if (file.exists()) {
            val charsetName = EncodingDetect.getEncode(file)
            return String(file.readBytes(), charset(charsetName))
        }
        return ""
    }

    @JavascriptInterface
    fun readTxtFile(path: String, charsetName: String): String {
        val file = getFile(path)
        if (file.exists()) {
            return String(file.readBytes(), charset(charsetName))
        }
        return ""
    }

    /**
     * 删除本地文件
     */
    @JavascriptInterface
    fun deleteFile(path: String): Boolean {
        val file = getFile(path)
        return if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    /**
     * js实现Zip压缩文件解压
     * @param zipPath 相对路径
     * @return 相对路径
     */
    @JavascriptInterface
    fun unzipFile(zipPath: String): String {
        return unArchiveFile(zipPath)
    }

    /**
     * js实现7Zip压缩文件解压
     * @param zipPath 相对路径
     * @return 相对路径
     */
    @JavascriptInterface
    fun un7zFile(zipPath: String): String {
        return unArchiveFile(zipPath)
    }

    /**
     * js实现Rar压缩文件解压
     * @param zipPath 相对路径
     * @return 相对路径
     */
    @JavascriptInterface
    fun unrarFile(zipPath: String): String {
        return unArchiveFile(zipPath)
    }

    /**
     * js实现压缩文件解压
     * @param zipPath 相对路径
     * @return 相对路径
     */
    @JavascriptInterface
    fun unArchiveFile(zipPath: String): String {
        if (zipPath.isEmpty()) return ""
        val zipFile = getFile(zipPath)
        if (!zipFile.name.endsWith(".zip", true) && !zipFile.name.endsWith(".epub", true)) {
            throw UnsupportedOnServerException("仅支持 zip 解压")
        }
        return JsFileHelper.relative(JsFileHelper.unzip(zipFile))
    }

    /**
     * js实现文件夹内所有文本文件读取
     * @param path 文件夹相对路径
     * @return 所有文件字符串换行连接
     */
    @JavascriptInterface
    fun getTxtInFolder(path: String): String {
        if (path.isEmpty()) return ""
        val folder = getFile(path)
        val contents = StringBuilder()
        folder.listFiles().let {
            if (it != null) {
                for (f in it) {
                    val charsetName = EncodingDetect.getEncode(f)
                    contents.append(String(f.readBytes(), charset(charsetName)))
                        .append("\n")
                }
                contents.deleteCharAt(contents.length - 1)
            }
        }
        folder.deleteRecursively()
        return contents.toString()
    }

    /**
     * 获取网络zip文件里面的数据
     * @param url zip文件的链接或十六进制字符串
     * @param path 所需获取文件在zip内的路径
     * @return zip指定文件的数据
     */
    @JavascriptInterface
    fun getZipStringContent(url: String, path: String): String {
        val byteArray = getZipByteArrayContent(url, path) ?: return ""
        val charsetName = EncodingDetect.getEncode(byteArray)
        return String(byteArray, Charset.forName(charsetName))
    }

    @JavascriptInterface
    fun getZipStringContent(url: String, path: String, charsetName: String): String {
        val byteArray = getZipByteArrayContent(url, path) ?: return ""
        return String(byteArray, Charset.forName(charsetName))
    }

    /**
     * 获取网络zip文件里面的数据
     * @param url zip文件的链接或十六进制字符串
     * @param path 所需获取文件在zip内的路径
     * @return zip指定文件的数据
     */
    @JavascriptInterface
    fun getRarStringContent(url: String, path: String): String {
        val byteArray = getRarByteArrayContent(url, path) ?: return ""
        val charsetName = EncodingDetect.getEncode(byteArray)
        return String(byteArray, Charset.forName(charsetName))
    }

    @JavascriptInterface
    fun getRarStringContent(url: String, path: String, charsetName: String): String {
        val byteArray = getRarByteArrayContent(url, path) ?: return ""
        return String(byteArray, Charset.forName(charsetName))
    }

    /**
     * 获取网络7zip文件里面的数据
     * @param url 7zip文件的链接或十六进制字符串
     * @param path 所需获取文件在7zip内的路径
     * @return zip指定文件的数据
     */
    @JavascriptInterface
    fun get7zStringContent(url: String, path: String): String {
        val byteArray = get7zByteArrayContent(url, path) ?: return ""
        val charsetName = EncodingDetect.getEncode(byteArray)
        return String(byteArray, Charset.forName(charsetName))
    }

    @JavascriptInterface
    fun get7zStringContent(url: String, path: String, charsetName: String): String {
        val byteArray = get7zByteArrayContent(url, path) ?: return ""
        return String(byteArray, Charset.forName(charsetName))
    }

    /**
     * 获取网络zip文件里面的数据
     * @param url zip文件的链接或十六进制字符串
     * @param path 所需获取文件在zip内的路径
     * @return zip指定文件的数据
     */
    fun getZipByteArrayContent(url: String, path: String): ByteArray? {
        val bytes = if (url.isAbsUrl()) {
            AnalyzeUrl(url, source = getSource(), coroutineContext = context).getByteArray()
        } else {
            HexUtil.decodeHex(url)
        }
        val bos = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry: ZipEntry
            while (zis.nextEntry.also { entry = it } != null) {
                if (entry.name.equals(path)) {
                    zis.use { it.copyTo(bos) }
                    return bos.toByteArray()
                }
                entry = zis.nextEntry
            }
        }

        log("getZipContent 未发现内容")
        return null
    }

    /**
     * 获取网络Rar文件里面的数据
     * @param url Rar文件的链接或十六进制字符串
     * @param path 所需获取文件在Rar内的路径
     * @return Rar指定文件的数据
     */
    fun getRarByteArrayContent(url: String, path: String): ByteArray? {
        val bytes = if (url.isAbsUrl()) {
            AnalyzeUrl(url, source = getSource(), coroutineContext = context).getByteArray()
        } else {
            HexUtil.decodeHex(url)
        }

        return ByteArrayInputStream(bytes).use {
            throw UnsupportedOnServerException("rar/7z 解压")
        }
    }

    /**
     * 获取网络7zip文件里面的数据
     * @param url 7zip文件的链接或十六进制字符串
     * @param path 所需获取文件在7zip内的路径
     * @return 7zip指定文件的数据
     */
    fun get7zByteArrayContent(url: String, path: String): ByteArray? {
        val bytes = if (url.isAbsUrl()) {
            AnalyzeUrl(url, source = getSource(), coroutineContext = context).getByteArray()
        } else {
            HexUtil.decodeHex(url)
        }

        return ByteArrayInputStream(bytes).use {
            throw UnsupportedOnServerException("rar/7z 解压")
        }
    }


//******************文件操作************************//

    /**
     * 解析字体Base64数据,返回字体解析类
     */
    @Deprecated(
        "Deprecated",
        ReplaceWith("queryTTF(data)")
    )
    fun queryBase64TTF(data: String?): QueryTTF? {
        log("queryBase64TTF(String)方法已过时,并将在未来删除；请无脑使用queryTTF(Any)替代，新方法支持传入 url、本地文件、base64、ByteArray 自动判断&自动缓存，特殊情况需禁用缓存请传入第二可选参数false:Boolean")
        return queryTTF(data)
    }

    /**
     * 返回字体解析类
     * @param data 支持url,本地文件,base64,ByteArray,自动判断,自动缓存
     * @param useCache 可选开关缓存,不传入该值默认开启缓存
     */
    @OptIn(ExperimentalStdlibApi::class)
    fun queryTTF(data: Any?, useCache: Boolean): QueryTTF? {
        try {
            var key: String? = null
            var qTTF: QueryTTF?
            when (data) {
                is String -> {
                    if (useCache) {
                        key = MessageDigest.getInstance("SHA-256").digest(data.toByteArray())
                            .toHexString()
                        qTTF = AppCacheManager.getQueryTTF(key)
                        if (qTTF != null) return qTTF
                    }
                    val font: ByteArray? = when {
                        data.isAbsUrl() -> AnalyzeUrl(
                            data,
                            source = getSource(),
                            coroutineContext = context
                        ).getByteArray()

                        else -> base64DecodeToByteArray(data)
                    }
                    font ?: return null
                    qTTF = QueryTTF(font)
                }

                is ByteArray -> {
                    if (useCache) {
                        key = MessageDigest.getInstance("SHA-256").digest(data).toHexString()
                        qTTF = AppCacheManager.getQueryTTF(key)
                        if (qTTF != null) return qTTF
                    }
                    qTTF = QueryTTF(data)
                }

                else -> return null
            }
            if (key != null) AppCacheManager.put(key, qTTF)
            return qTTF
        } catch (e: Exception) {
            AppLog.put("[queryTTF] 获取字体处理类出错", e)
            throw e
        }
    }

    fun queryTTF(data: Any?): QueryTTF? {
        return queryTTF(data, true)
    }

    /**
     * @param text 包含错误字体的内容
     * @param errorQueryTTF 错误的字体
     * @param correctQueryTTF 正确的字体
     * @param filter 删除 errorQueryTTF 中不存在的字符
     */
    fun replaceFont(
        text: String,
        errorQueryTTF: QueryTTF?,
        correctQueryTTF: QueryTTF?,
        filter: Boolean
    ): String {
        if (errorQueryTTF == null || correctQueryTTF == null) return text
        val contentArray = text.toStringArray() //这里不能用toCharArray,因为有些文字占多个字节
        val intArray = IntArray(1)
        contentArray.forEachIndexed { index, s ->
            val oldCode = s.codePointAt(0)
            // 忽略正常的空白字符
            if (errorQueryTTF.isBlankUnicode(oldCode)) {
                return@forEachIndexed
            }
            // 删除轮廓数据不存在的字符
            var glyf = errorQueryTTF.getGlyfByUnicode(oldCode)  // 轮廓数据不存在
            if (errorQueryTTF.getGlyfIdByUnicode(oldCode) == 0) glyf = null // 轮廓数据指向保留索引0
            if (filter && (glyf == null)) {
                contentArray[index] = ""
                return@forEachIndexed
            }
            // 使用轮廓数据反查Unicode
            val code = correctQueryTTF.getUnicodeByGlyf(glyf)
            if (code != 0) {
                intArray[0] = code
                contentArray[index] = String(intArray, 0, 1)
            }
        }
        return contentArray.joinToString("")
    }

    /**
     * @param text 包含错误字体的内容
     * @param errorQueryTTF 错误的字体
     * @param correctQueryTTF 正确的字体
     */
    fun replaceFont(
        text: String,
        errorQueryTTF: QueryTTF?,
        correctQueryTTF: QueryTTF?
    ): String {
        return replaceFont(text, errorQueryTTF, correctQueryTTF, false)
    }


    /**
     * 章节数转数字
     */
    @JavascriptInterface
    fun toNumChapter(s: String?): String? {
        s ?: return null
        val matcher = AppPattern.titleNumPattern.matcher(s)
        if (matcher.find()) {
            val intStr = StringUtils.stringToInt(matcher.group(2))
            return "${matcher.group(1)}${intStr}${matcher.group(3)}"
        }
        return s
    }


    fun toURL(urlStr: String): JsURL {
        return JsURL(urlStr)
    }

    fun toURL(url: String, baseUrl: String? = null): JsURL {
        return JsURL(url, baseUrl)
    }

    /**
     * 弹窗提示（网页版：收集后由前端显示）
     */
    fun toast(msg: Any?) {
        rhinoContextOrNull?.ensureActive()
        EngineContext.current?.messages?.add(msg.toString().trim())
        log("[toast] $msg")
    }

    /**
     * 弹窗提示 停留时间较长
     */
    fun longToast(msg: Any?) {
        rhinoContextOrNull?.ensureActive()
        EngineContext.current?.messages?.add(msg.toString().trim())
        log("[toast] $msg")
    }

    /**
     * 输出调试日志
     */
    fun log(msg: Any?): Any? {
        rhinoContextOrNull?.ensureActive()
        getSource()?.let {
            Debug.log(it.getKey(), msg.toString())
        } ?: Debug.log(msg.toString())
        AppLog.putDebug("${getTag() ?: "源"}调试输出: $msg")
        return msg
    }

    /**
     * 输出对象类型
     */
    fun logType(any: Any?) {
        if (any == null) {
            log("null")
        } else {
            log(any.javaClass.name)
        }
    }

    /**
     * 生成UUID
     */
    @JavascriptInterface
    fun randomUUID(): String {
        return UUID.randomUUID().toString()
    }

    @JavascriptInterface
    fun androidId(): String {
        return AppConst.androidId
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        openUrl(url, null)
    }

    /**
     * 打开应用跳转或者网页（网页版：http 链接交给前端在新标签页打开）
     * @param mimeType 指定应用类型
     */
    @JavascriptInterface
    fun openUrl(url: String, mimeType: String? = null) {
        require(url.length < 64 * 1024) { "openUrl parameter url too long" }
        rhinoContextOrNull?.ensureActive()
        if (!canOpenSourceUi()) return
        getSource() ?: throw NoStackTraceException("openUrl source cannot be null")
        if (url.startsWith("http", true)) {
            EngineContext.current?.openUrls?.add(url)
        }
        log("[openUrl] $url")
    }

    fun showBrowser(url: String) = showBrowser(url, null, null, null)

    fun showBrowser(url: String, html: String?) = showBrowser(url, html, null, null)

    fun showBrowser(url: String, html: String?, preloadJs: String?) =
        showBrowser(url, html, preloadJs, null)

    /**
     * 底部弹窗浏览器（段评、本章说等）。
     * 网页版：推送给用户正在使用的页面，以底部弹窗显示；用户没有打开页面时在新标签页打开。
     */
    fun showBrowser(
        url: String,
        html: String?,
        preloadJs: String?,
        config: String?
    ) {
        rhinoContextOrNull?.ensureActive()
        if (!canOpenSourceUi()) return
        val source = getSource() ?: return
        if (SourceVerificationHelp.handler?.showBrowser(source, url, html, config) == true) {
            log("[showBrowser] $url")
            return
        }
        if (url.startsWith("http", true)) {
            EngineContext.current?.openUrls?.add(url)
        }
        log("[showBrowser] $url")
    }

    private fun canOpenSourceUi(): Boolean {
        if (EngineContext.current?.suppressUi == true) {
            Debug.log(getTag(), "加载图片等后台操作中不打开书源网页")
            return false
        }
        if (!shouldSuppressSourceNavigation(AppConfig.blockSourceNavigation, context)) return true
        Debug.log(getTag(), "已阻止当前操作中的书源网页或视频跳转")
        return false
    }

    fun singleFlight(
        name: String,
        action: Function,
        timeoutMs: Long
    ) {
        SourceLock.singleFlight(sourceConcurrencyKey(name), timeoutMs) {
            val cx = rhinoContext
            action.call(cx, action.parentScope, functionThisObj(action), emptyArray<Any?>())
        }
    }

    private fun functionThisObj(action: Function): Scriptable {
        val top = action.parentScope?.let { ScriptableObject.getTopLevelScope(it) }
        return top?.globalThis
            ?: Undefined.SCRIPTABLE_UNDEFINED
    }

    fun singleFlight(
        name: String,
        action: Function
    ) = singleFlight(name, action, 15_000L)

    fun lock(
        name: String,
        action: Function,
        timeoutMs: Long
    ) {
        SourceLock.lock(sourceConcurrencyKey(name), timeoutMs) {
            val cx = rhinoContext
            action.call(cx, action.parentScope, functionThisObj(action), emptyArray<Any?>())
        }
    }

    fun lock(
        name: String,
        action: Function
    ) = lock(name, action, 15_000L)

    fun tick(name: String): Int {
        return SourceLock.tick(sourceConcurrencyKey(name))
    }

    private fun sourceConcurrencyKey(name: String): String {
        if (name.isBlank() || name.length > 256) {
            throw NoStackTraceException("name 不能为空且长度不能超过 256")
        }
        val sourceKey = getSource()?.getKey()
            ?: throw NoStackTraceException("并发工具只能在书源、订阅源或朗读源中使用")
        // 服务端多用户：按用户隔离
        return "${EngineContext.currentUserId}:$sourceKey@$name"
    }

    /**
     * 获取阅读配置（网页版的排版在浏览器端，这里返回默认值以保持兼容）
     */
    @JavascriptInterface
    fun getReadBookConfig(): String {
        return GSON.toJson(getReadBookConfigMap())
    }

    fun getReadBookConfigMap(): Map<String, Any> {
        return mapOf(
            "textSize" to 20,
            "lineSpacingExtra" to 12,
            "paragraphSpacing" to 2,
            "textBold" to 0,
            "pageAnim" to 0,
        )
    }

    /**
     * 获取主题模式
     */
    @JavascriptInterface
    fun getThemeMode(): String {
        return AppConfig.themeMode ?: "0"
    }

    /**
     * 获取主题配置
     */
    @JavascriptInterface
    fun getThemeConfig(): String {
        return GSON.toJson(getThemeConfigMap())
    }

    fun getThemeConfigMap(): Map<String, Any?> {
        return mapOf("themeName" to "web", "isNightTheme" to false)
    }

}
