package io.legado.app.model.localBook

import com.script.rhino.RhinoScriptEngine
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.utils.StringUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.nio.charset.Charset
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.math.min

/**
 * TXT 目录解析，移植自 App 的 TextFile（getTocRule / analyze），分章结果与 App 一致。
 *
 * 与 App 的差别：
 * - 服务端上传时已经把文件转成 UTF-8，这里按 [charset]（默认 UTF-8）解析，不再识别编码
 * - 目录规则由调用方传入（当前用户启用的规则，按序号排序），不读数据库
 * - 选中的规则同样保存在 book.tocUrl（规则 + [spaceChars] + js 处理），与 App 备份兼容
 */
class TextFile(
    private val file: File,
    private val book: Book,
    private val rules: List<TxtTocRule>,
    private val charset: Charset = Charsets.UTF_8,
    private val splitLongChapter: Boolean = true,
) {

    @Suppress("ConstPropertyName")
    companion object {
        const val spaceChars = "🫅🈳🏻"
        private val log = LoggerFactory.getLogger(TextFile::class.java)

        /** 解析目录；book.tocUrl 为空时自动选择规则并写回 book.tocUrl，同时设置 book.intro（前言）和 book.wordCount */
        fun getChapterList(
            file: File,
            book: Book,
            rules: List<TxtTocRule>,
            splitLongChapter: Boolean = true,
        ): ArrayList<BookChapter> = TextFile(file, book, rules, splitLongChapter = splitLongChapter).getChapterList()
    }

    private val blank: Byte = 0x0a

    //默认从文件中获取数据的长度
    private val bufferSize = 512000

    //没有标题的时候，每个章节的最大长度
    private val maxLengthWithNoToc = 10 * 1024

    //使用正则划分目录，每个章节的最大允许长度
    private val maxLengthWithToc = 102400

    //选中更好的目录规则判断阈值
    private val overRuleCount = 2
    private val replaceBook = ReplaceBook(book.name, book.author, book.origin, book.originName, book.bookUrl)

    class MutableRef<String>(var value: String)

    private val lastVolumeTitle = MutableRef("")

    private fun openStream() = FileInputStream(file)

    /**
     * 获取目录
     */
    fun getChapterList(): ArrayList<BookChapter> {
        if (book.tocUrl.isBlank()) {
            openStream().use { bis ->
                val buffer = ByteArray(bufferSize)
                val length = bis.read(buffer)
                if (length == -1) throw IllegalStateException("Unexpected Empty Txt File")
                val blockContent = String(buffer, 0, length, charset)
                val tocRule = getTocRule(blockContent)
                book.tocUrl = tocRule?.let { it.rule + spaceChars + it.replacement } ?: ""
            }
        }
        val (toc, wordCount) = analyze(book.tocUrl.split(spaceChars, limit = 2))
        book.wordCount = StringUtils.wordCountFormat(wordCount)
        toc.forEachIndexed { index, bookChapter ->
            bookChapter.index = index
            bookChapter.bookUrl = book.bookUrl
        }
        return toc
    }

    /**
     * 按规则解析目录
     */
    private fun analyze(rr: List<String>): Pair<ArrayList<BookChapter>, Int> {
        if (rr[0].isEmpty()) {
            return analyze()
        }
        val pattern = try {
            rr[0].toPattern(Pattern.MULTILINE)
        } catch (e: PatternSyntaxException) {
            log.warn("TXT目录规则正则语法错误: {}", e.message)
            return analyze()
        }
        val jsStr = rr.getOrNull(1)
        lastVolumeTitle.value = ""
        val toc = arrayListOf<BookChapter>()
        var bookWordCount = 0
        openStream().use { bis ->
            var blockContent: String
            //加载章节
            var curOffset: Long = 0
            //读取的长度
            var length: Int
            var lastChapterWordCount = 0
            val buffer = ByteArray(bufferSize)
            var bufferStart = 3
            bis.read(buffer, 0, 3)
            if (hasBom(buffer)) {
                bufferStart = 0
                curOffset = 3
            }
            //获取文件中的数据到buffer，直到没有数据为止
            while (bis.read(
                    buffer, bufferStart, bufferSize - bufferStart
                ).also { length = it } > 0
            ) {
                var end = bufferStart + length
                if (end == bufferSize) {
                    for (i in bufferStart + length - 1 downTo 0) {
                        if (buffer[i] == blank) {
                            end = i
                            break
                        }
                    }
                }
                //将数据转换成String, 不能超过length
                blockContent = String(buffer, 0, end, charset)
                buffer.copyInto(buffer, 0, end, bufferStart + length)
                bufferStart = bufferStart + length - end
                length = end
                //当前Block下使过的String的指针
                var seekPos = 0
                //进行正则匹配
                val matcher: Matcher = pattern.matcher(blockContent)
                //如果存在相应章节
                while (matcher.find()) { //获取匹配到的字符在字符串中的起始位置
                    val chapterStart = matcher.start()
                    //获取章节内容
                    val chapterContent = blockContent.substring(seekPos, chapterStart)
                    val chapterContentLength = chapterContent.length
                    val chapterLength = chapterContent.toByteArray(charset).size.toLong()
                    val titleLength = matcher.group().toByteArray(charset).size.toLong()
                    val lastStart = toc.lastOrNull()?.start ?: curOffset
                    if (splitLongChapter && curOffset + chapterLength - lastStart > maxLengthWithToc) {
                        //章节字数太多进行拆分
                        val lastTitle = toc.lastOrNull()?.let {
                            it.end = it.start
                            it.isVolume = true
                            lastVolumeTitle.value = it.title
                            it.tag = null
                            it.title
                        }
                        val title = replacement(
                            matcher.group(),
                            jsStr,
                            toc,
                            lastTitle,
                            chapterContentLength
                        ).takeIf { it.isNotEmpty() } ?: continue
                        val (chapters, wordCount) = analyze(lastStart, curOffset + chapterLength)
                        lastTitle?.let {
                            chapters.forEachIndexed { index, bookChapter ->
                                bookChapter.title = "$lastTitle(${index + 1})"
                            }
                        }
                        toc.addAll(chapters)
                        bookWordCount += wordCount
                        //创建当前章节
                        val curChapter = BookChapter()
                        curChapter.title = title
                        curChapter.start = curOffset + chapterLength
                        curChapter.end = curChapter.start
                        toc.add(curChapter)
                        lastChapterWordCount = 0
                    } else if (seekPos == 0 && chapterStart != 0) {
                        /**
                         * 如果 seekPos == 0 && chapterStart != 0 表示当前block处前面有一段内容
                         * 第一种情况一定是序章 第二种情况是上一个章节的内容
                         */
                        if (toc.isEmpty()) { //如果当前没有章节，那么就是序章
                            //加入简介
                            if (chapterContent.isNotBlank()) {
                                val title = replacement("前言", jsStr, toc)
                                if (title.isNotEmpty()) {
                                    //如果js把"前言"处理成空了，那么就不要前言,并且前言内容会全部放到简介里面去
                                    val qyChapter = BookChapter()
                                    qyChapter.title = title
                                    qyChapter.start = curOffset
                                    qyChapter.end = curOffset + chapterLength
                                    qyChapter.wordCount =
                                        StringUtils.wordCountFormat(chapterContentLength)
                                    toc.add(qyChapter)
                                }
                                book.intro = if (chapterContentLength > 600 && title.isNotEmpty()) {
                                    chapterContent.take(600)
                                } else {
                                    chapterContent
                                }
                            }
                            val title = replacement(
                                matcher.group(),
                                jsStr,
                                toc
                            ).takeIf { it.isNotEmpty() } ?: continue
                            //创建当前章节
                            val curChapter = BookChapter()
                            curChapter.title = title
                            curChapter.start = curOffset + chapterLength + titleLength
                            curChapter.end = curChapter.start
                            toc.add(curChapter)
                        } else { //否则就block分割之后，上一个章节的剩余内容
                            //获取上一章节
                            val lastChapter = toc.last()
                            val title = replacement(
                                matcher.group(),
                                jsStr,
                                toc,
                                lastChapter.title,
                                chapterContentLength
                            ).takeIf { it.isNotEmpty() } ?: continue
                            if (chapterContent.isBlank()) {
                                lastChapter.isVolume = true
                                lastVolumeTitle.value = lastChapter.title
                            } else {
                                lastChapter.isVolume = false
                            }
                            //将当前段落添加上一章去
                            lastChapter.end = lastChapter.end!! + chapterLength
                            lastChapterWordCount += chapterContentLength
                            lastChapter.wordCount =
                                StringUtils.wordCountFormat(lastChapterWordCount)
                            //创建当前章节
                            val curChapter = BookChapter()
                            curChapter.title = title
                            curChapter.start = lastChapter.end!! + titleLength
                            curChapter.end = curChapter.start
                            toc.add(curChapter)
                        }
                        bookWordCount += chapterContentLength
                        lastChapterWordCount = 0
                    } else {
                        if (toc.isNotEmpty()) { //获取章节内容
                            //获取上一章节
                            val lastChapter = toc.last()
                            val title = replacement(
                                matcher.group(),
                                jsStr,
                                toc,
                                lastChapter.title,
                                chapterContentLength
                            ).takeIf { it.isNotEmpty() }
                                ?: continue
                            if (chapterContent.isBlank()) {
                                lastChapter.isVolume = true
                                lastVolumeTitle.value = lastChapter.title
                            } else {
                                lastChapter.isVolume = false
                            }
                            lastChapter.end =
                                lastChapter.start!! + chapterLength
                            lastChapter.wordCount =
                                StringUtils.wordCountFormat(chapterContentLength)
                            //创建当前章节
                            val curChapter = BookChapter()
                            curChapter.title = title
                            curChapter.start = lastChapter.end!! + titleLength
                            curChapter.end = curChapter.start
                            toc.add(curChapter)
                        } else { //如果章节不存在则创建章节
                            val title = replacement(matcher.group(), jsStr, toc).takeIf { it.isNotEmpty() }
                                ?: continue
                            val curChapter = BookChapter()
                            curChapter.title = title
                            curChapter.start = curOffset + titleLength
                            curChapter.end = curChapter.start
                            curChapter.wordCount =
                                StringUtils.wordCountFormat(chapterContentLength)
                            toc.add(curChapter)
                        }
                        bookWordCount += chapterContentLength
                        lastChapterWordCount = 0
                    }
                    //设置指针偏移
                    seekPos += chapterContentLength + matcher.group().length
                }
                val wordCount = blockContent.length - seekPos
                bookWordCount += wordCount
                lastChapterWordCount += wordCount
                //block的偏移点
                curOffset += length.toLong()
                //设置上一章的结尾
                toc.lastOrNull()?.let {
                    it.end = curOffset
                    it.wordCount = StringUtils.wordCountFormat(lastChapterWordCount)
                }
            }
            toc.lastOrNull()?.let { chapter ->
                //章节字数太多进行拆分
                if (splitLongChapter && chapter.end!! - chapter.start!! > maxLengthWithToc) {
                    val end = chapter.end!!
                    chapter.end = chapter.start
                    chapter.isVolume = true
                    chapter.tag = null
                    val lastTitle = chapter.title
                    lastVolumeTitle.value = lastTitle
                    val (chapters, _) = analyze(
                        chapter.start!!, end
                    )
                    chapters.forEachIndexed { index, bookChapter ->
                        bookChapter.title = "$lastTitle(${index + 1})"
                    }
                    toc.addAll(chapters)
                }
            }
        }
        return toc to bookWordCount
    }

    /**
     * 无规则拆分目录
     */
    private fun analyze(
        fileStart: Long = 0L, fileEnd: Long = Long.MAX_VALUE
    ): Pair<ArrayList<BookChapter>, Int> {
        val toc = arrayListOf<BookChapter>()
        var bookWordCount = 0
        openStream().use { bis ->
            //block的个数
            var blockPos = 0
            //加载章节
            var curOffset: Long = 0
            var chapterPos = 0
            //读取的长度
            var length = 0
            var lastChapterWordCount = 0
            val buffer = ByteArray(bufferSize)
            var bufferStart = 3
            if (fileStart == 0L) {
                bis.read(buffer, 0, 3)
                if (hasBom(buffer)) {
                    bufferStart = 0
                    curOffset = 3
                }
            } else {
                bis.skip(fileStart)
                curOffset = fileStart
                bufferStart = 0
            }
            //获取文件中的数据到buffer，直到没有数据为止
            while (
                fileEnd - curOffset - bufferStart > 0 &&
                bis.read(
                    buffer,
                    bufferStart,
                    min((bufferSize - bufferStart).toLong(), fileEnd - curOffset - bufferStart).toInt()
                ).also { length = it } > 0
            ) {
                blockPos++
                //章节在buffer的偏移量
                var chapterOffset = 0
                //当前剩余可分配的长度
                length += bufferStart
                var strLength = length
                //分章的位置
                chapterPos = 0
                while (strLength > 0) {
                    chapterPos++
                    //是否长度超过一章
                    if (strLength > maxLengthWithNoToc) { //在buffer中一章的终止点
                        var end = length
                        //寻找换行符作为终止点
                        for (i in chapterOffset + maxLengthWithNoToc until length) {
                            if (buffer[i] == blank) {
                                end = i
                                break
                            }
                        }
                        val content = String(buffer, chapterOffset, end - chapterOffset, charset)
                        bookWordCount += content.length
                        lastChapterWordCount = content.length
                        val chapter = BookChapter()
                        chapter.title = "第${blockPos}章($chapterPos)"
                        chapter.start = toc.lastOrNull()?.end ?: curOffset
                        chapter.end = chapter.start!! + end - chapterOffset
                        chapter.wordCount = StringUtils.wordCountFormat(content.length)
                        toc.add(chapter)
                        //减去已经被分配的长度
                        strLength -= (end - chapterOffset)
                        //设置偏移的位置
                        chapterOffset = end
                    } else {
                        buffer.copyInto(buffer, 0, length - strLength, length)
                        length -= strLength
                        bufferStart = strLength
                        strLength = 0
                    }
                }
                //block的偏移点
                curOffset += length.toLong()
            }
            //设置结尾章节
            val content = String(buffer, 0, bufferStart, charset)
            bookWordCount += content.length
            if (bufferStart > 100 || toc.isEmpty()) {
                val chapter = BookChapter()
                chapter.title = "第${blockPos}章(${chapterPos})"
                chapter.start = toc.lastOrNull()?.end ?: curOffset
                chapter.end = chapter.start!! + bufferStart
                chapter.wordCount = StringUtils.wordCountFormat(content.length)
                toc.add(chapter)
            } else {
                val wordCount = lastChapterWordCount + content.length
                toc.lastOrNull()?.let {
                    it.end = it.end!! + bufferStart
                    it.wordCount = StringUtils.wordCountFormat(wordCount)
                }
            }
        }
        return toc to bookWordCount
    }

    /**
     * 获取合适的目录规则
     */
    private fun getTocRule(content: String): TxtTocRule? {
        var maxNum = -1
        var mTocRule: TxtTocRule? = null
        for (tocRule in rules) {
            val pattern = try {
                tocRule.rule.toPattern(Pattern.MULTILINE)
            } catch (e: PatternSyntaxException) {
                log.warn("TXT目录规则正则语法错误:{} {}", tocRule.name, e.message)
                continue
            }
            val matcher = pattern.matcher(content)
            var start = 0
            var csNum = 0
            var numE = 0
            var lastTitle: String? = null
            while (matcher.find()) {
                val contentLength = matcher.start() - start
                if (start == 0 || contentLength > 1000) {
                    val title = replacement(matcher.group(), tocRule.replacement, csNum, lastTitle, contentLength)
                    if (title.isNotEmpty()) {
                        lastTitle = title
                        csNum++
                    }
                    start = matcher.end()
                } else if (contentLength < 100) {
                    numE++ //这种不足100字的一般被识别为卷，即错误识别，正常章节数小于卷3倍的不选
                }
            }
            if (csNum >= numE * 3 && (csNum > maxNum + overRuleCount)) { //后面的规则匹配数量没超过最大值2个，那么依旧用前面那个
                maxNum = csNum
                mTocRule = tocRule
                if (maxNum > 70) {
                    break
                } //能获取60个章节，说明这个规则能基本匹配，并且排在前面，所以不考虑后面的规则
            }
        }
        return mTocRule
    }

    /** js 处理里可以读取的书籍信息（只读） */
    data class ReplaceBook(
        val name: String,
        val author: String,
        val origin: String,
        val originName: String,
        val bookUrl: String,
    )

    @Suppress("unused")
    class JsExtensions(val lastVolumeTitle: MutableRef<String>, val toc: ArrayList<BookChapter>? = null) {
        fun putVolume(title: String) {
            lastVolumeTitle.value = title
            if (toc != null) {
                val start = toc.lastOrNull()?.end ?: 0
                toc.add(BookChapter(title = title, isVolume = true, start = start, end = start))
            }
        }
    }

    /**
     * 净化标题
     */
    private fun replacement(
        content: String,
        jsStr: String?,
        toc: ArrayList<BookChapter>,
        prevTitle: String? = null,
        prevLength: Int = -1
    ): String {
        if (jsStr.isNullOrBlank()) {
            return content
        }
        return evalJs(content, jsStr, toc.size + 1, prevTitle, prevLength, toc)
    }

    private fun replacement(
        content: String,
        jsStr: String?,
        index: Int,
        prevTitle: String? = null,
        prevLength: Int = -1
    ): String {
        if (jsStr.isNullOrBlank()) {
            return content
        }
        return evalJs(content, jsStr, index + 1, prevTitle, prevLength)
    }

    private fun evalJs(
        content: String,
        jsStr: String,
        index: Int,
        prevTitle: String?,
        prevLength: Int = -1,
        toc: ArrayList<BookChapter>? = null
    ): String {
        return RhinoScriptEngine.eval(jsStr) {
            this["result"] = content
            this["book"] = replaceBook
            this["index"] = index
            this["prevTitle"] = prevTitle
            this["prevLength"] = prevLength
            this["lastVolumeTitle"] = lastVolumeTitle.value
            this["java"] = JsExtensions(lastVolumeTitle, toc)
        }.toString()
    }

    private fun hasBom(bytes: ByteArray): Boolean =
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
}
