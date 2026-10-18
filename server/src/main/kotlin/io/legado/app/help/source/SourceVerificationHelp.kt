package io.legado.app.help.source

import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.UnsupportedOnServerException
import io.legado.app.help.engine.EngineContext
import io.legado.app.model.Debug
import kotlin.coroutines.CoroutineContext

internal sealed class VerificationResult {
    data class Response(val value: Pair<String, String>) : VerificationResult()
    object Refetch : VerificationResult()
}

/**
 * 书源人工验证（验证码 / 浏览器过盾），接口与 LegadoTeam 版保持一致。
 *
 * Android 版会弹出界面阻塞等待用户操作；网页版无法在服务器上阻塞等待浏览器里的操作，
 * 默认行为是：把网页交给前端在新标签页打开，然后按普通请求重新获取（Refetch）。
 * Web 层可以通过 [handler] 接管：把网页 / 验证码推送给用户正在使用的页面，等待用户操作后把结果交回脚本。
 */
object SourceVerificationHelp {

    interface Handler {
        /**
         * 阻塞等待用户操作。
         * @param useBrowser true: 浏览器验证（返回 url 和网页源码）；false: 图片验证码（返回 url 和用户输入）
         * @return null 表示当前没有可以交互的页面，按默认行为处理
         */
        fun getVerificationResult(
            source: BaseSource,
            url: String,
            title: String,
            useBrowser: Boolean,
            html: String?,
        ): Pair<String, String>?

        /**
         * 底部弹窗浏览器（java.showBrowser），不等待结果。
         * @return false 表示当前没有可以显示的页面，按默认方式处理
         */
        fun showBrowser(source: BaseSource, url: String, html: String?, config: String?): Boolean = false
    }

    @Volatile
    var handler: Handler? = null

    internal fun getVerificationResult(
        source: BaseSource?,
        url: String,
        title: String,
        useBrowser: Boolean,
        refetchAfterSuccess: Boolean = true,
        html: String? = null,
        coroutineContext: CoroutineContext? = null,
    ): VerificationResult {
        source
            ?: throw NoStackTraceException("getVerificationResult parameter source cannot be null")
        require(url.length < 64 * 1024) { "getVerificationResult parameter url too long" }
        handler?.getVerificationResult(source, url, title, useBrowser, html)?.let {
            return VerificationResult.Response(it)
        }
        if (!useBrowser) {
            throw UnsupportedOnServerException("图片验证码")
        }
        requestOpen(source, url, title)
        return if (refetchAfterSuccess) {
            VerificationResult.Refetch
        } else {
            VerificationResult.Response(url to (html ?: ""))
        }
    }

    fun startBrowser(
        source: BaseSource?,
        url: String,
        title: String,
        saveResult: Boolean? = false,
        refetchAfterSuccess: Boolean? = true,
        html: String? = null,
        verificationResultKey: String? = null,
    ) {
        source ?: throw NoStackTraceException("startBrowser parameter source cannot be null")
        require(url.length < 64 * 1024) { "startBrowser parameter url too long" }
        requestOpen(source, url, title)
    }

    /** 交给前端在新标签页打开 */
    private fun requestOpen(source: BaseSource, url: String, title: String) {
        if (url.startsWith("http", true)) {
            EngineContext.current?.openUrls?.add(url)
        }
        Debug.log(source.getKey(), "⇒请求打开网页（$title）: $url")
    }
}
