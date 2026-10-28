package io.legado.web.service

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.utils.HtmlFormatter
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.ForbiddenException
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.UserPrincipal
import io.legado.web.config.AppSettings
import io.legado.web.local.EpubParser
import io.legado.web.local.LocalBookCache
import io.legado.web.local.LocalBookException
import io.legado.web.local.MobiParser
import io.legado.web.local.PdfParser
import io.legado.web.local.TxtParser
import io.legado.web.local.UmdParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.util.UUID

/**
 * 本地书籍（用户上传的 EPUB / TXT / MOBI / AZW3 / AZW / UMD / PDF）。
 *
 * - 文件按用户保存在 数据目录/local_books/{userId}/ 下，文件名由服务器生成（UUID），不使用上传的文件名；
 *   MOBI、UMD 的封面、浏览器渲染的 PDF 第一页保存为同名的 .cover 文件
 * - 书籍记录：origin = loc_book（与 App 相同），bookUrl = local://{文件名}
 * - 章节地址：EPUB 为 epub:{zip 内路径}#{开始锚点}|{结束锚点}，TXT 为 txt:{开始字节}:{结束字节}（UTF-8 文件），
 *   MOBI 为 mobi:{序号}:{href}，UMD 为 umd:{序号}，PDF 为 pdf:{开始页}:{结束页}（页面在浏览器里渲染）
 */
