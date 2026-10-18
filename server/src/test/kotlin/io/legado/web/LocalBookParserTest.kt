package io.legado.web

import io.legado.web.local.EpubParser
import io.legado.web.local.LocalBookException
import io.legado.web.local.TxtParser
import io.legado.app.data.entities.Book
import io.legado.app.model.localBook.TextFile
import io.legado.web.service.RuleDefaults
import java.io.File
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalBookParserTest {

    private fun tmp(suffix: String) = File.createTempFile("lbtest-", suffix).apply { deleteOnExit() }

    /** 按 EPUB 规范写 zip：mimetype 为第一个且不压缩 */
    private fun epub(files: Map<String, ByteArray>, mimetype: String? = "application/epub+zip"): File {
        val f = tmp(".epub")
        ZipOutputStream(f.outputStream()).use { zip ->
            if (mimetype != null) {
                val bytes = mimetype.toByteArray()
                val e = ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(e)
                zip.write(bytes)
                zip.closeEntry()
            }
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return f
    }

    private val container = """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
        <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun epub3(): File = epub(
        mapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/content.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>测试之书</dc:title><dc:creator>张三</dc:creator>
                <dc:description>&lt;p&gt;一本&lt;b&gt;测试&lt;/b&gt;书&lt;/p&gt;</dc:description></metadata>
                <manifest>
                  <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                  <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                  <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                  <item id="cover" href="images/cover.png" media-type="image/png" properties="cover-image"/>
                </manifest>
                <spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""".toByteArray(),
            "OEBPS/nav.xhtml" to """<html xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol>
                <li><a href="text/ch1.xhtml#s1">第一章 开始</a></li>
                <li><a href="text/ch1.xhtml#s2">第二章 同一文件</a></li>
                <li><a href="text/ch2.xhtml">第三章 另一文件</a><ol><li><a href="text/ch2.xhtml#sub">小节</a></li></ol></li>
                </ol></nav></body></html>""".toByteArray(),
            "OEBPS/text/ch1.xhtml" to """<html><head><script>alert(1)</script></head><body>
                <h1 id="s1">第一章 开始</h1><p onclick="x()">第一章正文。</p><img src="../images/cover.png"/>
                <h1 id="s2">第二章 同一文件</h1><p>第二章正文。</p><a href="javascript:alert(1)">链接</a>
                </body></html>""".toByteArray(),
            "OEBPS/text/ch2.xhtml" to """<html><body><h1>第三章 另一文件</h1><p>第三章正文。</p><p id="sub">小节正文</p><img src="../../../etc/passwd"/></body></html>""".toByteArray(),
            "OEBPS/images/cover.png" to png,
        )
    )

    @Test
    fun parseEpub3() {
        EpubParser(epub3()).use { e ->
            val meta = e.metadata()
            assertEquals("测试之书", meta.title)
            assertEquals("张三", meta.author)
            assertEquals("一本测试书", meta.intro)
            assertEquals("OEBPS/images/cover.png", meta.coverPath)
            val chapters = e.chapters()
            assertEquals(listOf("第一章 开始", "第二章 同一文件", "第三章 另一文件", "小节"), chapters.map { it.title })
            assertEquals(1, chapters[3].depth)
            // 同一文件中的两章按锚点分割
            val c1 = e.chapterHtml(chapters, 0) { "/img?p=$it" }
            assertTrue("第一章正文" in c1 && "第二章正文" !in c1, c1)
            assertTrue("/img?p=OEBPS/images/cover.png" in c1)
            assertFalse("script" in c1 || "alert" in c1 || "onclick" in c1, c1)
            val c2 = e.chapterHtml(chapters, 1) { "/img?p=$it" }
            assertTrue("第二章正文" in c2 && "第一章正文" !in c2, c2)
            assertFalse("javascript" in c2, c2)
            // 越界的图片路径被移除
            val c3 = e.chapterHtml(chapters, 2) { "/img?p=$it" }
            assertFalse("passwd" in c3, c3)
            // 图片接口：只允许书内的图片条目
            assertEquals("image/png", e.image("OEBPS/images/cover.png")?.second)
            assertNull(e.image("OEBPS/text/ch1.xhtml"))
            assertNull(e.image("../../etc/passwd"))
            assertNull(e.image("META-INF/container.xml"))
        }
    }

    @Test
    fun parseEpub2Ncx() {
        val f = epub(
            mapOf(
                "META-INF/container.xml" to container.toByteArray(),
                "OEBPS/content.opf" to """<package version="2.0"><metadata><title>旧格式</title></metadata>
                    <manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="a" href="a.html" media-type="application/xhtml+xml"/><item id="b" href="b.html" media-type="application/xhtml+xml"/></manifest>
                    <spine toc="ncx"><itemref idref="a"/><itemref idref="b"/></spine></package>""".toByteArray(),
                "OEBPS/toc.ncx" to """<ncx><navMap><navPoint><navLabel><text>甲</text></navLabel><content src="a.html"/></navPoint>
                    <navPoint><navLabel><text>乙</text></navLabel><content src="b.html"/></navPoint></navMap></ncx>""".toByteArray(),
                "OEBPS/a.html" to "<html><body><p>甲的内容</p></body></html>".toByteArray(),
                "OEBPS/b.html" to "<html><body><p>乙的内容</p></body></html>".toByteArray(),
            )
        )
        EpubParser(f).use { e ->
            assertEquals(listOf("甲", "乙"), e.chapters().map { it.title })
            assertTrue("乙的内容" in e.chapterHtml(e.chapters(), 1) { it })
        }
    }

    @Test
    fun rejectInvalidEpub() {
        // 扩展名是 epub 但内容不是 zip
        val fake = tmp(".epub").apply { writeText("<html>not epub</html>") }
        assertFalse(EpubParser.looksLikeEpub(fake))
        assertFailsWith<LocalBookException> { EpubParser(fake) }
        // zip 但没有 mimetype
        assertFailsWith<LocalBookException> { EpubParser(epub(mapOf("META-INF/container.xml" to container.toByteArray()), mimetype = null)) }
        // 压缩炸弹：高度可压缩的大文件
        val bomb = epub(mapOf("META-INF/container.xml" to container.toByteArray(), "big.txt" to ByteArray(20 * 1024 * 1024)))
        assertFailsWith<LocalBookException> { EpubParser(bomb) }
    }

    /** 用 App 自带的 TXT 目录规则（启用的，按序号）分章 */
    private val defaultRules = RuleDefaults.txtTocRules.filter { it.enable }.sortedBy { it.serialNumber }

    private fun split(file: File, tocUrl: String = ""): Pair<Book, List<io.legado.app.data.entities.BookChapter>> {
        val book = Book(bookUrl = "local://test.txt", name = "测试").apply { this.tocUrl = tocUrl }
        return book to TxtParser.split(file, book, defaultRules)
    }

    @Test
    fun parseTxtGbk() {
        val gbk = Charset.forName("GBK")
        val text = buildString {
            append("本书简介：这是一本测试用的书。\n")
            // 真实小说的章节通常有数千字（App 的规则判断要求章节间隔超过 1000 字）
            for (i in 1..5) append("第${i}章 标题$i\n这是第${i}章的内容。\n" + "少年抬头望向远方的灯火。".repeat(100) + "\n")
        }
        val src = tmp(".txt").apply { writeBytes(text.toByteArray(gbk)) }
        val charset = TxtParser.detectCharset(src)
        assertEquals("GB18030", charset.name())
        val dest = tmp(".txt")
        TxtParser.convert(src, dest, charset)
        val (book, chapters) = split(dest)
        // 与 App 相同：第一章之前有内容时单独作为“前言”，同时作为简介
        assertEquals(listOf("前言", "第1章 标题1", "第2章 标题2", "第3章 标题3", "第4章 标题4", "第5章 标题5"), chapters.map { it.title })
        assertTrue(book.intro!!.contains("本书简介"), book.intro)
        // 自动选中的规则保存在 tocUrl（规则 + 分隔符 + js 处理）
        assertTrue(book.tocUrl.isNotEmpty() && book.tocUrl.contains(TextFile.spaceChars))
        // 章节正文不含标题行
        val c1 = TxtParser.read(dest, TxtParser.Chapter(chapters[1].title, chapters[1].start!!, chapters[1].end!!)).split("\n")
        assertEquals("　　这是第1章的内容。", c1[0])
        assertEquals(2, c1.size)
        // 章节太短、找不到合适的目录规则时按长度分章（与 App 相同的标题）
        val short = tmp(".txt").apply { writeText("第1章 甲\n一句话。\n第2章 乙\n又一句。\n") }
        assertEquals(listOf("第1章(1)"), split(short).second.map { it.title })
        // 指定规则：按“数字 分隔符 标题名称”找不到章节
        val rule8 = RuleDefaults.txtTocRules.first { it.id == -8L }
        assertTrue(split(dest, rule8.rule + TextFile.spaceChars).second.isEmpty())
        // 超长章节按 App 的方式拆分：原章节变为卷，后面依次为 (1)(2)…
        val longFile = tmp(".txt").apply { writeText("第1章 很长\n" + "很长的一段正文。\n".repeat(20000) + "第2章 结束\n结束。\n") }
        val rule2 = RuleDefaults.txtTocRules.first { it.id == -2L }
        val longToc = split(longFile, rule2.rule + TextFile.spaceChars).second
        assertTrue(longToc.first().isVolume && longToc[1].title == "第1章 很长(1)", longToc.take(3).map { it.title }.toString())
        // UTF-8 文件
        val utf8 = tmp(".txt").apply { writeText(text) }
        assertEquals(Charsets.UTF_8, TxtParser.detectCharset(utf8))
    }

    @Test
    fun rejectBinaryTxt() {
        assertFailsWith<LocalBookException> { TxtParser.detectCharset(tmp(".txt").apply { writeBytes(png + ByteArray(100)) }) }
        assertFailsWith<LocalBookException> { TxtParser.detectCharset(tmp(".txt").apply { writeBytes("abc".toByteArray() + ByteArray(10) + "def".toByteArray()) }) }
        assertFailsWith<LocalBookException> { TxtParser.detectCharset(tmp(".txt")) }
    }
}
