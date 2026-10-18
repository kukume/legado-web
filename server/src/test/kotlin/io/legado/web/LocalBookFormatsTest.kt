package io.legado.web

import io.legado.web.local.LocalBookCache
import io.legado.web.local.MobiParser
import io.legado.web.local.PdfParser
import io.legado.web.local.UmdParser
import me.ag2s.umdlib.domain.UmdBook
import me.ag2s.umdlib.domain.UmdCover
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UMD、PDF 的解析（MOBI / AZW3 没有能在测试里生成的工具，用真实文件手动验证） */
class LocalBookFormatsTest {

    private fun tmp(suffix: String) = File.createTempFile("lbfmt-", suffix).apply { deleteOnExit() }

    /** 1x1 的 JPEG */
    private val jpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 'J'.code.toByte(), 'F'.code.toByte(),
        'I'.code.toByte(), 'F'.code.toByte(), 0, 1, 1, 0, 0, 1, 0, 1, 0, 0, 0xFF.toByte(), 0xD9.toByte(),
    )

    @Test
    fun parseUmd() {
        // 用 UMD 库自带的生成功能写一本书，再用解析器读出来
        val umd = UmdBook()
        umd.header.title = "测试 UMD"
        umd.header.author = "张三"
        umd.header.bookType = "玄幻"
        umd.chapters.addChapter("第一章 开始", "第一段正文。 第二段正文。")
        umd.chapters.addChapter("第二章 继续", "第二章的内容。")
        umd.cover = UmdCover(jpeg)
        val file = tmp(".umd")
        file.outputStream().use { umd.buildUmd(it) }

        assertTrue(UmdParser.looksLikeUmd(file))
        assertEquals("测试 UMD", UmdParser.meta(file).title)
        assertEquals("张三", UmdParser.meta(file).author)
        assertEquals(listOf("第一章 开始", "第二章 继续"), UmdParser.titles(file))
        assertEquals("第一段正文。\n第二段正文。", UmdParser.content(file, 0))
        assertEquals("第二章的内容。", UmdParser.content(file, 1))
        assertContentEquals(jpeg, UmdParser.cover(file))
        LocalBookCache.evict(file)

        // 不是 UMD
        val txt = tmp(".umd").apply { writeText("hello") }
        assertFalse(UmdParser.looksLikeUmd(txt))
        assertFalse(MobiParser.looksLikeMobi(txt))
        assertFalse(PdfParser.looksLikePdf(txt))
    }

    @Test
    fun pdfChapters() {
        // 与 App 相同：每 10 页一章，章名“分段_N”
        val chapters = PdfParser.chapters(25)
        assertEquals(listOf("分段_0", "分段_1", "分段_2"), chapters.map { it.title })
        assertEquals(listOf("pdf:0:10", "pdf:10:20", "pdf:20:25"), chapters.map { it.url })
        assertEquals(25, PdfParser.pagesOf(chapters.last().url))
        val content = PdfParser.content("pdf:20:25").split("\n")
        assertEquals(5, content.size)
        assertEquals("<img src=\"pdfpage:20,{\"style\":\"FULL\"}\">", content[0])
        assertFailsWith<Exception> { PdfParser.chapters(0) }
        assertFailsWith<Exception> { PdfParser.content("pdf:0:100") }
        assertTrue(PdfParser.looksLikePdf(tmp(".pdf").apply { writeText("%PDF-1.7\n...") }))
    }
}
