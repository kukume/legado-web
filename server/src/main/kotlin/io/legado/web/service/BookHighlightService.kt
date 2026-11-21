package io.legado.web.service

import io.legado.web.auth.BadRequestException
import io.legado.web.auth.NotFoundException
import io.legado.web.db.BookHighlights
import io.legado.web.db.Books
import io.legado.web.db.dbQuery
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

data class BookHighlightDto(
    val id: Long = 0,
    val bookId: Long = 0,
    val chapterIndex: Int = 0,
    val chapterName: String = "",
    /** 章节正文中的字符偏移（起点，含）：正文按段落拼接，每段后面一个换行 */
    val chapterPos: Int = 0,
    /** 终点（不含） */
    val chapterPosEnd: Int = 0,
    val bookText: String = "",
    /** HighlightStyle 的 JSON（与 App 相同） */
    val style: String = "",
    val note: String = "",
    val createdAt: Long = 0,
)

data class SaveHighlightRequest(
    val chapterIndex: Int = 0,
    val chapterName: String = "",
    val chapterPos: Int = 0,
    val chapterPosEnd: Int = 0,
    val bookText: String = "",
    val style: String = "",
    val note: String = "",
)

/** 修改样式或备注（为 null 的不修改）；位置在正文变化后重新定位时也会更新 */
data class UpdateHighlightRequest(
    val style: String? = null,
    val note: String? = null,
    val chapterPos: Int? = null,
    val chapterPosEnd: Int? = null,
)

/**
 * 手动标注：选中正文添加高亮（可写备注），与 App 的 BookHighlight 对应
 */
class BookHighlightService {

    private fun ResultRow.toDto() = BookHighlightDto(
        id = this[BookHighlights.id].value,
        bookId = this[BookHighlights.bookId],
        chapterIndex = this[BookHighlights.chapterIndex],
        chapterName = this[BookHighlights.chapterName],
        chapterPos = this[BookHighlights.chapterPos],
        chapterPosEnd = this[BookHighlights.chapterPosEnd],
        bookText = this[BookHighlights.bookText],
        style = this[BookHighlights.style],
        note = this[BookHighlights.note],
        createdAt = this[BookHighlights.createdAt],
    )

    private fun requireBook(userId: Long, bookId: Long) {
        Books.selectAll().where { (Books.id eq bookId) and (Books.userId eq userId) }.firstOrNull()
            ?: throw NotFoundException("书籍不存在")
    }

    private fun check(text: String?, note: String?, style: String?) {
        if ((text?.length ?: 0) > MAX_TEXT || (note?.length ?: 0) > MAX_TEXT) throw BadRequestException("内容过长（最多 $MAX_TEXT 字）")
        if ((style?.length ?: 0) > MAX_STYLE) throw BadRequestException("样式过长")
    }

    suspend fun list(userId: Long, bookId: Long): List<BookHighlightDto> = dbQuery {
        requireBook(userId, bookId)
        BookHighlights.selectAll().where { (BookHighlights.userId eq userId) and (BookHighlights.bookId eq bookId) }
            .orderBy(BookHighlights.chapterIndex to SortOrder.ASC, BookHighlights.chapterPos to SortOrder.ASC)
            .map { it.toDto() }
    }

    suspend fun add(userId: Long, bookId: Long, req: SaveHighlightRequest): BookHighlightDto {
        if (req.chapterIndex < 0 || req.chapterPos < 0 || req.chapterPosEnd <= req.chapterPos) throw BadRequestException("位置无效")
        if (req.bookText.isEmpty()) throw BadRequestException("没有选中文字")
        check(req.bookText, req.note, req.style)
        val now = System.currentTimeMillis()
        return dbQuery {
            requireBook(userId, bookId)
            val id = BookHighlights.insertAndGetId {
                it[BookHighlights.userId] = userId
                it[BookHighlights.bookId] = bookId
                it[chapterIndex] = req.chapterIndex
                it[chapterName] = req.chapterName.take(500)
                it[chapterPos] = req.chapterPos
                it[chapterPosEnd] = req.chapterPosEnd
                it[bookText] = req.bookText
                it[style] = req.style
                it[note] = req.note
                it[createdAt] = now
            }
            BookHighlights.selectAll().where { BookHighlights.id eq id }.first().toDto()
        }
    }

    suspend fun update(userId: Long, id: Long, req: UpdateHighlightRequest) {
        check(null, req.note, req.style)
        val n = dbQuery {
            BookHighlights.update({ (BookHighlights.id eq id) and (BookHighlights.userId eq userId) }) {
                req.style?.let { s -> it[style] = s }
                req.note?.let { s -> it[note] = s }
                if (req.chapterPos != null && req.chapterPosEnd != null && req.chapterPos >= 0 && req.chapterPosEnd > req.chapterPos) {
                    it[chapterPos] = req.chapterPos
                    it[chapterPosEnd] = req.chapterPosEnd
                }
            }
        }
        if (n == 0) throw NotFoundException("标注不存在")
    }

    suspend fun delete(userId: Long, ids: List<Long>) {
        if (ids.isEmpty()) return
        dbQuery { BookHighlights.deleteWhere { (BookHighlights.userId eq userId) and (BookHighlights.id inList ids) } }
    }

    companion object {
        const val MAX_TEXT = 2000
        const val MAX_STYLE = 4000
    }
}
