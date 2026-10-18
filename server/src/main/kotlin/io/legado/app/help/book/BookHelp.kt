package io.legado.app.help.book

import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 正文保存令牌（阅读 App 用于防止并发写入旧内容，服务端只保留结构以兼容移植代码）
 */
data class ContentSaveToken internal constructor(
    val bookUrl: String,
    val chapterIndex: Int,
    val version: Long,
)

object BookHelp {

    /**
     * 正文保存回调，由 Web 层设置（用于缓存章节内容），返回是否保存成功
     */
    @Volatile
    var contentSaver: ((BookSource, Book, BookChapter, String) -> Boolean)? = null

    /** 获取下一章链接的回调（Android 版从数据库读取） */
    @Volatile
    var chapterUrlResolver: ((Book, Int) -> String?)? = null

    internal fun contentSaveToken(book: Book, bookChapter: BookChapter): ContentSaveToken =
        ContentSaveToken(book.bookUrl, bookChapter.index, 0L)

    fun saveContent(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String,
        token: ContentSaveToken = contentSaveToken(book, bookChapter),
        saveChapterMetadata: Boolean = false,
    ): Boolean {
        return contentSaver?.invoke(bookSource, book, bookChapter, content) ?: true
    }

    fun formatBookName(name: String): String {
        return name
            .replace(AppPattern.nameRegex, "")
            .trim { it <= ' ' }
    }

    fun formatBookAuthor(author: String): String {
        return author
            .replace(AppPattern.authorRegex, "")
            .trim { it <= ' ' }
    }

    private val chapterNamePattern1 by lazy {
        "(?<=[第（])[\\d零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+(?=[章节回集话卷篇]|）)".toPattern()
    }

    /**
     * 换源后根据章节标题和序号定位当前章节
     */
    fun getDurChapter(
        oldDurChapterIndex: Int,
        oldDurChapterName: String?,
        newChapterList: List<BookChapter>,
        oldChapterListSize: Int = 0
    ): Int {
        if (oldDurChapterIndex <= 0) return 0
        if (newChapterList.isEmpty()) return oldDurChapterIndex
        val oldChapterNum = getChapterNum(oldDurChapterName)
        val oldName = getPureChapterName(oldDurChapterName)
        val newChapterSize = newChapterList.size
        val durIndex =
            if (oldChapterListSize == 0) oldDurChapterIndex
            else oldDurChapterIndex * oldChapterListSize / newChapterSize
        val min = max(0, min(oldDurChapterIndex, durIndex) - 10)
        val max = min(newChapterSize - 1, max(oldDurChapterIndex, durIndex) + 10)
        var nameSim = 0.0
        var newIndex = 0
        var newNum = 0
        if (oldName.isNotEmpty()) {
            for (i in min..max) {
                val newName = getPureChapterName(newChapterList[i].title)
                val temp = similarity(oldName, newName)
                if (temp > nameSim) {
                    nameSim = temp
                    newIndex = i
                }
            }
        }
        if (nameSim < 0.96 && oldChapterNum > 0) {
            for (i in min..max) {
                val temp = getChapterNum(newChapterList[i].title)
                if (temp == oldChapterNum) {
                    newNum = temp
                    newIndex = i
                    break
                } else if (abs(temp - oldChapterNum) < abs(newNum - oldChapterNum)) {
                    newNum = temp
                    newIndex = i
                }
            }
        }
        return if (nameSim > 0.96 || abs(newNum - oldChapterNum) < 1) {
            newIndex
        } else {
            min(max(0, newChapterList.size - 1), oldDurChapterIndex)
        }
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val sa = a.toSet()
        val sb = b.toSet()
        val inter = sa.intersect(sb).size.toDouble()
        val union = sa.union(sb).size.toDouble()
        return if (union == 0.0) 0.0 else inter / union
    }

    private fun getChapterNum(chapterName: String?): Int {
        chapterName ?: return -1
        val matcher = chapterNamePattern1.matcher(chapterName)
        if (matcher.find()) {
            return io.legado.app.utils.StringUtils.stringToInt(matcher.group())
        }
        return -1
    }

    private val regexOther by lazy { "[^\\w\\u4E00-\\u9FEF〇\\u3400-\\u4DBF\\u20000-\\u2A6DF\\u2A700-\\u2EBEF]".toRegex() }
    private val regexA by lazy { "\\s".toRegex() }
    private val regexB by lazy { "^.*?第(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)[章节篇回集话](?!$)|^(?:[\\d〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[,:、])*(?:[\\d〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)(?:[,:、](?!$)|\\.(?=[^\\d]))".toRegex() }
    private val regexC by lazy { "(?!^)(?:[〖【《〔\\[{(][^〖【《〔\\[{()〕》】〗\\]}]+)?[)〕》】〗\\]}]$|^[〖【《〔\\[{(](?:[^〖【《〔\\[{()〕》】〗\\]}]+[〕》】〗\\]})])?(?!$)".toRegex() }

    private fun getPureChapterName(chapterName: String?): String {
        chapterName ?: return ""
        return chapterName
            .replace(regexA, "")
            .replace(regexB, "")
            .replace(regexC, "")
            .replace(regexOther, "")
    }
}


fun Book.isType(type: Int): Boolean = this.type and type > 0

val Book.isWebFile: Boolean get() = isType(BookType.webFile)
val Book.isImage: Boolean get() = isType(BookType.image)
val Book.isAudio: Boolean get() = isType(BookType.audio)
val Book.isEpub: Boolean get() = false

fun Book.addType(vararg types: Int) {
    types.forEach { type = type or it }
}

fun Book.removeType(vararg types: Int) {
    types.forEach { type = type and it.inv() }
}

fun Book.removeAllBookType() {
    removeType(BookType.allBookType)
}

fun Book.simulatedTotalChapterNum(): Int = totalChapterNum

val Book.isLocal: Boolean
    get() = isType(BookType.local) || origin == BookType.localTag || origin.startsWith(BookType.webDavTag)

val Book.isVideo: Boolean get() = isType(BookType.video)

val Book.isOnLineTxt: Boolean get() = !isLocal && isType(BookType.text)

/** 读取已缓存的正文（服务端缓存由 Web 层管理，引擎内不读取） */
@Suppress("UNUSED_PARAMETER")
fun BookHelp.getContent(book: Book, bookChapter: BookChapter, token: ContentSaveToken? = null): String? = null
