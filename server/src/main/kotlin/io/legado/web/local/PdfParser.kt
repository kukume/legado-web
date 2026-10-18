package io.legado.web.local

import java.io.File

/**
 * PDF：服务器不解析内容，页面在浏览器里用 pdf.js 渲染（与 App 一样，正文是一页页的图）。
 *
 * 与 App 的 PdfFile 相同：每 10 页一章，章名为“分段_N”。章节地址为 pdf:{开始页}:{结束页}（从 0 开始，不含结束页），
 * 正文为每页一个 <img src="pdfpage:页码">，由网页替换成渲染出的页面。页数由上传的浏览器读出后提交。
 */
object PdfParser {

    const val PREFIX = "pdf:"

    /** 与 App 的 PdfFile.PAGE_SIZE 相同 */
    const val PAGE_SIZE = 10

    /** 页数上限（防止提交异常的页数生成过多章节） */
    const val MAX_PAGES = 50_000

    fun looksLikePdf(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            // 规范允许 %PDF- 出现在前 1024 字节内
            String(input.readNBytes(1024), Charsets.ISO_8859_1).contains("%PDF-")
        }
    }.getOrDefault(false)

    data class Chapter(val title: String, val url: String)

    fun chapters(pages: Int): List<Chapter> {
        if (pages !in 1..MAX_PAGES) throw LocalBookException("PDF 页数无效")
        val count = (pages + PAGE_SIZE - 1) / PAGE_SIZE
        return (0 until count).map { i ->
            Chapter("分段_$i", "$PREFIX${i * PAGE_SIZE}:${minOf(pages, (i + 1) * PAGE_SIZE)}")
        }
    }

    /** 总页数（最后一章的结束页） */
    fun pagesOf(lastChapterUrl: String): Int? = lastChapterUrl.removePrefix(PREFIX).substringAfter(':').toIntOrNull()

    fun content(url: String): String {
        val range = url.removePrefix(PREFIX).split(':')
        val start = range.getOrNull(0)?.toIntOrNull() ?: throw LocalBookException("章节不存在")
        val end = range.getOrNull(1)?.toIntOrNull() ?: throw LocalBookException("章节不存在")
        if (start < 0 || end <= start || end - start > PAGE_SIZE) throw LocalBookException("章节不存在")
        // style FULL：每页占满一行（网页的图片参数，见 utils/content.ts）
        return (start until end).joinToString("\n") { "<img src=\"pdfpage:$it,{\"style\":\"FULL\"}\">" }
    }
}
