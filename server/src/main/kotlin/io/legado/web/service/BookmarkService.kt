package io.legado.web.service

import io.legado.web.auth.BadRequestException
import io.legado.web.auth.NotFoundException
import io.legado.web.db.Bookmarks
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

data class BookmarkDto(
    val id: Long = 0,
    val bookId: Long = 0,
    val chapterIndex: Int = 0,
    val chapterName: String = "",
    /** 章内位置（比例，0..10000，与阅读进度相同） */
    val chapterPos: Int = 0,
    /** 选中的正文 */
    val bookText: String = "",
    /** 备注 */
    val content: String = "",
    val createdAt: Long = 0,
)

data class SaveBookmarkRequest(
    val chapterIndex: Int = 0,
    val chapterName: String = "",
    val chapterPos: Int = 0,
    val bookText: String = "",
    val content: String = "",
)

data class UpdateBookmarkRequest(val content: String = "")

/**
 * 书签：选中正文添加，记录章节、章内位置和选中的文字
 */
class BookmarkService {

    private fun ResultRow.toDto() = BookmarkDto(
        id = this[Bookmarks.id].value,
        bookId = this[Bookmarks.bookId],
        chapterIndex = this[Bookmarks.chapterIndex],
        chapterName = this[Bookmarks.chapterName],
        chapterPos = this[Bookmarks.chapterPos],
        bookText = this[Bookmarks.bookText],
        content = this[Bookmarks.content],
        createdAt = this[Bookmarks.createdAt],
    )

    private fun requireBook(userId: Long, bookId: Long) {
        Books.selectAll().where { (Books.id eq bookId) and (Books.userId eq userId) }.firstOrNull()
            ?: throw NotFoundException("书籍不存在")
    }

    suspend fun list(userId: Long, bookId: Long): List<BookmarkDto> = dbQuery {
        requireBook(userId, bookId)
        Bookmarks.selectAll().where { (Bookmarks.userId eq userId) and (Bookmarks.bookId eq bookId) }
            .orderBy(Bookmarks.chapterIndex to SortOrder.ASC, Bookmarks.chapterPos to SortOrder.ASC)
            .map { it.toDto() }
    }

    suspend fun add(userId: Long, bookId: Long, req: SaveBookmarkRequest): BookmarkDto {
        if (req.chapterIndex < 0) throw BadRequestException("章节无效")
        if (req.bookText.length > MAX_TEXT || req.content.length > MAX_TEXT) throw BadRequestException("内容过长（最多 $MAX_TEXT 字）")
        val now = System.currentTimeMillis()
        return dbQuery {
            requireBook(userId, bookId)
            val id = Bookmarks.insertAndGetId {
                it[Bookmarks.userId] = userId
                it[Bookmarks.bookId] = bookId
                it[chapterIndex] = req.chapterIndex
                it[chapterName] = req.chapterName.take(500)
                it[chapterPos] = req.chapterPos.coerceIn(0, MAX_POS)
                it[bookText] = req.bookText
                it[content] = req.content
                it[createdAt] = now
            }
            Bookmarks.selectAll().where { Bookmarks.id eq id }.first().toDto()
        }
    }

    suspend fun update(userId: Long, id: Long, req: UpdateBookmarkRequest) {
        if (req.content.length > MAX_TEXT) throw BadRequestException("备注过长（最多 $MAX_TEXT 字）")
        val n = dbQuery {
            Bookmarks.update({ (Bookmarks.id eq id) and (Bookmarks.userId eq userId) }) { it[content] = req.content }
        }
        if (n == 0) throw NotFoundException("书签不存在")
    }

    suspend fun delete(userId: Long, ids: List<Long>) {
        if (ids.isEmpty()) return
        dbQuery { Bookmarks.deleteWhere { (Bookmarks.userId eq userId) and (Bookmarks.id inList ids) } }
    }

    companion object {
        const val MAX_TEXT = 2000
        const val MAX_POS = 10000
    }
}
