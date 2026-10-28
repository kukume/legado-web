package io.legado.web.service

import io.legado.web.security.ScriptUrls
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.model.webBook.BookContent
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.UserPrincipal
import io.legado.web.config.AppSettings
import io.legado.web.db.BookGroups
import io.legado.web.db.Bookmarks
import io.legado.web.db.Books
import io.legado.web.db.ChapterContents
import io.legado.web.db.Chapters
import io.legado.web.db.dbQuery
import io.legado.web.engine.runEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

data class BookDto(
    val id: Long,
    val bookUrl: String,
    val tocUrl: String,
    val origin: String,
    val originName: String,
    val name: String,
    val author: String,
    val kind: String?,
    val coverUrl: String?,
    val intro: String?,
    val type: Int,
    val groupId: Long,
    val inShelf: Boolean,
    val latestChapterTitle: String?,
    val latestChapterTime: Long,
    val lastCheckTime: Long,
    val lastCheckCount: Int,
    val totalChapterNum: Int,
    val durChapterTitle: String?,
    val durChapterIndex: Int,
    val durChapterPos: Int,
    val durChapterTime: Long,
    val wordCount: String?,
    val canUpdate: Boolean,
    val order: Int,
    val customCoverUrl: String?,
    val customIntro: String?,
    /** 书源是否仍然可用 */
    var sourceId: Long? = null,
    /** 书源是否提供段评（ruleReview） */
    var hasReview: Boolean = false,
    /** 本地书籍（用户上传的 EPUB / TXT） */
    val isLocal: Boolean = false,
    /** 音频书籍（来自音频书源，正文是音频地址） */
    val isAudio: Boolean = false,
    /** 视频（短剧等，来自视频书源，正文是视频地址） */
    val isVideo: Boolean = false,
    /** 图片（漫画，来自图片书源，正文是图片） */
    val isImage: Boolean = false,
    /** 正文处理（按书保存，与 App 的 readConfig 相同）：重新分段、删除重复标题、替换净化 */
    val reSegment: Boolean = false,
    val removeSameTitle: Boolean = true,
    val useReplaceRule: Boolean = true,
)

data class ChapterDto(
    val index: Int,
    val title: String,
    val isVolume: Boolean,
    val isVip: Boolean,
    val isPay: Boolean,
    val tag: String?,
    val cached: Boolean,
)

/** 书内搜索结果中关键字前后各保留的字数 */
private const val SNIPPET = 24

/** 书内搜索的一条结果 */
data class BookSearchHit(
    val chapterIndex: Int,
    val chapterTitle: String,
    /** 关键字前后的一小段文字 */
    val snippet: String,
    /** 关键字在 snippet 中的位置 */
    val matchStart: Int,
    val matchLength: Int,
    /** 章内位置（比例，0..10000） */
    val pos: Int,
)

data class BookSearchResult(
    /** 章节总数（不含卷名） */
    val total: Int,
    /** 实际搜索的章节数（在线书籍只搜已缓存的章节） */
    val searched: Int,
    val hits: List<BookSearchHit>,
    /** 结果太多，只返回了前面一部分 */
    val truncated: Boolean,
)

data class ContentDto(
    val index: Int,
    val title: String,
    val content: String,
    val isVolume: Boolean,
    val cached: Boolean,
    /** 正文实际上是视频地址（如聚合书源的“短剧”），前端改用视频播放页 */
    val video: Boolean = false,
    /** 分页正文的后续页面获取失败，内容不完整（末尾带有提示，未缓存） */
    val incomplete: Boolean = false,
)

/**
 * 文本书源返回的正文实际上是视频地址时（聚合书源的短剧、影视等，App 中多靠书源自己打开网页播放器），取出该地址。
 * 判断：正文很短，只有一个网址，且网址是视频文件 / m3u8，或正文带有“播放直链”等提示
 */
fun detectVideoUrl(content: String): String? {
    if (content.length > 3000) return null
    val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty() || lines.size > 8) return null
    val urls = lines.filter { it.startsWith("http", true) && it.none { c -> c == ' ' || c == '<' } }
    if (urls.size != 1) return null
    val url = urls[0]
    val path = url.substringBefore(",{").substringBefore('#').substringBefore('?').lowercase()
    val isVideoFile = Regex("""\.(m3u8|mp4|flv|webm|mov|mkv|m4v|ts)$""").containsMatchIn(path) ||
        url.substringBefore(",{").contains(".m3u8", true)
    val hinted = Regex("播放直链|视频直链|开启播放|播放地址").containsMatchIn(content)
    return url.takeIf { isVideoFile || hinted }
}

data class GroupDto(val id: Long, val name: String, val order: Int)

/** 加入书架 / 打开书籍的请求 */
data class OpenBookRequest(
    val bookUrl: String = "",
    val origin: String = "",
    val sourceId: Long? = null,
    val name: String = "",
    val author: String = "",
    val kind: String? = null,
    val coverUrl: String? = null,
    val intro: String? = null,
    val wordCount: String? = null,
    val latestChapterTitle: String? = null,
    val tocUrl: String? = null,
    val type: Int? = null,
    val variable: String? = null,
    val inShelf: Boolean = false,
    val groupId: Long? = null,
)

