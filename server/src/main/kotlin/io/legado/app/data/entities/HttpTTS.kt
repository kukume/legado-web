package io.legado.app.data.entities

/**
 * 在线朗读引擎（与 App 的 httpTTS 导出格式相同）。
 * url 中可以使用 {{speakText}}（要朗读的文字）和 {{speakSpeed}}（语速）。
 */
data class HttpTTS(
    val id: Long = System.currentTimeMillis(),
    var name: String = "",
    var url: String = "",
    /** 返回内容类型的正则，匹配不上时视为出错（例如返回了错误信息） */
    var contentType: String? = null,
    override var concurrentRate: String? = "0",
    override var loginUrl: String? = null,
    override var loginUi: String? = null,
    override var header: String? = null,
    override var jsLib: String? = null,
    override var enabledCookieJar: Boolean? = false,
    var loginCheckJs: String? = null,
    var lastUpdateTime: Long = System.currentTimeMillis(),
) : BaseSource {

    override fun getTag(): String = name

    override fun getKey(): String = "httpTts:$id"
}
