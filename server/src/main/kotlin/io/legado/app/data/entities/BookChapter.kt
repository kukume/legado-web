package io.legado.app.data.entities

import io.legado.app.constant.AppPattern
import io.legado.app.help.RuleBigDataHelp
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleDataInterface
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonObject

data class BookChapter(
    var url: String = "",               // 章节地址
    var title: String = "",             // 章节标题
    var isVolume: Boolean = false,      // 是否是卷名
    var baseUrl: String = "",           // 用来拼接相对url
    var bookUrl: String = "",           // 书籍地址
    var index: Int = 0,                 // 章节序号
    var isVip: Boolean = false,         // 是否VIP
    var isPay: Boolean = false,         // 是否已购买
    var resourceUrl: String? = null,    // 音频真实URL
    var tag: String? = null,            // 更新时间或其他章节附加信息
    var wordCount: String? = null,      // 本章节字数
    var start: Long? = null,            // 章节起始位置
    var end: Long? = null,              // 章节终止位置
    var startFragmentId: String? = null,
    var endFragmentId: String? = null,
    var variable: String? = null,       //变量
    var imgUrl: String? = null          // 标题段评图或者视频封面
) : RuleDataInterface {

    /** 章节数据变化时的回调（Android 版直接写数据库，服务端由 Web 层在请求结束后保存） */
    @Transient
    internal var deferUpdates = false

    fun update() {
        // 服务端：章节对象由调用方在请求结束后统一持久化
    }

    fun putImgUrl(value: String?) {
        imgUrl = value
        update()
    }

    fun putLyric(value: String?) { //存入歌词文本
        if (super.putVariable("lyric", value)) {
            variable = GSON.toJson(variableMap)
            update()
        }
    }

    fun putDanmaku(value: String?) { //存入弹幕文本
        if (super.putVariable("danmaku", value)) {
            variable = GSON.toJson(variableMap)
            update()
        }
    }

    @delegate:Transient
    override val variableMap: HashMap<String, String> by lazy {
        GSON.fromJsonObject<HashMap<String, String>>(variable).getOrNull() ?: hashMapOf()
    }

    @Transient
    var titleMD5: String? = null

    override fun putVariable(key: String, value: String?): Boolean {
        if (super.putVariable(key, value)) {
            variable = GSON.toJson(variableMap)
        }
        return true
    }

    override fun putBigVariable(key: String, value: String?) {
        RuleBigDataHelp.putChapterVariable(bookUrl, url, key, value)
    }

    override fun getBigVariable(key: String): String? {
        return RuleBigDataHelp.getChapterVariable(bookUrl, url, key)
    }

    override fun hashCode() = url.hashCode()

    override fun equals(other: Any?): Boolean {
        if (other is BookChapter) {
            return other.url == url
        }
        return false
    }

    fun primaryStr(): String {
        return bookUrl + url
    }

    fun getDisplayTitle(): String {
        return title.replace(AppPattern.rnRegex, "")
    }

    fun getAbsoluteURL(): String {
        if (url.startsWith(title) && isVolume) return baseUrl
        val urlMatcher = AnalyzeUrl.paramPattern.matcher(url)
        val urlBefore = if (urlMatcher.find()) url.substring(0, urlMatcher.start()) else url
        val urlAbsoluteBefore = NetworkUtils.getAbsoluteURL(baseUrl, urlBefore)
        return if (urlBefore.length == url.length) {
            urlAbsoluteBefore
        } else {
            "$urlAbsoluteBefore," + url.substring(urlMatcher.end())
        }
    }

    private fun ensureTitleMD5Init() {
        if (titleMD5 == null) {
            titleMD5 = MD5Utils.md5Encode16(title)
        }
    }

    fun getFileName(suffix: String = "nb"): String {
        ensureTitleMD5Init()
        return String.format("%05d-%s.%s", index, titleMD5, suffix)
    }
}
