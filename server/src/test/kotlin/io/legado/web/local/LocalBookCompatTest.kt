package io.legado.web.local

import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private data class Q(val a: String, val b: String, val c: Boolean, val d: String)

/** 各种不规范但 App（epublib、ICU 编码识别）能打开的文件，我们也要能识别 */
class LocalBookCompatTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "legado-compat-test").apply { deleteRecursively(); mkdirs() }

    private val opf = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
<manifest><item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xml"/></manifest>
<spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>"""
    private val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
    private fun xhtml(t: String) = "<html><head><title>$t</title></head><body><h1>$t</h1><p>正文内容 $t 一二三四五六七八九十</p></body></html>"

    private fun epub(name: String, entries: List<Pair<String, String>>, nameCharset: Charset = Charsets.UTF_8): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream(), nameCharset).use { z ->
            for ((n, c) in entries) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
        }
        return f
    }

    private fun base(opfText: String = opf, opfPath: String = "OEBPS/content.opf") = listOf(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to container.replace("OEBPS/content.opf", opfPath),
        opfPath to opfText,
        "OEBPS/Text/c1.xhtml" to xhtml("第一章"),
        "OEBPS/Text/c2.xhtml" to xhtml("第二章"),
    )

    private fun tryEpub(label: String, f: File) {
        assertTrue(EpubParser.looksLikeEpub(f), "$label：应识别为 EPUB")
        EpubParser(f).use { p ->
            val ch = p.chapters()
            assertEquals(2, ch.size, "$label：章节数")
            assertTrue(org.jsoup.Jsoup.parse(p.chapterHtml(ch, 0) { it }).text().contains("第一章"), "$label：正文")
        }
    }

    @Test fun recognizesLikeApp() {
        tryEpub("正常", epub("ok.epub", base()))
        tryEpub("缺少 mimetype", epub("nomime.epub", base().filter { it.first != "mimetype" }))
        tryEpub("mimetype 带 BOM", epub("bom.epub", base().map { if (it.first == "mimetype") it.first to "﻿application/epub+zip" else it }))
        tryEpub("缺少 container.xml", epub("nocontainer.epub", base().filter { it.first != "META-INF/container.xml" }))
        tryEpub("container 指向错误的 opf", epub("badcontainer.epub", base().map { if (it.first == "META-INF/container.xml") it.first to container.replace("OEBPS/content.opf", "content.opf") else it }))
        tryEpub("没有 spine", epub("nospine.epub", base(opf.replace(Regex("<spine>.*</spine>"), ""))))
        tryEpub("spine 的 idref 写成 href", epub("idrefhref.epub", base(opf.replace("idref=\"c1\"", "idref=\"Text/c1.xhtml\"").replace("idref=\"c2\"", "idref=\"Text/c2.xhtml\""))))
        tryEpub("manifest 大小写与文件不一致", epub("case.epub", base(opf.replace("Text/c1.xhtml", "text/C1.xhtml"))))
        tryEpub("zip 内路径用反斜杠", epub("backslash.epub", base().map { it.first.replace('/', '\\') to it.second }.map { if (it.first == "META-INF\\container.xml") "META-INF/container.xml" to it.second else it }))
        tryEpub("zip 文件名为 GBK 编码", epub("gbkname.epub", base().map { if (it.first.startsWith("OEBPS/Text")) it.first.replace("c1", "第一章").replace("c2", "第二章") to it.second else it }
            .map { if (it.first == "OEBPS/content.opf") it.first to opf.replace("Text/c1.xhtml", "Text/第一章.xhtml").replace("Text/c2.xhtml", "Text/第二章.xhtml") else it }, Charset.forName("GBK")))

        // TXT 编码
        val simp = "第一章 开始\n这是正文内容，用来测试编码识别是否正确。他们说话的时候，天已经黑了。\n第二章 继续\n更多的正文内容在这里。\n".repeat(30)
        val trad = "第一章 開始\n這是正文內容，用來測試編碼識別是否正確。他們說話的時候，天已經黑了。\n第二章 繼續\n更多的正文內容在這裡。\n".repeat(30)
        val ru = "Глава первая\nЭто текст книги на русском языке, чтобы проверить кодировку.\n".repeat(30)
        for ((label, cs, bom, txt) in listOf(
            Q("UTF-8", "UTF-8", false, simp), Q("GBK 简体", "GBK", false, simp), Q("GBK 繁体", "GBK", false, trad),
            Q("Big5 繁体", "Big5", false, trad), Q("UTF-16LE 无 BOM", "UTF-16LE", false, simp),
            Q("UTF-16BE 无 BOM", "UTF-16BE", false, simp), Q("UTF-16LE 带 BOM", "UTF-16LE", true, simp),
            Q("俄文 windows-1251", "windows-1251", false, ru),
        )) {
            val f = File(dir, "t-$cs-$label.txt".replace(' ', '_'))
            val bytes = txt.toByteArray(Charset.forName(cs))
            f.writeBytes(if (bom) byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + bytes else bytes)
            val c = TxtParser.detectCharset(f)
            assertEquals(txt, String(f.readBytes(), c).removePrefix("﻿"), "TXT $label：识别为 $c 后乱码")
        }
        // 二进制文件仍应被拒绝
        val rnd = java.util.Random(1)
        for ((label, gen) in listOf<Pair<String, (Int) -> Byte>>(
            "随机二进制" to { _ -> rnd.nextInt(256).toByte() },
            "奇数位多 0 的二进制" to { i -> if (i % 2 == 1 && rnd.nextInt(3) > 0) 0 else rnd.nextInt(256).toByte() },
        )) {
            val f = File(dir, "bin-$label.txt")
            f.writeBytes(ByteArray(200_000) { gen(it) })
            assertFailsWith<LocalBookException>("$label 应被拒绝") { TxtParser.detectCharset(f) }
        }
    }
}
