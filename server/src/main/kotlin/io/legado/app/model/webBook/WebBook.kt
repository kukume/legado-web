package io.legado.app.model.webBook

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getContent
import io.legado.app.help.book.addType
import io.legado.app.help.book.removeAllBookType
import io.legado.app.help.http.StrResponse
import io.legado.app.help.source.getBookType
import io.legado.app.help.source.exploreInfoMap
import io.legado.app.help.source.SuppressSourceNavigation
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.model.jsSource.JsSourceBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlin.coroutines.CoroutineContext

@Suppress("MemberVisibilityCanBePrivate")
object WebBook {

    /**
     * 搜索
     */

    suspend fun searchBookAwait(
        bookSource: BookSource,
        key: String,
        page: Int? = 1,
        filter: ((name: String, author: String, kind: String?) -> Boolean)? = null,
        shouldBreak: ((size: Int) -> Boolean)? = null
    ): ArrayList<SearchBook> = withContext(SuppressSourceNavigation) {
        if (bookSource.isJsSource()) {
            return@withContext JsSourceBook.searchAwait(bookSource, key, page, filter)
        }
        val searchUrl = bookSource.searchUrl
        if (searchUrl.isNullOrBlank()) {
            throw NoStackTraceException("搜索url不能为空")
        }
        val ruleData = RuleData()
        val analyzeUrl = AnalyzeUrl(
            mUrl = searchUrl,
            key = key,
            page = page,
            baseUrl = bookSource.bookSourceUrl,
            source = bookSource,
            ruleData = ruleData,
            coroutineContext = currentCoroutineContext()
        )
        val checkJs = bookSource.loginCheckJs
        val res = kotlin.runCatching {
            analyzeUrl.getStrResponseAwait().let {
                if (!checkJs.isNullOrBlank()) { //检测书源是否已登录
                    analyzeUrl.evalJS(checkJs, it) as StrResponse
                } else {
                    it
                }
            }
        }.getOrElse { throwable ->
            if (!checkJs.isNullOrBlank()) {
                val errResponse = analyzeUrl.getErrStrResponse(throwable)
                try {
                    (analyzeUrl.evalJS(checkJs, errResponse) as StrResponse).also {
                        if (it.code() == 500) {
                            throw throwable
                        }
                    }
                } catch (_: Throwable) {
                    throw throwable
                }
            } else {
                throw throwable
            }
        }
        checkRedirect(bookSource, res)
        BookList.analyzeBookList(
            bookSource = bookSource,
            ruleData = ruleData,
            analyzeUrl = analyzeUrl,
            baseUrl = res.url,
            body = res.body,
            isSearch = true,
            isRedirect = res.raw.priorResponse?.isRedirect == true,
            filter = filter,
            shouldBreak = shouldBreak
        )
    }

    /**
     * 发现
     */

    suspend fun exploreBookAwait(
        bookSource: BookSource,
        url: String,
        page: Int? = 1,
    ): ArrayList<SearchBook> {
        if (bookSource.isJsSource()) {
            return JsSourceBook.exploreAwait(bookSource, url, page)
        }
        val ruleData = RuleData()
        val sourceUrl = bookSource.bookSourceUrl
        val exploreInfoMap = bookSource.exploreInfoMap()
        val analyzeUrl = AnalyzeUrl(
            mUrl = url,
            page = page,
            baseUrl = sourceUrl,
            source = bookSource,
            ruleData = ruleData,
            coroutineContext = currentCoroutineContext(),
            infoMap = exploreInfoMap
        )
        val checkJs = bookSource.loginCheckJs
        val res = kotlin.runCatching {
            analyzeUrl.getStrResponseAwait().let {
                if (!checkJs.isNullOrBlank()) { //检测书源是否已登录
                    analyzeUrl.evalJS(checkJs, it) as StrResponse
                } else {
                    it
                }
            }
        }.getOrElse { throwable ->
            if (!checkJs.isNullOrBlank()) {
                val errResponse = analyzeUrl.getErrStrResponse(throwable)
                try {
                    (analyzeUrl.evalJS(checkJs, errResponse) as StrResponse).also {
                        if (it.code() == 500) {
                            throw throwable
                        }
                    }
                } catch (_: Throwable) {
                    throw throwable
                }
            } else {
                throw throwable
            }
        }
        checkRedirect(bookSource, res)
        return BookList.analyzeBookList(
            bookSource = bookSource,
            ruleData = ruleData,
            analyzeUrl = analyzeUrl,
            baseUrl = res.url,
            body = res.body,
            isSearch = false
        )
    }

    /**
     * 书籍信息
     */

