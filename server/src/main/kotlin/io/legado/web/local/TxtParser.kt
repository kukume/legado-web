package io.legado.web.local

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.lib.icu4j.CharsetDetector
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
        if (sample.any { it == 0.toByte() }) {
            // 没有 BOM 的 UTF-16（App 用 ICU 能识别）：0 字节主要在奇数位（LE）或偶数位（BE），且能完整解码
            return utf16WithoutBom(head) ?: throw LocalBookException("这不是文本文件（包含二进制内容）")
        }
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
        // 不是 UTF-8：与 App 一样用 ICU 识别（繁体 Big5、日文、俄文等）。只采用把握大的结果——
        // 文本较短时 ICU 可能误判，这时仍按 GB18030（原来的做法，GBK 文本不受影响）
        icuCharset(head)?.let { return it }
        // 其余按 GB18030 处理；GB18030 也解不通的比例太高，说明不是文本（或是无法识别的编码）
        val gb = Charset.forName("GB18030")
        val decoded = gb.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(head))
        val bad = (0 until decoded.length).count { decoded[it] == '\uFFFD' }
        if (bad > 4 && bad * 100 > decoded.length) throw LocalBookException("无法识别文本编码（可能不是文本文件）")
        return gb
    }

    /** 中日韩多字节编码：ICU 把握在 50 以上才采用 */
    private val CJK_CHARSETS = setOf("GB18030", "Big5", "Shift_JIS", "EUC-JP", "EUC-KR")

    private fun icuCharset(bytes: ByteArray): Charset? {
        val best = runCatching { CharsetDetector().setText(bytes).detect() }.getOrNull() ?: return null
        val name = best.name ?: return null
        val accept = when {
            name in CJK_CHARSETS -> best.confidence >= 50
            // 西里尔文、东欧等单字节编码：ICU 对乱码也常给出 ISO-8859-1 / windows-1252，西欧编码要求更高的把握
            name.startsWith("ISO-8859-1") || name == "windows-1252" -> best.confidence >= 60
            name.startsWith("windows-") || name.startsWith("ISO-8859-") || name.startsWith("KOI8") -> best.confidence >= 40
            else -> false // UTF-8 / UTF-16 已在前面处理
        }
        if (!accept) return null
        // GB18030 就是默认的兜底，交给后面的检查（能识别出明显不是文本的文件）
        if (name == "GB18030") return null
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    private fun utf16WithoutBom(head: ByteArray): Charset? {
        val sample = head.copyOf(minOf(head.size, 64 * 1024) and 1.inv())
        var even = 0
        var odd = 0
        for (i in sample.indices) if (sample[i] == 0.toByte()) { if (i % 2 == 0) even++ else odd++ }
        // 英文、数字、换行在 UTF-16LE 里是 “字符 00”，0 字节集中在奇数位；中文里像 “一”（U+4E00）这样的字也会
        // 在另一侧产生少量 0，所以按哪一侧明显更多来判断，最后以能否完整解码为准
        val cs = when {
            odd >= 2 * even && odd > 0 -> Charsets.UTF_16LE
            even >= 2 * odd && even > 0 -> Charsets.UTF_16BE
            else -> return null
        }
        val decoder = cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        // 末尾可能截断了一个代理对，少解码 2 字节再试一次
        for (len in listOf(sample.size, sample.size - 2)) {
            if (len <= 0) break
            try {
                val text = decoder.reset().decode(ByteBuffer.wrap(sample, 0, len))
                // 解出来要像文本：控制字符（换行、制表除外）、私用区、未分配的字符很少（二进制数据按 UTF-16 解出来这些会很多）
                val bad = text.count { c ->
                    if (c == '\n' || c == '\r' || c == '\t' || c == '\u000C') return@count false
                    when (Character.getType(c).toByte()) {
                        Character.CONTROL, Character.PRIVATE_USE, Character.UNASSIGNED, Character.SURROGATE -> true
                        else -> false
                    }
                }
                return if (bad <= maxOf(8, text.length / 50)) cs else null
            } catch (_: CharacterCodingException) {
            }
        }
        return null
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
