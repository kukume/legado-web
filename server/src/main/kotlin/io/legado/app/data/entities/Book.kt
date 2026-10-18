package io.legado.app.data.entities

import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlin.math.max

/**
 * 书籍（引擎内使用的对象，持久化由 Web 层负责）
 */
data class Book(
    override var bookUrl: String = "",
    var tocUrl: String = "",
    var origin: String = BookType.localTag,
    var originName: String = "",
    override var name: String = "",
    override var author: String = "",
    override var kind: String? = null,
    var customTag: String? = null,
    var coverUrl: String? = null,
    var customCoverUrl: String? = null,
    var intro: String? = null,
    var customIntro: String? = null,
    var charset: String? = null,
    var type: Int = BookType.text,
    var group: Long = 0,
    var latestChapterTitle: String? = null,
    var latestChapterTime: Long = System.currentTimeMillis(),
    var lastCheckTime: Long = System.currentTimeMillis(),
    var lastCheckCount: Int = 0,
    var totalChapterNum: Int = 0,
    var durChapterTitle: String? = null,
    var durChapterIndex: Int = 0,
    var durChapterPos: Int = 0,
    var durChapterTime: Long = System.currentTimeMillis(),
    override var wordCount: String? = null,
    var canUpdate: Boolean = true,
    var order: Int = 0,
    var originOrder: Int = 0,
    override var variable: String? = null,
    var readConfig: ReadConfig? = null,
    var syncTime: Long = 0L,
    /** 当前卷索引 */
    var durVolumeIndex: Int = 0,
    /** 相对于卷的索引 */
    var chapterInVolumeIndex: Int = 0,
    var persistedCoverUrl: String? = null
) : BaseBook {

    override fun equals(other: Any?): Boolean {
        if (other is Book) {
            return other.bookUrl == bookUrl
        }
        return false
    }

    override fun hashCode(): Int {
        return bookUrl.hashCode()
    }

    @delegate:Transient
    override val variableMap: HashMap<String, String> by lazy {
        GSON.fromJsonObject<HashMap<String, String>>(variable).getOrNull() ?: hashMapOf()
    }

    @Transient
    override var infoHtml: String? = null

    @Transient
    override var tocHtml: String? = null

    @Transient
    var downloadUrls: List<String>? = null

    val lastChapterIndex get() = totalChapterNum - 1

    fun getRealAuthor() = author.replace(AppPattern.authorRegex, "")

    fun getUnreadChapterNum() = max(totalChapterNum - durChapterIndex - 1, 0)

    fun getDisplayCover() = if (customCoverUrl.isNullOrEmpty()) coverUrl else customCoverUrl

    fun getDisplayIntro() = if (customIntro.isNullOrEmpty()) intro else customIntro

    val config: ReadConfig
        get() {
            if (readConfig == null) {
                readConfig = ReadConfig()
            }
            return readConfig!!
        }

    fun setReverseToc(reverseToc: Boolean) {
        config.reverseToc = reverseToc
    }

    fun getReverseToc(): Boolean {
        return config.reverseToc
    }

    fun setUseReplaceRule(useReplaceRule: Boolean) {
        config.useReplaceRule = useReplaceRule
    }

    fun getUseReplaceRule(): Boolean {
        return config.useReplaceRule ?: true
    }

    fun toSearchBook() = SearchBook(
        name = name,
        author = author,
        kind = kind,
        bookUrl = bookUrl,
        origin = origin,
        originName = originName,
        type = type,
        wordCount = wordCount,
        latestChapterTitle = latestChapterTitle,
        coverUrl = coverUrl,
        intro = intro,
        tocUrl = tocUrl,
        originOrder = originOrder,
        variable = variable
    ).apply {
        this.infoHtml = this@Book.infoHtml
        this.tocHtml = this@Book.tocHtml
    }

    data class ReadConfig(
        var reverseToc: Boolean = false,
        var pageAnim: Int? = null,
        var reSegment: Boolean = false,
        var imageStyle: String? = null,
        var useReplaceRule: Boolean? = null,
        var delTag: Long = 0L,
        var ttsEngine: String? = null,
        var splitLongChapter: Boolean = true,
        /** 删除正文开头与章节名重复的标题行（网页版的设置；App 默认删除，按章节单独关闭） */
        var removeSameTitle: Boolean = true,
    )
}