    suspend fun getBookInfoAwait(
        bookSource: BookSource,
        book: Book,
        canReName: Boolean = true,
    ): Book {
        if (bookSource.isJsSource()) {
            return JsSourceBook.getBookInfoAwait(bookSource, book, canReName)
        }
        book.removeAllBookType()
        book.addType(bookSource.getBookType())
        if (!book.infoHtml.isNullOrEmpty()) {
            BookInfo.analyzeBookInfo(
                bookSource = bookSource,
                book = book,
                baseUrl = book.bookUrl,
                redirectUrl = book.bookUrl,
                body = book.infoHtml,
                canReName = canReName
            )
        } else {
            val analyzeUrl = AnalyzeUrl(
                mUrl = book.bookUrl,
                baseUrl = bookSource.bookSourceUrl,
                source = bookSource,
                ruleData = book,
                coroutineContext = currentCoroutineContext()
            )
            val checkJs = bookSource.loginCheckJs
            val res = kotlin.runCatching {
                analyzeUrl.getStrResponseAwait().let {
                    if (!checkJs.isNullOrBlank()) { //检测书源是否已登录
                        analyzeUrl.evalJS(checkJs, it) as StrResponse
                    } else {
                        it
                    }
                }
            }.getOrElse { throwable ->
                if (!checkJs.isNullOrBlank()) {
                    val errResponse = analyzeUrl.getErrStrResponse(throwable)
                    try {
                        (analyzeUrl.evalJS(checkJs, errResponse) as StrResponse).also {
                            if (it.code() == 500) {
                                throw throwable
                            }
                        }
                    } catch (_: Throwable) {
                        throw throwable
                    }
                } else {
                    throw throwable
                }
            }
            checkRedirect(bookSource, res)
            BookInfo.analyzeBookInfo(
                bookSource = bookSource,
                book = book,
                baseUrl = book.bookUrl,
                redirectUrl = res.url,
                body = res.body,
                canReName = canReName
            )
        }
        return book
    }

    /**
     * 目录
     */