class ShelfService(
    private val sourceService: SourceService,
    private val replaceService: ReplaceRuleService,
    private val settings: AppSettings,
) {
    /** 本地书籍（EPUB / TXT），由 Application 设置 */
    lateinit var localBooks: LocalBookService

    private val bookLocks = ConcurrentHashMap<Long, Mutex>()
    private fun lock(bookId: Long) = bookLocks.getOrPut(bookId) { Mutex() }

    private fun ResultRow.toDto() = BookDto(
        id = this[Books.id].value,
        bookUrl = this[Books.bookUrl],
        tocUrl = this[Books.tocUrl],
        origin = this[Books.origin],
        originName = this[Books.originName],
        name = this[Books.name],
        author = this[Books.author],
        kind = this[Books.kind],
        coverUrl = this[Books.coverUrl],
        intro = this[Books.intro],
        type = this[Books.type],
        groupId = this[Books.groupId],
        inShelf = this[Books.inShelf],
        latestChapterTitle = this[Books.latestChapterTitle],
        latestChapterTime = this[Books.latestChapterTime],
        lastCheckTime = this[Books.lastCheckTime],
        lastCheckCount = this[Books.lastCheckCount],
        totalChapterNum = this[Books.totalChapterNum],
        durChapterTitle = this[Books.durChapterTitle],
        durChapterIndex = this[Books.durChapterIndex],
        durChapterPos = this[Books.durChapterPos],
        durChapterTime = this[Books.durChapterTime],
        wordCount = this[Books.wordCount],
        canUpdate = this[Books.canUpdate],
        order = this[Books.order],
        customCoverUrl = this[Books.customCoverUrl],
        customIntro = this[Books.customIntro],
        isLocal = LocalBookService.isLocal(this[Books.origin]),
        isAudio = this[Books.type] and io.legado.app.constant.BookType.audio != 0,
        isVideo = this[Books.type] and io.legado.app.constant.BookType.video != 0,
        isImage = this[Books.type] and io.legado.app.constant.BookType.image != 0,
    ).let { dto ->
        val rc = readConfigOf(this)
        // 替换净化没设置时：漫画关闭、其他开启（与 ReplaceRuleService.useReplaceRule 相同）
        val replace = rc?.useReplaceRule ?: !dto.isImage
        if (rc == null) dto.copy(useReplaceRule = replace)
        else dto.copy(reSegment = rc.reSegment, removeSameTitle = rc.removeSameTitle, useReplaceRule = replace)
    }

    private fun readConfigOf(row: ResultRow): Book.ReadConfig? =
        row[Books.readConfig]?.let { GSON.fromJsonObject<Book.ReadConfig>(it).getOrNull() }

    private fun ResultRow.toEngineBook() = Book(
        bookUrl = this[Books.bookUrl],
        tocUrl = this[Books.tocUrl],
        origin = this[Books.origin],
        originName = this[Books.originName],
        name = this[Books.name],
        author = this[Books.author],
        kind = this[Books.kind],
        coverUrl = this[Books.coverUrl],
        intro = this[Books.intro],
        type = this[Books.type],
        latestChapterTitle = this[Books.latestChapterTitle],
        latestChapterTime = this[Books.latestChapterTime],
        lastCheckTime = this[Books.lastCheckTime],
        lastCheckCount = this[Books.lastCheckCount],
        totalChapterNum = this[Books.totalChapterNum],
        durChapterTitle = this[Books.durChapterTitle],
        durChapterIndex = this[Books.durChapterIndex],
        durChapterPos = this[Books.durChapterPos],
        wordCount = this[Books.wordCount],
        variable = this[Books.variable],
        readConfig = readConfigOf(this),
    )

    private fun ResultRow.toChapter(bookUrl: String) = BookChapter(
        url = this[Chapters.url],
        title = this[Chapters.title],
        isVolume = this[Chapters.isVolume],
        baseUrl = this[Chapters.baseUrl],
        bookUrl = bookUrl,
        index = this[Chapters.index],
        isVip = this[Chapters.isVip],
        isPay = this[Chapters.isPay],
        tag = this[Chapters.tag],
        wordCount = this[Chapters.wordCount],
        variable = this[Chapters.variable],
    )

    private suspend fun bookRow(user: UserPrincipal, bookId: Long): ResultRow = dbQuery {
        Books.selectAll().where { (Books.id eq bookId) and (Books.userId eq user.id) }.firstOrNull()
    } ?: throw NotFoundException("书籍不存在")

    // ---------------- 书架 ----------------

    suspend fun shelf(user: UserPrincipal): List<BookDto> = dbQuery {
        Books.selectAll().where { (Books.userId eq user.id) and (Books.inShelf eq true) }
            .orderBy(Books.durChapterTime, SortOrder.DESC)
            .map { it.toDto() }
    }

    suspend fun getBook(user: UserPrincipal, bookId: Long): BookDto {
        val dto = bookRow(user, bookId).toDto()
        val stored = sourceService.findByUrl(user, dto.origin)
        dto.sourceId = stored?.id
        dto.hasReview = ReviewService.hasReview(stored?.source)
        return dto
    }

    /**
     * 打开一本书：已存在则直接返回，否则获取详情和目录后保存
     */
    suspend fun open(user: UserPrincipal, req: OpenBookRequest): BookDto {
        if (req.bookUrl.isBlank()) throw BadRequestException("bookUrl 不能为空")
        sourceService.requireTrustedUrls(user, req.bookUrl, req.tocUrl, req.coverUrl)
        val existing = dbQuery {
            Books.selectAll().where { (Books.userId eq user.id) and (Books.bookUrl eq req.bookUrl) }.firstOrNull()
        }
        if (existing != null) {
            val id = existing[Books.id].value
            if (req.inShelf && !existing[Books.inShelf]) {
                dbQuery {
                    Books.update({ Books.id eq id }) {
                        it[inShelf] = true
                        req.groupId?.let { g -> it[groupId] = g }
                    }
                }
            }
            if (existing[Books.totalChapterNum] == 0) {
                runCatching { refreshToc(user, id) }
            }
            return getBook(user, id)
        }
        val stored = (req.sourceId?.let { runCatching { sourceService.get(user, it) }.getOrNull() }
            ?: sourceService.findByUrl(user, req.origin))
            ?: throw NotFoundException("书源不存在或已被删除")
        val source = stored.source
        val book = Book(
            bookUrl = req.bookUrl,
            origin = source.bookSourceUrl,
            originName = source.bookSourceName,
            name = req.name,
            author = req.author,
            kind = req.kind,
            coverUrl = req.coverUrl,
            intro = req.intro,
            wordCount = req.wordCount,
            latestChapterTitle = req.latestChapterTitle,
            tocUrl = req.tocUrl ?: "",
            type = req.type ?: 8,
            variable = req.variable,
        )
        val chapters = runEngine(user.id, 180_000) {
            WebBook.getBookInfoAwait(source, book)
            WebBook.getChapterListAwait(source, book).getOrThrow()
        }
        book.lastCheckCount = 0
        val now = System.currentTimeMillis()
        val id = dbQuery {
            val id = Books.insertAndGetId {
                it[userId] = user.id
                it[createdAt] = now
                it[inShelf] = req.inShelf
                it[groupId] = req.groupId ?: 0
                it[durChapterTime] = now
                writeBook(it, book)
            }.value
            saveChapters(id, chapters)
            id
        }
        return getBook(user, id)
    }

    /** 本地书籍的 bookUrl（不是本地书籍时报错） */
    suspend fun localBookUrl(user: UserPrincipal, bookId: Long): String {
        val row = bookRow(user, bookId)
        if (!LocalBookService.isLocal(row[Books.origin])) throw NotFoundException("不是本地书籍")
        return row[Books.bookUrl]
    }

    /** 本地书籍保存了封面文件后，书籍的封面指向封面接口（PDF 的封面由浏览器渲染第一页后上传） */
    suspend fun setLocalCover(user: UserPrincipal, bookId: Long): BookDto {
        bookRow(user, bookId)
        dbQuery { Books.update({ (Books.id eq bookId) and (Books.userId eq user.id) }) { it[coverUrl] = localBooks.coverUrl(bookId) + "?v=" + System.currentTimeMillis() } }
        return getBook(user, bookId)
    }

    /** 保存上传的本地书籍（章节由 LocalBookService 解析） */
    suspend fun addLocalBook(user: UserPrincipal, book: Book, chapters: List<BookChapter>, groupId: Long, coverPath: String?): BookDto {
        val now = System.currentTimeMillis()
        book.totalChapterNum = chapters.size
        book.latestChapterTitle = chapters.lastOrNull()?.title?.trim()
        book.latestChapterTime = now
        book.lastCheckTime = now
        val id = dbQuery {
            val id = Books.insertAndGetId {
                it[userId] = user.id
                it[createdAt] = now
                it[inShelf] = true
                it[Books.groupId] = groupId
                it[durChapterTime] = now
                writeBook(it, book)
            }.value
            saveChapters(id, chapters)
            if (coverPath != null) Books.update({ Books.id eq id }) { it[coverUrl] = localBooks.coverUrl(id) }
            id
        }
        return getBook(user, id)
    }

    private fun writeBook(it: org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>, book: Book) {
        it[Books.bookUrl] = book.bookUrl
        it[Books.tocUrl] = book.tocUrl
        it[Books.origin] = book.origin
        it[Books.originName] = book.originName
        it[Books.name] = book.name.ifBlank { "未知书名" }.take(255)
        it[Books.author] = book.author.take(255)
        it[Books.kind] = book.kind
        it[Books.coverUrl] = book.coverUrl
        it[Books.intro] = book.intro
        it[Books.type] = book.type
        it[Books.latestChapterTitle] = book.latestChapterTitle
        it[Books.latestChapterTime] = book.latestChapterTime
        it[Books.lastCheckTime] = book.lastCheckTime
        it[Books.lastCheckCount] = book.lastCheckCount
        it[Books.totalChapterNum] = book.totalChapterNum
        it[Books.durChapterTitle] = book.durChapterTitle
        it[Books.wordCount] = book.wordCount?.take(64)
        it[Books.variable] = book.variable
    }

    private fun saveChapters(bookId: Long, chapters: List<BookChapter>) {
        Chapters.deleteWhere { Chapters.bookId eq bookId }
        Chapters.batchInsert(chapters, shouldReturnGeneratedValues = false) { c ->
            this[Chapters.bookId] = bookId
            this[Chapters.index] = c.index
            this[Chapters.url] = c.url
            this[Chapters.title] = c.title
            this[Chapters.isVolume] = c.isVolume
            this[Chapters.baseUrl] = c.baseUrl
            this[Chapters.tag] = c.tag
            this[Chapters.isVip] = c.isVip
            this[Chapters.isPay] = c.isPay
            this[Chapters.wordCount] = c.wordCount?.take(64)
            this[Chapters.variable] = c.variable
        }
        // 章节地址变化后缓存的正文可能错位，按 url 校验，不匹配的删除
        val valid = chapters.associate { it.index to it.url }
        ChapterContents.selectAll().where { ChapterContents.bookId eq bookId }
            .map { it[ChapterContents.index] to it[ChapterContents.chapterUrl] }
            .filter { (i, url) -> valid[i] != url }
            .forEach { (i, _) ->
                ChapterContents.deleteWhere { (ChapterContents.bookId eq bookId) and (ChapterContents.index eq i) }
            }
    }

    /**
     * 刷新详情 + 目录
     */
    suspend fun refreshToc(user: UserPrincipal, bookId: Long, refreshInfo: Boolean = false, txtTocUrl: String? = null): BookDto {
        lock(bookId).withLock {
            val row = bookRow(user, bookId)
            val book = row.toEngineBook()
            if (LocalBookService.isLocal(book.origin)) {
                // PDF 的目录按页数生成：页数取自当前目录最后一章
                val pdfPages = if (book.bookUrl.endsWith(".pdf")) dbQuery {
                    Chapters.select(Chapters.url).where { Chapters.bookId eq bookId }
                        .orderBy(Chapters.index, SortOrder.DESC).limit(1).firstOrNull()?.get(Chapters.url)
                }?.let { io.legado.web.local.PdfParser.pagesOf(it) } else null
                val parsed = localBooks.reparse(user.id, book, txtTocUrl, pdfPages)
                val chapters = parsed.chapters
                dbQuery {
                    Books.update({ Books.id eq bookId }) {
                        it[totalChapterNum] = chapters.size
                        it[latestChapterTitle] = chapters.lastOrNull()?.title?.trim()
                        it[lastCheckTime] = System.currentTimeMillis()
                        parsed.tocUrl?.let { u -> it[tocUrl] = u }
                        parsed.wordCount?.let { w -> it[wordCount] = w.take(64) }
                    }
                    saveChapters(bookId, chapters)
                }
                return getBook(user, bookId)
            }
            val stored = sourceService.findByUrl(user, book.origin)
                ?: throw NotFoundException("书源「${book.originName}」不存在，请换源")
            val oldTotal = book.totalChapterNum
            val chapters = runEngine(user.id, 180_000) {
                if (refreshInfo || book.tocUrl.isBlank()) {
                    WebBook.getBookInfoAwait(stored.source, book, canReName = false)
                }
                WebBook.getChapterListAwait(stored.source, book, runPerJs = true).getOrThrow()
            }
            if (book.totalChapterNum > oldTotal && oldTotal > 0) {
                book.lastCheckCount = book.totalChapterNum - oldTotal
            }
            dbQuery {
                Books.update({ Books.id eq bookId }) {
                    writeBook(it, book)
                }
                saveChapters(bookId, chapters)
            }
        }
        return getBook(user, bookId)
    }

    suspend fun chapters(user: UserPrincipal, bookId: Long): List<ChapterDto> {
        bookRow(user, bookId)
        // 还没有目录（例如从 App 备份导入的书）：先从书源获取
        val hasChapters = dbQuery { Chapters.select(Chapters.index).where { Chapters.bookId eq bookId }.limit(1).any() }
        if (!hasChapters) runCatching { refreshToc(user, bookId) }
        return dbQuery {
            val cached = ChapterContents.select(ChapterContents.index)
                .where { ChapterContents.bookId eq bookId }
                .map { it[ChapterContents.index] }.toSet()
            Chapters.selectAll().where { Chapters.bookId eq bookId }
                .orderBy(Chapters.index, SortOrder.ASC)
                .map {
                    ChapterDto(
                        index = it[Chapters.index],
                        title = it[Chapters.title],
                        isVolume = it[Chapters.isVolume],
                        isVip = it[Chapters.isVip],
                        isPay = it[Chapters.isPay],
                        tag = it[Chapters.tag],
                        cached = it[Chapters.index] in cached,
                    )
                }
        }
    }

    /**
     * 获取章节正文（优先缓存）
     */
    suspend fun content(user: UserPrincipal, bookId: Long, index: Int, refresh: Boolean): ContentDto =
        loadContent(user, bookId, index, refresh).also { ScriptUrls.rememberContentImages(user.id, it.content) }

    private suspend fun loadContent(user: UserPrincipal, bookId: Long, index: Int, refresh: Boolean): ContentDto {
        val row = bookRow(user, bookId)
        val book = row.toEngineBook()
        val chapterRow = dbQuery {
            Chapters.selectAll().where { (Chapters.bookId eq bookId) and (Chapters.index eq index) }.firstOrNull()
        } ?: throw NotFoundException("章节不存在")
        val chapter = chapterRow.toChapter(book.bookUrl)
        if (LocalBookService.isLocal(book.origin)) {
            val urls = if (LocalBookService.needsAllUrls(chapter.url)) dbQuery {
                Chapters.select(Chapters.url).where { Chapters.bookId eq bookId }.orderBy(Chapters.index, SortOrder.ASC).map { it[Chapters.url] }
            } else emptyList()
            val raw = localBooks.content(user.id, bookId, book.bookUrl, chapter.url, urls, index, chapter.title)
            return ContentDto(index, chapter.getDisplayTitle(), replaceService.apply(user.id, book, chapter.title, raw), chapter.isVolume, true)
        }
        if (!refresh) {
            val cached = dbQuery {
                ChapterContents.selectAll()
                    .where { (ChapterContents.bookId eq bookId) and (ChapterContents.index eq index) }
                    .firstOrNull()
            }
            if (cached != null && cached[ChapterContents.chapterUrl] == chapter.url) {
                val text = cached[ChapterContents.content]
                return ContentDto(
                    index, chapter.getDisplayTitle(),
                    replaceService.apply(user.id, book, chapter.title, text),
                    chapter.isVolume, true, markIfVideo(bookId, row[Books.type], text),
                )
            }
        }
        val raw = fetchContent(user, bookId, book, chapter)
        return ContentDto(
            index, chapter.getDisplayTitle(), replaceService.apply(user.id, book, chapter.title, raw),
            chapter.isVolume, false, markIfVideo(bookId, row[Books.type], raw),
            BookContent.isIncomplete(raw),
        )
    }

    /** 正文是视频地址时把书标记为视频（之后从书架直接用视频播放页打开），返回是否为视频 */
    private suspend fun markIfVideo(bookId: Long, type: Int, content: String): Boolean {
        if (type and (BookType.audio or BookType.image) != 0) return false
        if (type and BookType.video != 0) return true
        if (detectVideoUrl(content) == null) return false
        dbQuery { Books.update({ Books.id eq bookId }) { it[Books.type] = type or BookType.video } }
        return true
    }

    /**
     * 章节的原始正文（不应用替换规则），音频书籍的正文就是音频地址。
     * [refresh] 为 true 时重新从书源获取（音频地址常带有效期，过期后需要刷新）。
     */
    /**
     * 书内全文搜索：本地书籍搜索全书，在线书籍只搜已缓存的章节（未缓存的章节需要向书源逐章请求，容易被限流，不在这里抓取）。
     * 搜索的是应用替换规则后的正文，与阅读时看到的一致；不区分大小写
     */
    suspend fun searchBook(user: UserPrincipal, bookId: Long, keyword: String, limit: Int = 300): BookSearchResult {
        val q = keyword.trim()
        if (q.isEmpty()) throw BadRequestException("请输入要搜索的内容")
        if (q.length > 100) throw BadRequestException("搜索内容过长")
        val book = bookRow(user, bookId).toEngineBook()
        val chapters = dbQuery {
            Chapters.selectAll().where { Chapters.bookId eq bookId }.orderBy(Chapters.index, SortOrder.ASC)
                .map { it.toChapter(book.bookUrl) }
        }.filter { !it.isVolume }
        val local = LocalBookService.isLocal(book.origin)
        val urls = if (local && chapters.any { LocalBookService.needsAllUrls(it.url) }) dbQuery {
            Chapters.select(Chapters.url).where { Chapters.bookId eq bookId }.orderBy(Chapters.index, SortOrder.ASC).map { it[Chapters.url] }
        } else emptyList()
        // 在线书籍：已缓存的正文（章节地址变了的缓存已过时，不算）
        val cached: Map<Int, String> = if (local) emptyMap() else dbQuery {
            val urlOf = chapters.associate { it.index to it.url }
            ChapterContents.selectAll().where { ChapterContents.bookId eq bookId }
                .filter { urlOf[it[ChapterContents.index]] == it[ChapterContents.chapterUrl] }
                .associate { it[ChapterContents.index] to it[ChapterContents.content] }
        }
        val tag = Regex("<[^>]*>")
        val space = Regex("\\s+")
        val hits = mutableListOf<BookSearchHit>()
        var searched = 0
        var truncated = false
        outer@ for (ch in chapters) {
            val raw = if (local) {
                runCatching { localBooks.content(user.id, bookId, book.bookUrl, ch.url, urls, ch.index, ch.title) }.getOrNull()
            } else cached[ch.index]
            raw ?: continue
            searched++
            val text = replaceService.apply(user.id, book, ch.title, raw).replace(tag, "")
            var i = text.indexOf(q, ignoreCase = true)
            while (i >= 0) {
                if (hits.size >= limit) {
                    truncated = true
                    break@outer
                }
                val from = maxOf(0, i - SNIPPET)
                val before = text.substring(from, i).replace(space, " ")
                val after = text.substring(i + q.length, minOf(text.length, i + q.length + SNIPPET)).replace(space, " ")
                hits.add(
                    BookSearchHit(
                        chapterIndex = ch.index,
                        chapterTitle = ch.getDisplayTitle(),
                        snippet = (if (from > 0) "…" else "") + before.trimStart() + text.substring(i, i + q.length) + after.trimEnd(),
                        matchStart = (if (from > 0) 1 else 0) + before.trimStart().length,
                        matchLength = q.length,
                        pos = (i.toLong() * 10000 / maxOf(1, text.length)).toInt(),
                    )
                )
                i = text.indexOf(q, i + q.length, ignoreCase = true)
            }
        }
        return BookSearchResult(total = chapters.size, searched = searched, hits = hits, truncated = truncated)
    }

    suspend fun rawContent(user: UserPrincipal, bookId: Long, index: Int, refresh: Boolean): String {
        val book = bookRow(user, bookId).toEngineBook()
        val chapter = dbQuery {
            Chapters.selectAll().where { (Chapters.bookId eq bookId) and (Chapters.index eq index) }.firstOrNull()
        }?.toChapter(book.bookUrl) ?: throw NotFoundException("章节不存在")
        if (!refresh) {
            val cached = dbQuery {
                ChapterContents.selectAll()
                    .where { (ChapterContents.bookId eq bookId) and (ChapterContents.index eq index) }
                    .firstOrNull()
            }
            if (cached != null && cached[ChapterContents.chapterUrl] == chapter.url) return cached[ChapterContents.content]
        }
        return fetchContent(user, bookId, book, chapter)
    }

    /**
     * 正文图片点击（段评气泡、本章说卡片等）的执行上下文。
     * 只接受本章正文中确实存在的图片地址，点击脚本取自图片地址里的参数，不接受前端提交的任意脚本。
     */
    suspend fun imageClickContext(user: UserPrincipal, bookId: Long, index: Int, src: String): Triple<BookSource, Book, BookChapter> {
        if (src.isBlank()) throw BadRequestException("图片地址为空")
        val content = content(user, bookId, index, false)
        if (!content.content.contains(src)) throw BadRequestException("图片不在本章正文中，请刷新本章后重试")
        return chapterContext(user, bookId, index)
    }

    /** 书源、书籍、章节（在引擎中执行与章节相关的书源规则时使用，如段评） */
    suspend fun chapterContext(user: UserPrincipal, bookId: Long, index: Int): Triple<BookSource, Book, BookChapter> {
        val book = bookRow(user, bookId).toEngineBook()
        val chapter = dbQuery {
            Chapters.selectAll().where { (Chapters.bookId eq bookId) and (Chapters.index eq index) }.firstOrNull()
        }?.toChapter(book.bookUrl) ?: throw NotFoundException("章节不存在")
        val source = sourceService.findByUrl(user, book.origin)?.source
            ?: throw NotFoundException("书源「${book.originName}」不存在")
        return Triple(source, book, chapter)
    }

    /** 正在获取的正文（同一章同时只向书源请求一次） */
    private val inflightContent = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /**
     * 从书源获取正文。同一章节的并发请求合并为一次：音频 / 视频书源的正文请求常会触发书源后台的转码任务，
     * 重复请求会让后台同时跑多个任务（例如写同一个文件而失败）。
     */
    private suspend fun fetchContent(user: UserPrincipal, bookId: Long, book: Book, chapter: BookChapter): String {
        val key = "$bookId:${chapter.index}:${chapter.url}"
        val mine = CompletableDeferred<String>()
        val running = inflightContent.putIfAbsent(key, mine)
        if (running != null) return running.await()
        return try {
            fetchContentOnce(user, bookId, book, chapter).also { mine.complete(it) }
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inflightContent.remove(key, mine)
        }
    }

    private suspend fun fetchContentOnce(user: UserPrincipal, bookId: Long, book: Book, chapter: BookChapter): String {
        val stored = sourceService.findByUrl(user, book.origin)
            ?: throw NotFoundException("书源「${book.originName}」不存在，请换源")
        val nextUrl = dbQuery {
            Chapters.select(Chapters.url)
                .where { (Chapters.bookId eq bookId) and (Chapters.index eq chapter.index + 1) }
                .firstOrNull()?.get(Chapters.url)
        }
        val oldTitle = chapter.title
        val oldBookVar = book.variable
        val content = runEngine(user.id, 120_000) {
            WebBook.getContentAwait(stored.source, book, chapter, nextUrl, needSave = false)
        }
        dbQuery {
            // 不完整的正文不缓存，下次打开时重新获取
            if (content.isNotBlank() && !BookContent.isIncomplete(content)) {
                ChapterContents.upsert {
                    it[ChapterContents.bookId] = bookId
                    it[index] = chapter.index
                    it[chapterUrl] = chapter.url
                    it[ChapterContents.content] = content
                    it[updatedAt] = System.currentTimeMillis()
                }
            }
            if (chapter.title != oldTitle || chapter.variable != null) {
                Chapters.update({ (Chapters.bookId eq bookId) and (Chapters.index eq chapter.index) }) {
                    it[title] = chapter.title
                    it[variable] = chapter.variable
                }
            }
            if (book.variable != oldBookVar) {
                Books.update({ Books.id eq bookId }) { it[variable] = book.variable }
            }
        }
        return content
    }

    /**
     * 预下载后续章节
     */
    suspend fun cacheChapters(user: UserPrincipal, bookId: Long, start: Int, count: Int): Int {
        val row = bookRow(user, bookId)
        val book = row.toEngineBook()
        if (LocalBookService.isLocal(book.origin)) return 0
        val cached = dbQuery {
            ChapterContents.select(ChapterContents.index).where { ChapterContents.bookId eq bookId }
                .map { it[ChapterContents.index] }.toSet()
        }
        val targets = dbQuery {
            Chapters.selectAll().where { Chapters.bookId eq bookId }
                .orderBy(Chapters.index, SortOrder.ASC)
                .filter { it[Chapters.index] >= start && it[Chapters.index] !in cached && !it[Chapters.isVolume] }
                .take(count.coerceIn(1, 200))
                .map { it.toChapter(book.bookUrl) }
        }
        var ok = 0
        for (c in targets) {
            runCatching { fetchContent(user, bookId, book, c) }.onSuccess { ok++ }
        }
        return ok
    }

    suspend fun clearCache(user: UserPrincipal, bookId: Long) {
        bookRow(user, bookId)
        dbQuery { ChapterContents.deleteWhere { ChapterContents.bookId eq bookId } }
    }

    /**
     * 保存阅读进度。
     * time 为客户端记录进度的时间（离线阅读同步时传入）：比服务器上已有的进度旧则忽略，返回 false。
     */
    suspend fun saveProgress(user: UserPrincipal, bookId: Long, index: Int, pos: Int, title: String?, time: Long? = null): Boolean {
        bookRow(user, bookId)
        val now = System.currentTimeMillis()
        // 客户端时间不能晚于服务器当前时间（避免手机时间不准导致以后的进度都被忽略）
        val t = time?.coerceAtMost(now) ?: now
        val n = dbQuery {
            Books.update({
                (Books.id eq bookId) and (Books.userId eq user.id) and
                    (if (time != null) Books.durChapterTime lessEq t else Op.TRUE)
            }) {
                it[durChapterIndex] = index
                it[durChapterPos] = pos
                it[durChapterTitle] = title
                it[durChapterTime] = t
                it[lastCheckCount] = 0
            }
        }
        return n > 0
    }

    data class BookUpdate(
        val name: String? = null,
        val author: String? = null,
        val groupId: Long? = null,
        val inShelf: Boolean? = null,
        val customCoverUrl: String? = null,
        val customIntro: String? = null,
        val canUpdate: Boolean? = null,
        val order: Int? = null,
        val reSegment: Boolean? = null,
        val removeSameTitle: Boolean? = null,
        val useReplaceRule: Boolean? = null,
    )

    suspend fun update(user: UserPrincipal, bookId: Long, u: BookUpdate): BookDto {
        val row = bookRow(user, bookId)
        sourceService.requireTrustedUrls(user, u.customCoverUrl)
        // 正文处理开关写进 readConfig（按 JSON 合并，保留 App 备份里其他的字段）
        val readConfig = if (u.reSegment == null && u.removeSameTitle == null && u.useReplaceRule == null) null else {
            val obj = row[Books.readConfig]?.let { runCatching { com.google.gson.JsonParser.parseString(it).asJsonObject }.getOrNull() }
                ?: com.google.gson.JsonObject()
            u.reSegment?.let { obj.addProperty("reSegment", it) }
            u.removeSameTitle?.let { obj.addProperty("removeSameTitle", it) }
            u.useReplaceRule?.let { obj.addProperty("useReplaceRule", it) }
            obj.toString()
        }
        dbQuery {
            Books.update({ Books.id eq bookId }) {
                u.name?.takeIf { n -> n.isNotBlank() }?.let { v -> it[name] = v.take(255) }
                u.author?.let { v -> it[author] = v.take(255) }
                u.groupId?.let { v -> it[groupId] = v }
                u.inShelf?.let { v -> it[inShelf] = v }
                u.customCoverUrl?.let { v -> it[customCoverUrl] = v.ifBlank { null } }
                u.customIntro?.let { v -> it[customIntro] = v.ifBlank { null } }
                u.canUpdate?.let { v -> it[canUpdate] = v }
                u.order?.let { v -> it[order] = v }
                readConfig?.let { v -> it[Books.readConfig] = v }
            }
        }
        return getBook(user, bookId)
    }

    /** 书架手动排序：按 [ids] 的顺序写入 order（只修改自己的书） */
    suspend fun saveOrder(user: UserPrincipal, ids: List<Long>) {
        dbQuery {
            ids.forEachIndexed { i, id ->
                Books.update({ (Books.id eq id) and (Books.userId eq user.id) }) { it[order] = i }
            }
        }
    }

    suspend fun delete(user: UserPrincipal, bookId: Long) {
        val row = bookRow(user, bookId)
        if (LocalBookService.isLocal(row[Books.origin])) localBooks.deleteFile(user.id, row[Books.bookUrl])
        dbQuery {
            Books.deleteWhere { Books.id eq bookId }
            Chapters.deleteWhere { Chapters.bookId eq bookId }
            ChapterContents.deleteWhere { ChapterContents.bookId eq bookId }
            Bookmarks.deleteWhere { Bookmarks.bookId eq bookId }
        }
    }

    /**
     * 换源：用新书源的书替换当前书籍，保留阅读进度
     */
    suspend fun changeSource(user: UserPrincipal, bookId: Long, req: OpenBookRequest): BookDto {
        val row = bookRow(user, bookId)
        val old = row.toEngineBook()
        if (LocalBookService.isLocal(old.origin)) throw BadRequestException("本地书籍不能换源")
        sourceService.requireTrustedUrls(user, req.bookUrl, req.tocUrl, req.coverUrl)
        val stored = (req.sourceId?.let { runCatching { sourceService.get(user, it) }.getOrNull() }
            ?: sourceService.findByUrl(user, req.origin))
            ?: throw NotFoundException("书源不存在")
        val newBook = Book(
            bookUrl = req.bookUrl,
            origin = stored.source.bookSourceUrl,
            originName = stored.source.bookSourceName,
            name = req.name.ifBlank { old.name },
            author = req.author.ifBlank { old.author },
            coverUrl = req.coverUrl,
            intro = req.intro,
            kind = req.kind,
            tocUrl = req.tocUrl ?: "",
            variable = req.variable,
        )
        val chapters = runEngine(user.id, 180_000) {
            WebBook.getBookInfoAwait(stored.source, newBook, canReName = false)
            WebBook.getChapterListAwait(stored.source, newBook).getOrThrow()
        }
        val durIndex = io.legado.app.help.book.BookHelp.getDurChapter(
            old.durChapterIndex, old.durChapterTitle, chapters, old.totalChapterNum
        )
        newBook.durChapterIndex = durIndex
        newBook.durChapterTitle = chapters.getOrNull(durIndex)?.title
        // 同一用户可能已有这个 bookUrl 的临时记录，先删除
        dbQuery {
            Books.deleteWhere {
                (Books.userId eq user.id) and (Books.bookUrl eq newBook.bookUrl) and (Books.inShelf eq false)
            }
            Books.update({ Books.id eq bookId }) {
                writeBook(it, newBook)
                it[durChapterIndex] = durIndex
                it[durChapterTitle] = newBook.durChapterTitle
                it[durChapterPos] = 0
            }
            ChapterContents.deleteWhere { ChapterContents.bookId eq bookId }
            saveChapters(bookId, chapters)
        }
        return getBook(user, bookId)
    }

    /** 清理长期未访问的临时书籍（未加入书架） */
    suspend fun cleanupTemporary(days: Int = 7) {
        val before = System.currentTimeMillis() - days * 86400_000L
        dbQuery {
            val ids = Books.select(Books.id)
                .where { (Books.inShelf eq false) and (Books.durChapterTime less before) }
                .map { it[Books.id].value }
            ids.forEach { id ->
                Books.deleteWhere { Books.id eq id }
                Chapters.deleteWhere { Chapters.bookId eq id }
                ChapterContents.deleteWhere { ChapterContents.bookId eq id }
            }
        }
    }

    /** 获取书籍对应的引擎对象和书源（图片代理解密用） */
    suspend fun engineBook(user: UserPrincipal, bookId: Long): Book = bookRow(user, bookId).toEngineBook()

    // ---------------- 分组 ----------------

    suspend fun groups(user: UserPrincipal): List<GroupDto> = dbQuery {
        BookGroups.selectAll().where { BookGroups.userId eq user.id }
            .orderBy(BookGroups.order to SortOrder.ASC, BookGroups.id to SortOrder.ASC)
            .map { GroupDto(it[BookGroups.id].value, it[BookGroups.name], it[BookGroups.order]) }
    }

    suspend fun saveGroup(user: UserPrincipal, id: Long?, name: String, order: Int): GroupDto {
        if (name.isBlank()) throw BadRequestException("分组名不能为空")
        val gid = dbQuery {
            if (id == null) {
                BookGroups.insertAndGetId {
                    it[userId] = user.id
                    it[BookGroups.name] = name.take(64)
                    it[BookGroups.order] = order
                }.value
            } else {
                val n = BookGroups.update({ (BookGroups.id eq id) and (BookGroups.userId eq user.id) }) {
                    it[BookGroups.name] = name.take(64)
                    it[BookGroups.order] = order
                }
                if (n == 0) throw NotFoundException()
                id
            }
        }
        return GroupDto(gid, name, order)
    }

    suspend fun deleteGroup(user: UserPrincipal, id: Long) {
        dbQuery {
            BookGroups.deleteWhere { (BookGroups.id eq id) and (BookGroups.userId eq user.id) }
            Books.update({ (Books.userId eq user.id) and (Books.groupId eq id) }) { it[groupId] = 0 }
        }
    }
}
