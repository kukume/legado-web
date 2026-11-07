package io.legado.web.service

import io.legado.web.auth.BadRequestException
import io.legado.web.db.Books
import io.legado.web.db.ReadingStats
import io.legado.web.db.dbQuery
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** 阅读页定时上报的阅读时长、字数（增量） */
data class ReadingReport(
    /** 阅读者本地日期 yyyy-MM-dd（按用户所在时区统计“今天”） */
    val day: String = "",
    val bookId: Long = 0,
    val seconds: Int = 0,
    val chars: Int = 0,
)

data class DayStat(val day: String, val seconds: Long, val chars: Long)
data class BookStat(val bookId: Long, val name: String, val author: String, val seconds: Long, val chars: Long)

data class ReadingSummary(
    val totalSeconds: Long,
    val totalChars: Long,
    /** 有阅读记录的天数 */
    val totalDays: Int,
    /** from 起每一天的统计（没有阅读的日子不返回） */
    val days: List<DayStat>,
    /** 阅读时间最长的书 */
    val books: List<BookStat>,
)

/**
 * 阅读统计：按用户、日期、书籍累计阅读时长和字数
 */
class ReadingStatsService {

    suspend fun report(userId: Long, r: ReadingReport) {
        if (!DAY.matches(r.day)) throw BadRequestException("日期格式错误")
        if (r.bookId <= 0) throw BadRequestException("书籍无效")
        // 每次上报的是一小段时间内的增量，限制范围防止异常数据
        val seconds = r.seconds.coerceIn(0, 900)
        val chars = r.chars.coerceIn(0, 500_000)
        if (seconds == 0 && chars == 0) return
        val where = (ReadingStats.userId eq userId) and (ReadingStats.day eq r.day) and (ReadingStats.bookId eq r.bookId)
        // 在数据库里直接累加（不先读出来再写回）：多个端（网页、IDE 插件）同时上报时不会互相覆盖
        fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.add() = ReadingStats.update({ where }) {
            it[ReadingStats.seconds] = ReadingStats.seconds + seconds.toLong()
            it[ReadingStats.chars] = ReadingStats.chars + chars.toLong()
        }
        if (dbQuery { add() } > 0) return
        try {
            dbQuery {
                ReadingStats.insert {
                    it[ReadingStats.userId] = userId
                    it[day] = r.day
                    it[bookId] = r.bookId
                    it[ReadingStats.seconds] = seconds.toLong()
                    it[ReadingStats.chars] = chars.toLong()
                }
            }
        } catch (e: org.jetbrains.exposed.v1.exceptions.ExposedSQLException) {
            // 同时有另一端插入了这一行（主键冲突）：改为累加。插入失败的事务已回滚，在新事务里累加
            dbQuery { add() }
        }
    }

    suspend fun summary(userId: Long, from: String): ReadingSummary {
        if (!DAY.matches(from)) throw BadRequestException("日期格式错误")
        return dbQuery {
            val rows = ReadingStats.selectAll().where { ReadingStats.userId eq userId }.toList()
            val days = rows.filter { it[ReadingStats.day] >= from }
                .groupBy { it[ReadingStats.day] }
                .map { (d, rs) -> DayStat(d, rs.sumOf { it[ReadingStats.seconds] }, rs.sumOf { it[ReadingStats.chars] }) }
                .sortedBy { it.day }
            val byBook = rows.groupBy { it[ReadingStats.bookId] }
                .mapValues { (_, rs) -> rs.sumOf { it[ReadingStats.seconds] } to rs.sumOf { it[ReadingStats.chars] } }
            val top = byBook.entries.sortedByDescending { it.value.first }.take(10)
            val names = if (top.isEmpty()) emptyMap() else Books.select(Books.id, Books.name, Books.author)
                .where { (Books.userId eq userId) and (Books.id inList top.map { it.key }) }
                .associate { it[Books.id].value to (it[Books.name] to it[Books.author]) }
            ReadingSummary(
                totalSeconds = rows.sumOf { it[ReadingStats.seconds] },
                totalChars = rows.sumOf { it[ReadingStats.chars] },
                totalDays = rows.map { it[ReadingStats.day] }.distinct().size,
                days = days,
                books = top.map { (id, v) ->
                    val (name, author) = names[id] ?: ("（已删除的书）" to "")
                    BookStat(id, name, author, v.first, v.second)
                },
            )
        }
    }

    companion object {
        private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
