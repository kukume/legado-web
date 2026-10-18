package io.legado.web.local

import me.ag2s.umdlib.domain.UmdBook
import me.ag2s.umdlib.umd.UmdReader
import java.io.File

/**
 * UMD 解析，移植自 App 的 UmdFile：章节名、正文（UTF-16，段落以 U+2029 分隔）、封面。
 * 章节地址为 umd:{序号}。整本书解压后放在 [LocalBookCache] 里，读一章不用重新解析。
 */
object UmdParser {

    const val PREFIX = "umd:"

    data class Meta(val title: String, val author: String, val kind: String?)

    /** 文件头魔数 0xde9a9b89（小端） */
    fun looksLikeUmd(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val h = input.readNBytes(4)
            h.size == 4 && h[0] == 0x89.toByte() && h[1] == 0x9B.toByte() && h[2] == 0x9A.toByte() && h[3] == 0xDE.toByte()
        }
    }.getOrDefault(false)

    private fun book(file: File): UmdBook = LocalBookCache.get(file) {
        try {
            it.inputStream().buffered().use { input -> UmdReader().read(input) }
        } catch (e: Exception) {
            throw LocalBookException("不是有效的 UMD 文件：${e.message}")
        }
    }

    fun meta(file: File): Meta {
        val h = book(file).header
        return Meta(h.title.orEmpty(), h.author.orEmpty(), h.bookType?.takeIf { it.isNotBlank() })
    }

    fun cover(file: File): ByteArray? = book(file).cover?.coverData?.takeIf { it.isNotEmpty() }

    /** 章节名列表（下标即章节序号） */
    fun titles(file: File): List<String> {
        val chapters = book(file).chapters ?: return emptyList()
        return chapters.titles.indices.map { chapters.getTitle(it).trim().take(200) }
    }

    /** 第 index 章的正文：每段一行 */
    fun content(file: File, index: Int): String {
        val chapters = book(file).chapters ?: throw LocalBookException("章节不存在")
        if (index !in chapters.titles.indices) throw LocalBookException("章节不存在")
        return chapters.getContentString(index)
            .split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}
