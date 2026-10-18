package io.legado.app.help.engine

/**
 * 代替 Android BackstageWebView 的无头浏览器接口
 */
interface WebViewProvider {

    data class Request(
        val url: String?,
        val html: String?,
        val javaScript: String?,
        val headerMap: Map<String, String>?,
        val sourceRegex: String? = null,
        val overrideUrlRegex: String? = null,
        val delayTime: Long = 0,
        val cookie: String? = null,
    )

    data class Result(
        /** 最终地址 */
        val url: String,
        /** js 执行结果 / 页面源码 / 匹配到的资源地址 */
        val body: String,
    )

    suspend fun load(request: Request): Result
}