class LocalBookService(
    private val appSettings: AppSettings,
    private val settings: SettingService,
) {
    /** 由 Application 设置（ShelfService 也依赖本服务读取正文） */
    lateinit var shelfService: ShelfService
    lateinit var tocRules: TxtTocRuleService

    data class LocalBookInfo(val enabled: Boolean, val maxMb: Int, val quotaMb: Int, val usedBytes: Long)

    companion object {
        const val SCHEME = "local://"

        fun isLocal(origin: String?) = origin == BookType.localTag

        private val MOBI_EXT = setOf("mobi", "azw3", "azw")

        /** 服务器生成的文件名（UUID + 格式） */
        private val NAME_RE = Regex("""[0-9a-f\-]{36}\.(epub|txt|umd|mobi|azw3|azw|pdf)""")

        /** 浏览器上传的 PDF 封面（第一页）上限 */
        private const val MAX_COVER_BYTES = 3 * 1024 * 1024

        /** 正文里需要传入全部章节地址的格式（EPUB 按下一章的锚点截止，MOBI 读到下一章为止） */
        fun needsAllUrls(chapterUrl: String) = chapterUrl.startsWith("epub:") || chapterUrl.startsWith(MobiParser.PREFIX)
    }

    private val root: File get() = File(appSettings.dataDir, "local_books")

    private fun userDir(userId: Long) = File(root, userId.toString())

    /** bookUrl -> 文件（只接受服务器生成的文件名，防止路径穿越） */
    fun fileOf(userId: Long, bookUrl: String): File {
        val name = bookUrl.removePrefix(SCHEME)
        if (!NAME_RE.matches(name)) throw NotFoundException("本地书籍文件无效")
        return File(userDir(userId), name)
    }

    private fun coverFile(file: File) = File(file.parentFile, file.name + ".cover")

    private fun extOf(file: File) = file.name.substringAfterLast('.').lowercase()

    // ---------------- 配置与配额 ----------------

    private fun enabled() = settings.get(SettingService.LOCAL_BOOK_ENABLED) != "false"
    private fun maxBytes() = settings.getInt(SettingService.LOCAL_BOOK_MAX_MB).coerceIn(1, 1024) * 1024L * 1024
    private fun quotaBytes() = settings.getInt(SettingService.LOCAL_BOOK_QUOTA_MB).coerceIn(1, 1024 * 1024) * 1024L * 1024

    fun usedBytes(userId: Long): Long = userDir(userId).listFiles()?.sumOf { it.length() } ?: 0L

    fun info(user: UserPrincipal) = LocalBookInfo(
        enabled(), settings.getInt(SettingService.LOCAL_BOOK_MAX_MB), settings.getInt(SettingService.LOCAL_BOOK_QUOTA_MB), usedBytes(user.id),
    )

    /** 上传前的检查（请求体大小限制由路由按 [uploadLimit] 执行） */
    fun uploadLimit(user: UserPrincipal): Long {
        if (!enabled()) throw ForbiddenException("管理员已关闭本地书籍上传")
        return maxBytes()
    }

    // ---------------- 导入 ----------------

    data class ImportOptions(
        val groupId: Long = 0,
        /** 从备份恢复时保留原来的文件名（bookUrl 不变） */
        val keepName: String? = null,
        /** PDF 的页数（服务器不解析 PDF，由浏览器用 pdf.js 读出后提交） */
        val pdfPages: Int? = null,
    )

    /**
     * 导入上传的文件：检查格式与配额，解析目录，保存文件和书籍记录。[upload] 为临时文件，调用方负责删除。
     */
    suspend fun import(user: UserPrincipal, upload: File, originalName: String, options: ImportOptions = ImportOptions()): BookDto =
        withContext(Dispatchers.IO) {
            if (!enabled()) throw ForbiddenException("管理员已关闭本地书籍上传")
            val size = upload.length()
            if (size == 0L) throw BadRequestException("文件是空的")
            if (size > maxBytes()) throw BadRequestException("文件过大（最大 ${settings.getInt(SettingService.LOCAL_BOOK_MAX_MB)}MB）")
            val used = usedBytes(user.id)
            if (used + size > quotaBytes()) {
                throw BadRequestException("本地书籍空间不足（已用 ${used / 1024 / 1024}MB / ${settings.getInt(SettingService.LOCAL_BOOK_QUOTA_MB)}MB）")
            }
            val displayName = originalName.substringAfterLast('/').substringAfterLast('\\').take(200).ifBlank { "未命名" }
            val ext = displayName.substringAfterLast('.', "").lowercase()
            // 以文件内容为准判断格式；扩展名只用来在内容不明确时区分
            val kind = when {
                EpubParser.looksLikeEpub(upload) -> "epub"
                PdfParser.looksLikePdf(upload) -> "pdf"
                MobiParser.looksLikeMobi(upload) -> if (ext in MOBI_EXT) ext else "mobi"
                UmdParser.looksLikeUmd(upload) -> "umd"
                // zip 但不是 EPUB（没有 mimetype）：普通压缩包、Office 文档、漫画包等
                EpubParser.isZip(upload) -> throw BadRequestException(
                    if (ext == "epub") "不是有效的 EPUB 文件（缺少 mimetype）"
                    else "这是 zip 压缩包，不是电子书（只支持 EPUB、TXT、MOBI、AZW3、AZW、UMD、PDF）"
                )
                ext == "epub" || ext == "pdf" || ext == "umd" || ext in MOBI_EXT ->
                    throw BadRequestException("不是有效的 ${ext.uppercase()} 文件（文件头不正确）")
                ext == "txt" || ext.isEmpty() -> "txt"
                else -> throw BadRequestException("只支持 EPUB、TXT、MOBI、AZW3、AZW、UMD、PDF 格式")
            }
            if (kind == "pdf" && options.pdfPages == null) throw BadRequestException("缺少 PDF 页数，请刷新页面后重新上传")
            val dir = userDir(user.id).apply { mkdirs() }
            val fileName = options.keepName?.takeIf { NAME_RE.matches(it) && it.endsWith(".$kind") }
                ?: "${UUID.randomUUID()}.$kind"
            val dest = File(dir, fileName)
            val rules = if (kind == "txt") tocRules.enabled(user.id) else emptyList()
            try {
                val parsed = try {
                    parse(kind, upload, dest, displayName, rules, options.pdfPages)
                } catch (e: LocalBookException) {
                    throw BadRequestException(e.message ?: "文件格式错误")
                }
                shelfService.addLocalBook(user, parsed.first, parsed.second, options.groupId, parsed.third)
            } catch (e: Throwable) {
                deleteFile(dest)
                throw e
            }
        }

    /**
     * 解析文件：返回 (书籍, 章节, 封面)。封面为 EPUB 内的图片路径，或其他格式已保存了 .cover 文件时为 “file”。
     * TXT 转为 UTF-8 保存到 [dest]，其他格式原样保存
     */
    private fun parse(
        kind: String, src: File, dest: File, displayName: String, rules: List<TxtTocRule>, pdfPages: Int?,
    ): Triple<Book, List<BookChapter>, String?> {
        val bookUrl = SCHEME + dest.name
        val baseName = displayName.substringBeforeLast('.')
        fun newBook(name: String, author: String = "", intro: String? = null, kind: String? = null) = Book(
            bookUrl = bookUrl, origin = BookType.localTag, originName = displayName,
            name = name.ifBlank { baseName }, author = author, intro = intro, kind = kind,
            type = BookType.text or BookType.local,
        )
        fun saveCover(bytes: ByteArray?): String? {
            if (bytes == null || bytes.isEmpty() || imageType(bytes) == null) return null
            coverFile(dest).writeBytes(bytes)
            return "file"
        }
        return when (kind) {
            "epub" -> {
                src.copyTo(dest, overwrite = true)
                EpubParser(dest).use { epub ->
                    val meta = epub.metadata()
                    val chapters = epub.chapters()
                    if (chapters.isEmpty()) throw LocalBookException("EPUB 中没有章节")
                    Triple(newBook(meta.title ?: baseName, meta.author.orEmpty(), meta.intro), epubChapters(chapters, bookUrl), meta.coverPath)
                }
            }
            "umd" -> {
                src.copyTo(dest, overwrite = true)
                val meta = UmdParser.meta(dest)
                val titles = UmdParser.titles(dest)
                if (titles.isEmpty()) throw LocalBookException("UMD 中没有章节")
                Triple(newBook(meta.title, meta.author, kind = meta.kind), umdChapters(titles, bookUrl), saveCover(UmdParser.cover(dest)))
            }
            in MOBI_EXT -> {
                src.copyTo(dest, overwrite = true)
                val meta = MobiParser.meta(dest)
                val chapters = MobiParser.chapters(dest)
                if (chapters.isEmpty()) throw LocalBookException("书中没有章节")
                Triple(newBook(meta.title, meta.author, meta.intro), mobiChapters(chapters, bookUrl), saveCover(MobiParser.cover(dest)))
            }
            "pdf" -> {
                src.copyTo(dest, overwrite = true)
                // 封面由浏览器渲染第一页后另外上传
                Triple(newBook(baseName), pdfChapters(pdfPages ?: 0, bookUrl), null)
            }
            else -> {
                val charset = TxtParser.detectCharset(src)
                TxtParser.convert(src, dest, charset)
                val (name, author) = parseTxtName(baseName)
                val book = newBook(name, author)
                // 自动选择目录规则，选中的规则保存在 book.tocUrl（与 App 相同）
                val list = TxtParser.split(dest, book, rules)
                Triple(book, list, null)
            }
        }
    }

    private fun epubChapters(chapters: List<EpubParser.Chapter>, bookUrl: String) = chapters.mapIndexed { i, c ->
        BookChapter(
            url = "epub:${c.href}#${c.anchor.orEmpty()}|${c.endAnchor.orEmpty()}",
            title = ("　".repeat(c.depth.coerceAtMost(3)) + c.title).take(200),
            bookUrl = bookUrl, index = i,
        )
    }

    private fun umdChapters(titles: List<String>, bookUrl: String) = titles.mapIndexed { i, title ->
        BookChapter(url = "${UmdParser.PREFIX}$i", title = title.ifBlank { "第 ${i + 1} 章" }, bookUrl = bookUrl, index = i)
    }

    private fun mobiChapters(chapters: List<MobiParser.Chapter>, bookUrl: String) = chapters.mapIndexed { i, c ->
        BookChapter(url = c.url, title = c.title.ifBlank { "第 ${i + 1} 章" }, isVolume = c.isVolume, bookUrl = bookUrl, index = i)
    }

    private fun pdfChapters(pages: Int, bookUrl: String) = PdfParser.chapters(pages).mapIndexed { i, c ->
        BookChapter(url = c.url, title = c.title, bookUrl = bookUrl, index = i)
    }

    /** 从文件名提取书名和作者：《书名》作者：xxx、书名 作者：xxx、书名-作者 等 */
    private fun parseTxtName(name: String): Pair<String, String> {
        Regex("""《(.+?)》.*?(?:作者[:：]\s*)?([^\s《》]*)$""").find(name)?.let { m ->
            return m.groupValues[1].trim() to m.groupValues[2].removePrefix("作者").trim(':', '：', ' ')
        }
        Regex("""^(.+?)\s*(?:作者[:：]|by\s)\s*(.+)$""", RegexOption.IGNORE_CASE).find(name)?.let { m ->
            return m.groupValues[1].trim() to m.groupValues[2].trim()
        }
        return name.trim() to ""
    }

    /** 重新解析的结果：TXT 会更新所用的目录规则（tocUrl）、字数和前言简介 */
    data class Reparsed(val chapters: List<BookChapter>, val tocUrl: String? = null, val wordCount: String? = null)

    /**
     * 重新解析目录（“更新目录”）。TXT：[tocUrl] 为 null 时沿用书籍当前的目录规则，
     * 为空字符串时重新自动选择，否则按指定的规则（规则 + TextFile.spaceChars + js 处理）切分。
     * PDF 按 [pdfPages]（当前目录记录的页数）重新生成。
     */
    suspend fun reparse(userId: Long, book: Book, tocUrl: String? = null, pdfPages: Int? = null): Reparsed = withContext(Dispatchers.IO) {
        val bookUrl = book.bookUrl
        val file = fileOf(userId, bookUrl)
        if (!file.isFile) throw NotFoundException("本地书籍文件不存在")
        try {
            when (val ext = extOf(file)) {
                "epub" -> EpubParser(file).use { epub -> Reparsed(epubChapters(epub.chapters(), bookUrl)) }
                "umd" -> {
                    LocalBookCache.evict(file)
                    Reparsed(umdChapters(UmdParser.titles(file), bookUrl))
                }
                in MOBI_EXT -> {
                    LocalBookCache.evict(file)
                    Reparsed(mobiChapters(MobiParser.chapters(file), bookUrl))
                }
                "pdf" -> Reparsed(pdfChapters(pdfPages ?: throw BadRequestException("PDF 的目录按页数生成，不需要重新解析"), bookUrl))
                else -> {
                    check(ext == "txt")
                    // TXT 上传时已转为 UTF-8，直接按规则切分
                    val rules = tocRules.enabled(userId)
                    val b = Book(bookUrl = bookUrl, name = book.name, author = book.author, origin = book.origin, originName = book.originName)
                    b.tocUrl = tocUrl ?: book.tocUrl
                    val chapters = TxtParser.split(file, b, rules)
                    // 与 App 相同，指定的规则一个章节也没匹配到时视为目录为空；这里不修改原来的目录
                    if (chapters.isEmpty()) throw BadRequestException("按这个目录规则没有找到章节，目录未修改")
                    Reparsed(chapters, b.tocUrl, b.wordCount)
                }
            }
        } catch (e: LocalBookException) {
            throw BadRequestException(e.message ?: "文件格式错误")
        }
    }

    // ---------------- 正文与资源 ----------------

    /** 章节正文（与网络书籍相同的格式：每段一行，图片为 <img src="...">） */
    suspend fun content(userId: Long, bookId: Long, bookUrl: String, chapterUrl: String, allChapterUrls: List<String>, index: Int, title: String): String =
        withContext(Dispatchers.IO) {
            val file = fileOf(userId, bookUrl)
            if (!file.isFile) throw NotFoundException("本地书籍文件不存在，请重新上传")
            try {
                when {
                    chapterUrl.startsWith("txt:") -> {
                        val (s, e) = chapterUrl.removePrefix("txt:").split(':').map { it.toLong() }
                        TxtParser.read(file, TxtParser.Chapter(title.trim(), s, e))
                    }
                    chapterUrl.startsWith("epub:") -> {
                        val chapters = allChapterUrls.map { u ->
                            val v = u.removePrefix("epub:")
                            val href = v.substringBefore('#')
                            val anchors = v.substringAfter('#', "")
                            EpubParser.Chapter("", href, anchors.substringBefore('|').ifEmpty { null }, anchors.substringAfter('|', "").ifEmpty { null }, 0)
                        }
                        val html = EpubParser(file).use { epub ->
                            epub.chapterHtml(chapters, index) { path -> imageUrl(bookId, path) }
                        }
                        nonBlankLines(HtmlFormatter.formatKeepImg(html))
                    }
                    chapterUrl.startsWith(MobiParser.PREFIX) -> {
                        val html = MobiParser.content(file, chapterUrl, allChapterUrls.getOrNull(index + 1))
                        // 书内图片引用（recindex:N、kindle:embed:…）换成图片接口
                        val withImages = imgSrcRegex.replace(html) { m ->
                            val src = m.groupValues[1]
                            if (src.startsWith("/") || src.startsWith("http")) m.value else m.value.replace(src, imageUrl(bookId, src))
                        }
                        nonBlankLines(withImages)
                    }
                    chapterUrl.startsWith(UmdParser.PREFIX) -> {
                        val i = chapterUrl.removePrefix(UmdParser.PREFIX).toIntOrNull() ?: throw NotFoundException("章节不存在")
                        UmdParser.content(file, i)
                    }
                    chapterUrl.startsWith(PdfParser.PREFIX) -> PdfParser.content(chapterUrl)
                    else -> throw NotFoundException("章节不存在")
                }
            } catch (e: LocalBookException) {
                throw NotFoundException(e.message ?: "章节不存在")
            }
        }

    private val imgSrcRegex = Regex("""<img src="([^"]+)"""")

    /** 去掉空行（与章节名重复的首行由正文处理统一删除，见 ReplaceRuleService.apply） */
    private fun nonBlankLines(text: String): String = text.split('\n').filter { it.isNotBlank() }.joinToString("\n")

    fun imageUrl(bookId: Long, path: String) = "/api/shelf/$bookId/local/image?p=${URLEncoder.encode(path, "UTF-8")}"

    fun coverUrl(bookId: Long) = "/api/shelf/$bookId/local/cover"

    /** 按文件头判断图片类型（只接受常见图片格式） */
    private fun imageType(b: ByteArray): String? = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
        b.size >= 6 && String(b, 0, 6, Charsets.ISO_8859_1).let { it == "GIF87a" || it == "GIF89a" } -> "image/gif"
        b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
        b.size >= 2 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> "image/bmp"
        else -> null
    }

    /** 书内图片：EPUB（只允许图片类型的条目）、MOBI / AZW3（书内的图片资源） */
    suspend fun image(userId: Long, bookUrl: String, path: String): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val file = fileOf(userId, bookUrl)
        if (!file.isFile) throw NotFoundException("图片不存在")
        try {
            when (extOf(file)) {
                "epub" -> EpubParser(file).use { it.image(path) }
                in MOBI_EXT -> MobiParser.image(file, path)?.let { bytes -> imageType(bytes)?.let { bytes to it } }
                else -> null
            } ?: throw NotFoundException("图片不存在")
        } catch (e: LocalBookException) {
            throw NotFoundException("图片不存在")
        }
    }

    suspend fun cover(userId: Long, bookUrl: String): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val file = fileOf(userId, bookUrl)
        if (!file.isFile) throw NotFoundException("没有封面")
        if (extOf(file) == "epub") {
            return@withContext EpubParser(file).use { epub ->
                val path = epub.metadata().coverPath ?: throw NotFoundException("没有封面")
                epub.image(path) ?: throw NotFoundException("没有封面")
            }
        }
        val cf = coverFile(file)
        if (!cf.isFile) throw NotFoundException("没有封面")
        val bytes = cf.readBytes()
        bytes to (imageType(bytes) ?: throw NotFoundException("没有封面"))
    }

    /** 保存浏览器渲染的 PDF 第一页作为封面 */
    suspend fun saveCover(userId: Long, bookUrl: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val file = fileOf(userId, bookUrl)
        if (!file.isFile || extOf(file) != "pdf") throw BadRequestException("只有 PDF 可以上传封面")
        if (bytes.size > MAX_COVER_BYTES) throw BadRequestException("封面图片过大")
        if (imageType(bytes) == null) throw BadRequestException("封面不是有效的图片")
        coverFile(file).writeBytes(bytes)
    }

    /** PDF 原文件（浏览器用 pdf.js 渲染页面） */
    fun pdfFile(userId: Long, bookUrl: String): File {
        val file = fileOf(userId, bookUrl)
        if (!file.isFile || extOf(file) != "pdf") throw NotFoundException("文件不存在")
        return file
    }

    /** 删除书籍时删除文件（含封面） */
    fun deleteFile(userId: Long, bookUrl: String) {
        runCatching { deleteFile(fileOf(userId, bookUrl)) }
    }

    private fun deleteFile(file: File) {
        LocalBookCache.evict(file)
        file.delete()
        coverFile(file).delete()
    }
}
