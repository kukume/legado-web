package io.legado.web.local

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** zip 压缩包导入的安全性：路径穿越、压缩炸弹、文件过多、嵌套压缩包都不能造成危害 */
class ZipBookExtractorTest {
    private val base = File(System.getProperty("java.io.tmpdir"), "legado-zip-test-${System.nanoTime()}").apply { mkdirs() }
    private val work = File(base, "work")

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    private val book = "第一章 开始\n正文内容，这是一本用来测试的书。\n".repeat(200) // 远大于 2KB

    private fun zip(name: String, block: ZipOutputStream.() -> Unit): File {
        val f = File(base, name)
        ZipOutputStream(f.outputStream(), Charset.forName("UTF-8")).use { it.block() }
        return f
    }

    private fun ZipOutputStream.add(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name)); write(bytes); closeEntry()
    }

    private class Got(val name: String, val parent: File, val size: Long)

    private fun run(archive: File, maxFile: Long = 50L * 1024 * 1024, maxTotal: Long = 1024L * 1024 * 1024): Pair<List<Got>, List<ZipBookExtractor.Skipped>> {
        val got = ArrayList<Got>()
        val skipped = runBlocking {
            ZipBookExtractor(archive, work, maxFile, maxTotal).forEachBook(
                block = { name, file -> got += Got(name, file.parentFile.canonicalFile, file.length()) },
                onSkip = { it.message },
            )
        }
        return got to skipped
    }

    @Test fun pathTraversalNamesAreNeverUsedAsPaths() {
        val archive = zip("slip.zip") {
            add("../../evil.txt", book.toByteArray())
            add("/abs/evil2.txt", book.toByteArray())
            add("..\\..\\evil3.txt", book.toByteArray())
        }
        val (got, _) = run(archive)
        assertEquals(listOf("evil.txt", "evil2.txt", "evil3.txt"), got.map { it.name })
        // 都解压在工作目录里（随机文件名），没有写到工作目录外面
        assertTrue(got.all { it.parent == work.canonicalFile })
        assertFalse(File(base, "evil.txt").exists() || File(base.parentFile, "evil.txt").exists())
        assertTrue(base.walkTopDown().none { it.name.startsWith("evil") }, "不应出现以包内文件名命名的文件")
        // 回调后临时文件已删除
        assertTrue(work.listFiles().isNullOrEmpty())
    }

    @Test fun zipBombIsStopped() {
        // 200MB 的 0，压缩后只有约 200KB
        val archive = zip("bomb.zip") {
            putNextEntry(ZipEntry("bomb.txt"))
            val chunk = ByteArray(1024 * 1024)
            repeat(200) { write(chunk) }
            closeEntry()
            add("ok.txt", book.toByteArray())
        }
        assertTrue(archive.length() < 2 * 1024 * 1024)
        val (got, skipped) = run(archive)
        assertEquals(listOf("ok.txt"), got.map { it.name })
        assertTrue(skipped.single { it.name == "bomb.txt" }.reason.let { "压缩比" in it || "过大" in it })
        assertTrue(work.listFiles().isNullOrEmpty(), "炸弹的部分解压文件也要删除")
    }

    @Test fun perFileAndTotalLimits() {
        val big = ByteArray(300 * 1024) { (it % 200 + 32).toByte() } // 300KB 文本
        val archive = zip("limits.zip") {
            add("a.txt", big); add("b.txt", big); add("c.txt", big)
        }
        // 单个文件上限 200KB：全部跳过
        assertEquals(3, run(archive, maxFile = 200L * 1024).second.count { "过大" in it.reason })
        // 总量上限 700KB：前两本导入，第三本跳过
        val (got, skipped) = run(archive, maxTotal = 700L * 1024)
        assertEquals(listOf("a.txt", "b.txt"), got.map { it.name })
        assertEquals("c.txt", skipped.single().name)
    }

    @Test fun tooManyEntries() {
        val archive = zip("many.zip") { repeat(ZipBookExtractor.MAX_ENTRIES + 1) { add("f$it.dat", byteArrayOf(1)) } }
        assertFailsWith<LocalBookException> { run(archive) }
    }

    @Test fun nestedArchivesAreNotExtracted() {
        val inner = zip("inner.zip") { add("deep.txt", book.toByteArray()) }
        val archive = zip("outer.zip") {
            add("inner.zip", inner.readBytes())
            add("x.rar", byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00) + ByteArray(100))
        }
        val (got, skipped) = run(archive)
        assertTrue(got.isEmpty())
        assertEquals(setOf("inner.zip", "x.rar"), skipped.filter { "不会再解压" in it.reason }.map { it.name }.toSet())
    }

    @Test fun typicalDownloadArchive() {
        val epub = zip("b.epub") {
            add("mimetype", "application/epub+zip".toByteArray())
            add("META-INF/container.xml", """<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""".toByteArray())
            add("content.opf", """<package><manifest><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>""".toByteArray())
            add("c.xhtml", "<html><body><p>正文</p></body></html>".toByteArray())
        }
        val archive = zip("download.zip") {
            add("书/小说.txt", book.toByteArray(Charset.forName("GBK")))
            add("书/另一本.epub", epub.readBytes())
            add("书/封面.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(5000))
            add("书/下载说明.txt", "本书来自某网站".toByteArray())
            add("__MACOSX/书/._小说.txt", ByteArray(5000))
            add("书/扫描版.pdf", "%PDF-1.4\n".toByteArray() + ByteArray(5000))
        }
        val (got, skipped) = run(archive)
        assertEquals(setOf("小说.txt", "另一本.epub"), got.map { it.name }.toSet())
        assertEquals(mapOf("下载说明.txt" to "文件太小，可能是说明文件", "扫描版.pdf" to "压缩包里的 PDF 请解压后单独上传"), skipped.associate { it.name to it.reason })
    }

    @Test fun detectsRarAnd7zByHeader() {
        val rar = File(base, "a.txt").apply { writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)) }
        val sevenZ = File(base, "b.zip").apply { writeBytes(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)) }
        assertTrue(ArchiveFormat.isRar(rar)); assertFalse(ArchiveFormat.is7z(rar))
        assertTrue(ArchiveFormat.is7z(sevenZ)); assertFalse(ArchiveFormat.isRar(sevenZ))
    }
}
