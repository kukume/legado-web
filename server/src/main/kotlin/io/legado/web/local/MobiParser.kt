package io.legado.web.local

import io.legado.app.lib.mobi.KF6Book
import io.legado.app.lib.mobi.KF8Book
import io.legado.app.lib.mobi.MobiBook
import io.legado.app.lib.mobi.MobiReader
import io.legado.app.lib.mobi.entities.TOC
import io.legado.app.utils.HtmlFormatter
import org.jsoup.Jsoup
import java.io.File

/**
 * MOBI / AZW3 / AZW 解析，目录和正文的取法移植自 App 的 MobiFile（同时支持 KF6 和 KF8）。
 *
 * 章节地址为 mobi:{序号}:{href}（与 App 的 url 相同，加了前缀），卷名与下一章指向同一位置时为 mobi:skip:…；
 * 正文读到下一章的位置为止。图片地址保留书内的引用（KF6 为 recindex:N，KF8 为 kindle:embed:…）。
 */
object MobiParser {

    data class Chapter(val title: String, val url: String, val isVolume: Boolean)
    data class Meta(val title: String, val author: String, val intro: String?)

    const val PREFIX = "mobi:"

    private val xmlDeclarationRegex = Regex("<\\?xml[^>]*>")
    private val doctypeDeclarationRegex = Regex("<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE)

    /** 文件头：PalmDB 的类型和创建者为 BOOKMOBI */
    fun looksLikeMobi(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = input.readNBytes(68)
            head.size == 68 && String(head, 60, 8, Charsets.ISO_8859_1) == "BOOKMOBI"
        }
    }.getOrDefault(false)

    private fun book(file: File): MobiBook = LocalBookCache.get(file) {
        try {
            MobiReader().readMobi(it)
        } catch (e: Exception) {
            throw LocalBookException("不是有效的 MOBI / AZW3 文件：${e.message}")
        }
    }

    fun meta(file: File): Meta {
        val m = book(file).metadata
        return Meta(
            title = m.title,
            author = m.author.firstOrNull().orEmpty(),
            intro = m.description.takeIf { it.isNotBlank() }?.let { HtmlFormatter.formatKeepImg(it).replace(Regex("<img[^>]*>"), "").trim() },
        )
    }

    fun cover(file: File): ByteArray? = runCatching { book(file).getCover() }.getOrNull()

    fun chapters(file: File): List<Chapter> {
        val mobi = book(file)
        return synchronized(mobi) {
            when (mobi) {
                is KF8Book -> chapterList(mobi.toc, mobi.sectionIdMap[0] == null) {
                    val section = mobi.sections.firstOrNull { it.href.isNotEmpty() } ?: return@chapterList null
                    titleOf(mobi.getSectionText(section)) to section.href
                }
                is KF6Book -> chapterList(mobi.toc, mobi.sectionIdMap[0] == null) {
                    val section = mobi.sections.firstOrNull() ?: return@chapterList null
                    titleOf(mobi.getSectionText(section)) to section.href
                }
                else -> emptyList()
            }
        }
    }

    private fun titleOf(html: String) = Jsoup.parse(html).getElementsByTag("title").first()?.text()?.takeIf { it.isNotBlank() } ?: "卷首"

    /** App 的 getChapterListKF6 / getChapterListKF8（两者相同） */
    private fun chapterList(toc: List<TOC>?, needFirst: Boolean, first: () -> Pair<String, String>?): List<Chapter> {
        class Item(var title: String, var url: String, val isVolume: Boolean)
        val list = ArrayList<Item>()
        if (needFirst) first()?.let { (title, href) -> list.add(Item(title, "0:$href", false)) }
        fun append(ref: TOC) {
            val item = Item(ref.label, "${list.size}:${ref.href}", ref.subitems != null)
            val last = list.lastOrNull()
            // 卷名和第一章指向同一个位置：卷名不显示正文
            if (last != null && last.isVolume && last.url.substringAfter(":") == item.url.substringAfter(":")) {
                last.url = "skip:" + last.url
            }
            list.add(item)
            ref.subitems?.forEach(::append)
        }
        toc?.forEach(::append)
        return list.map { Chapter(it.title.trim().take(200), PREFIX + it.url, it.isVolume) }
    }