    suspend fun runPreUpdateJs(bookSource: BookSource, book: Book, isFromBookInfo : Boolean = false): Result<Unit> {
        return kotlin.runCatching {
            val preUpdateJs = bookSource.ruleToc?.preUpdateJs
            if (!preUpdateJs.isNullOrBlank()) {
                AnalyzeRule(book, bookSource, true, isFromBookInfo)
                    .setCoroutineContext(currentCoroutineContext())
                    .evalJS(preUpdateJs)
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("执行preUpdateJs规则失败 书源:${bookSource.bookSourceName}", it)
        }
    }

    suspend fun getChapterListAwait(
        bookSource: BookSource,
        book: Book,
        runPerJs: Boolean = false,
        isFromBookInfo : Boolean = false
    ): Result<List<BookChapter>> {
        if (bookSource.isJsSource()) {
            return JsSourceBook.getChapterListAwait(bookSource, book)
        }
        book.removeAllBookType()
        book.addType(bookSource.getBookType())
        return kotlin.runCatching {
            if (runPerJs) {
                runPreUpdateJs(bookSource, book, isFromBookInfo).getOrThrow()
            }
            if (book.bookUrl == book.tocUrl && !book.tocHtml.isNullOrEmpty()) {
                BookChapterList.analyzeChapterList(
                    bookSource = bookSource,
                    book = book,
                    baseUrl = book.tocUrl,
                    redirectUrl = book.tocUrl,
                    body = book.tocHtml,
                    isFromBookInfo = isFromBookInfo
                )
            } else {
                val analyzeUrl = AnalyzeUrl(
                    mUrl = book.tocUrl,
                    baseUrl = book.bookUrl,
                    source = bookSource,
                    ruleData = book,
                    coroutineContext = currentCoroutineContext()
                )
                val checkJs = bookSource.loginCheckJs
                val res = kotlin.runCatching {
                    analyzeUrl.getStrResponseAwait().let {
                        if (!checkJs.isNullOrBlank()) { //检测书源是否已登录
                            analyzeUrl.evalJS(checkJs, it) as StrResponse
                        } else {
                            it
                        }
                    }
                }.getOrElse { throwable ->
                    if (!checkJs.isNullOrBlank()) {
                        val errResponse = analyzeUrl.getErrStrResponse(throwable)
                        try {
                            (analyzeUrl.evalJS(checkJs, errResponse) as StrResponse).also {
                                if (it.code() == 500) {
                                    throw throwable
                                }
                            }
                        } catch (_: Throwable) {
                            throw throwable
                        }
                    } else {
                        throw throwable
                    }
                }
                checkRedirect(bookSource, res)
                BookChapterList.analyzeChapterList(
                    bookSource = bookSource,
                    book = book,
                    baseUrl = book.tocUrl,
                    redirectUrl = res.url,
                    body = res.body,
                    isFromBookInfo = isFromBookInfo
                )
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
        }
    }

    /**
     * 章节内容
     */

    suspend fun getContentAwait(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String? = null,
        needSave: Boolean = true
    ): String {
        val saveToken = if (needSave) {
            BookHelp.contentSaveToken(book, bookChapter)
        } else {
            null
        }
        if (saveToken != null && saveToken.version > 0L) {
            BookHelp.getContent(book, bookChapter, saveToken)?.let { return it }
        }
        if (bookSource.isJsSource()) {
            val content = JsSourceBook.getContentAwait(
                bookSource,
                book,
                bookChapter,
                nextChapterUrl,
                false,
            )
            if (saveToken != null) {
                val saved = BookHelp.saveContent(
                    bookSource, book, bookChapter, content, saveToken
                )
                BookHelp.getContent(book, bookChapter, saveToken)?.let { return it }
                if (!saved) throw NoStackTraceException("正文缓存已更新,请重试")
            }
            return content
        }
        if (bookChapter.isVolume && bookChapter.url.startsWith(bookChapter.title)) {
            Debug.log(bookSource.bookSourceUrl, "⇒一级目录正文不解析规则")
            return ""
        }
        val contentRule = bookSource.getContentRule()
        if (contentRule.content.isNullOrEmpty()) {
            Debug.log(bookSource.bookSourceUrl, "⇒正文规则为空,使用章节链接:${bookChapter.url}")
            return bookChapter.url
        }
        val content = if (bookChapter.url == book.bookUrl && !book.tocHtml.isNullOrEmpty()) {
            BookContent.analyzeContent(
                bookSource = bookSource,
                book = book,
                bookChapter = bookChapter,
                baseUrl = bookChapter.getAbsoluteURL(),
                redirectUrl = bookChapter.getAbsoluteURL(),
                body = book.tocHtml,
                nextChapterUrl = nextChapterUrl,
                needSave = false
            )
        } else {
            val analyzeUrl = AnalyzeUrl(
                mUrl = bookChapter.getAbsoluteURL(),
                baseUrl = book.tocUrl,
                source = bookSource,
                ruleData = book,
                chapter = bookChapter,
                coroutineContext = currentCoroutineContext()
            )
            val checkJs = bookSource.loginCheckJs
            val res = kotlin.runCatching {
                analyzeUrl.getStrResponseAwait(
                    jsStr = contentRule.webJs,
                    sourceRegex = contentRule.sourceRegex
                ).let {
                    if (!checkJs.isNullOrBlank()) { //检测书源是否已登录
                        analyzeUrl.evalJS(checkJs, it) as StrResponse
                    } else {
                        it
                    }
                }
            }.getOrElse { throwable ->
                if (!checkJs.isNullOrBlank()) {
                    val errResponse = analyzeUrl.getErrStrResponse(throwable)
                    try {
                        (analyzeUrl.evalJS(checkJs, errResponse) as StrResponse).also {
                            if (it.code() == 500) {
                                throw throwable
                            }
                        }
                    } catch (_: Throwable) {
                        throw throwable
                    }
                } else {
                    throw throwable
                }
            }
            checkRedirect(bookSource, res)
            BookContent.analyzeContent(
                bookSource = bookSource,
                book = book,
                bookChapter = bookChapter,
                baseUrl = bookChapter.getAbsoluteURL(),
                redirectUrl = res.url,
                body = res.body,
                nextChapterUrl = nextChapterUrl,
                needSave = false
            )
        }
        if (saveToken != null) {
            val saved = BookHelp.saveContent(
                bookSource,
                book,
                bookChapter,
                content,
                saveToken,
                saveChapterMetadata = true,
            )
            BookHelp.getContent(book, bookChapter, saveToken)?.let { return it }
            if (!saved) throw NoStackTraceException("正文缓存已更新,请重试")
        }
        return content
    }

    /**
     * 批量章节内容。
     *
     * 常规源走 contentBatch 规则,JS源走 getContentBatch 函数,
     * 两者都通过 java.cacheContent 回存。
     * 返回书源未回存的章节,调用方按普通单章流程兜底。
     */
    suspend fun getContentBatchAwait(
        bookSource: BookSource,
        book: Book,
        chapters: List<BookChapter>
    ): List<BookChapter> {
        if (bookSource.isJsSource()) {
            return JsSourceBook.getContentBatchAwait(bookSource, book, chapters)
        }
        return BookContent.analyzeContentBatch(
            bookSource = bookSource,
            book = book,
            chapters = chapters
        )
    }

    /**
     * 精准搜索
     */

    suspend fun preciseSearchAwait(
        bookSource: BookSource,
        name: String,
        author: String,
    ): Result<Book> {
        return kotlin.runCatching {
            currentCoroutineContext().ensureActive()
            searchBookAwait(
                bookSource, name,
                filter = { fName, fAuthor, _ -> fName == name && fAuthor == author },
                shouldBreak = { it > 0 }
            ).firstOrNull()?.let { searchBook ->
                currentCoroutineContext().ensureActive()
                return@runCatching searchBook.toBook()
            }
            throw NoStackTraceException("未搜索到 $name($author) 书籍")
        }.onFailure {
            currentCoroutineContext().ensureActive()
        }
    }

    /**
     * 检测重定向
     */
    private fun checkRedirect(bookSource: BookSource, response: StrResponse) {
        response.raw.priorResponse?.let {
            if (it.isRedirect) {
                Debug.log(bookSource.bookSourceUrl, "≡检测到重定向(${it.code})")
                Debug.log(bookSource.bookSourceUrl, "┌重定向后地址")
                Debug.log(bookSource.bookSourceUrl, "└${response.url}")
            }
        }
    }

}
