package io.legado.web.local

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.model.localBook.TextFile
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * TXT 解析：识别编码（保存为 UTF-8），按用户的 TXT 目录规则切分章节（移植自 App 的 TextFile）。
 */
object TxtParser {

    data class Chapter(val title: String, val start: Long, val end: Long)

    /** 文本里允许的控制字符：	 
 换页 
 */
    private val TEXT_CONTROLS = setOf(0x09, 0x0A, 0x0C, 0x0D)

    private val BINARY_MAGIC = listOf(
        byteArrayOf(0x50, 0x4B, 0x03, 0x04), // zip / epub / docx
        "%PDF".toByteArray(), "MZ".toByteArray(), byteArrayOf(0x7F, 0x45, 0x4C, 0x46), // pdf / exe / elf
        "GIF8".toByteArray(), byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
        "Rar!".toByteArray(), byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte()), byteArrayOf(0x1F, 0x8B.toByte()),
    )

    /** 检查是否为文本文件并识别编码；不是文本时抛出 [LocalBookException] */
    fun detectCharset(file: File): Charset {
        val head = file.inputStream().use { it.readNBytes(1024 * 1024) }
        if (head.isEmpty()) throw LocalBookException("文件是空的")
        if (BINARY_MAGIC.any { m -> head.size >= m.size && m.indices.all { head[it] == m[it] } }) {
            throw LocalBookException("这不是文本文件")
        }
        // BOM
        if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) return Charsets.UTF_8
        if (head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) return Charsets.UTF_16LE
        if (head.size >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) return Charsets.UTF_16BE
        val sample = head.copyOf(minOf(head.size, 8192))
        if (sample.any { it == 0.toByte() }) throw LocalBookException("这不是文本文件（包含二进制内容）")
        // 控制字符（制表、换行、换页、回车以外的 0x01–0x1F 和 0x7F）太多：二进制内容
        val controls = sample.count { b -> val c = b.toInt() and 0xFF; (c < 0x20 && c !in TEXT_CONTROLS) || c == 0x7F }
        if (controls > maxOf(8, sample.size / 50)) throw LocalBookException("这不是文本文件（包含二进制内容）")
        // 严格按 UTF-8 解码（末尾可能截断了一个多字节字符）
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        var len = head.size
        repeat(4) {
            try {
                decoder.reset().decode(ByteBuffer.wrap(head, 0, len))
                return Charsets.UTF_8
            } catch (_: CharacterCodingException) {
                if (len == head.size && head.size < 1024 * 1024) return@repeat
                len--
            }
        }
        // 不是 UTF-8 时按 GB18030 处理；GB18030 也解不通的比例太高，说明不是文本（或是无法识别的编码）
        val gb = Charset.forName("GB18030")
        val decoded = gb.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(head))
        val bad = (0 until decoded.length).count { decoded[it] == '\uFFFD' }
        if (bad > 4 && bad * 100 > decoded.length) throw LocalBookException("无法识别文本编码（可能不是文本文件）")
        return gb
    }

    /** 把 [src] 转为 UTF-8（无 BOM、换行统一为 \n）保存到 [dest] */
    fun convert(src: File, dest: File, charset: Charset) {
        val text = String(src.readBytes(), charset).removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        if (text.isBlank()) throw LocalBookException("文件是空的")
        dest.writeText(text, Charsets.UTF_8)
    }

    /**
     * 按目录规则切分章节（App 的 TextFile 算法）。[book].tocUrl 为空时从 [rules] 中自动选择并写回，
     * 不为空时按其中的规则切分（与 App 的“目录规则”对话框相同）。
     */
    fun split(file: File, book: Book, rules: List<TxtTocRule>): List<BookChapter> =
        TextFile.getChapterList(file, book, rules).onEach { c ->
            c.url = "txt:${c.start}:${c.end}"
            c.title = c.title.trim().take(200)
        }

    /** 读取章节正文（去掉与章节名相同的首行） */
    fun read(file: File, chapter: Chapter): String {
        val len = (chapter.end - chapter.start).toInt().coerceAtLeast(0)
        val bytes = ByteArray(len)
        RandomAccessFile(file, "r").use {
            it.seek(chapter.start)
            it.readFully(bytes)
        }
        val lines = String(bytes, Charsets.UTF_8).split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        // 第一章可能并入了开头的简介，标题行不一定在第一行
        val titleAt = lines.take(5).indexOf(chapter.title.trim())
        val body = if (titleAt >= 0) lines.filterIndexed { i, _ -> i != titleAt } else lines
        return body.joinToString("\n") { "　　$it" }
    }
}