    /**
     * 章节正文（HtmlFormatter 整理后的段落，保留图片）。[nextUrl] 为下一章的地址（App 记在章节变量 nextUrl 里）
     */
    fun content(file: File, url: String, nextUrl: String?): String {
        val u = url.removePrefix(PREFIX)
        if (u.startsWith("skip:")) return ""
        val next = nextUrl?.removePrefix(PREFIX)?.removePrefix("skip:").orEmpty()
        val mobi = book(file)
        val html = synchronized(mobi) {
            when (mobi) {
                is KF8Book -> contentKF8(mobi, u, next)
                is KF6Book -> contentKF6(mobi, u, next)
                else -> null
            }
        } ?: throw LocalBookException("章节不存在")
        return HtmlFormatter.formatKeepImg(html)
            .replace(xmlDeclarationRegex, "")
            .replace(doctypeDeclarationRegex, "")
    }

    private fun contentKF6(book: KF6Book, url: String, nextUrl: String): String? {
        var section = book.getSectionByHref(url) ?: return null
        val sb = StringBuilder(book.getSectionText(section))
        while (true) {
            section = section.next ?: break
            if (section.href == nextUrl.substringAfter(":")) break
            if (book.sectionIdMap[section.index] != null) break
            sb.append(book.getSectionText(section))
        }
        val soup = Jsoup.parse(sb.toString())
        soup.select("title").remove()
        soup.select("[style*=display:none]").remove()
        soup.select("img[recindex]").forEach {
            val recindex = it.attr("recindex")
            it.clearAttributes()
            it.attr("src", "recindex:$recindex")
        }
        return soup.outerHtml()
    }

    private fun contentKF8(book: KF8Book, url: String, nextUrl: String): String? {
        var section = book.getSectionByHref(url) ?: return null
        val nextPos = book.parsePosURI(nextUrl)
        val sb = StringBuilder(book.getTextByHref(url, nextUrl))
        while (true) {
            if (nextPos != null && section.frags.any { it.index == nextPos.fid }) break
            section = section.next ?: break
            if (!section.linear) continue
            if (section.href == nextUrl.substringAfter(":")) break
            if (book.sectionIdMap[section.index] != null) break
            sb.append(book.getSectionText(section))
        }
        val soup = Jsoup.parse(sb.toString())
        soup.select("title").remove()
        soup.select("[style*=display:none]").remove()
        // kindle:flow 指向的 SVG（多为封面页）：<img> 里的 SVG 不能再加载书内图片，换成其中引用的图片
        soup.select("img[src^=kindle:flow:]").forEach { img ->
            val svg = book.getFlowByHref(img.attr("src"))
            val refs = svg?.let { embedRegex.findAll(it).map { m -> m.value }.distinct().toList() }.orEmpty()
            if (refs.isEmpty()) img.remove()
            else {
                refs.forEach { img.before("<img src=\"$it\">") }
                img.remove()
            }
        }
        return soup.outerHtml()
    }

    private val embedRegex = Regex("kindle:embed:\\w+(\\?mime=[\\w/+.-]+)?")

    /** 书内图片（KF6：recindex:N；KF8：kindle:embed:…） */
    fun image(file: File, href: String): ByteArray? {
        val mobi = book(file)
        return synchronized(mobi) {
            runCatching {
                when (mobi) {
                    is KF8Book -> mobi.getResourceByHref(href)
                    is KF6Book -> mobi.getResourceByHref(href)
                    else -> null
                }
            }.getOrNull()
        }
    }
}
