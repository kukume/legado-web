package io.legado.web.service

import com.google.gson.annotations.SerializedName
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.book.ContentHelp
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.NotFoundException
import io.legado.web.db.ReplaceRules
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
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * 与阅读 App 替换规则 JSON 格式兼容
 */
data class ReplaceRuleDto(
    val id: Long = 0,
    val name: String = "",
    val group: String? = null,
    val pattern: String = "",
    val replacement: String = "",
    val scope: String? = null,
    val scopeTitle: Boolean = false,
    val scopeContent: Boolean = true,
    val excludeScope: String? = null,
    @SerializedName(value = "isEnabled", alternate = ["enabled"])
    val isEnabled: Boolean = true,
    val isRegex: Boolean = true,
    val order: Int = 0,
)

class ReplaceRuleService {

    private companion object {
        val spaceRegex = Regex("\\s+")
        val imgTagRegex = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
        /** 图片占位符：私用区字符，重新分段不会改动 */
        const val IMG_MARK = "\uE000"
        const val IMG_MARK_END = "\uE001"
        val imgMarkRegex = Regex("$IMG_MARK(\\d+)$IMG_MARK_END")
    }

    private val cache = ConcurrentHashMap<Long, List<ReplaceRuleDto>>()

    private fun ResultRow.toDto() = ReplaceRuleDto(
        id = this[ReplaceRules.id].value,
        name = this[ReplaceRules.name],
        group = this[ReplaceRules.groupName],
        pattern = this[ReplaceRules.pattern],
        replacement = this[ReplaceRules.replacement],
        scope = this[ReplaceRules.scope],
        scopeTitle = this[ReplaceRules.scopeTitle],
        scopeContent = this[ReplaceRules.scopeContent],
        excludeScope = this[ReplaceRules.excludeScope],
        isEnabled = this[ReplaceRules.enabled],
        isRegex = this[ReplaceRules.isRegex],
        order = this[ReplaceRules.order],
    )

    suspend fun list(userId: Long): List<ReplaceRuleDto> = dbQuery {
        ReplaceRules.selectAll().where { ReplaceRules.userId eq userId }
            .orderBy(ReplaceRules.order to SortOrder.ASC, ReplaceRules.id to SortOrder.ASC)
            .map { it.toDto() }
    }

    suspend fun save(userId: Long, dto: ReplaceRuleDto): ReplaceRuleDto {
        if (dto.pattern.isEmpty()) throw BadRequestException("替换规则不能为空")
        if (dto.isRegex) {
            runCatching { Regex(dto.pattern) }.onFailure { throw BadRequestException("正则错误: ${it.message}") }
        }
        val id = dbQuery {
            if (dto.id == 0L) {
                ReplaceRules.insertAndGetId {
                    it[ReplaceRules.userId] = userId
                    fill(it, dto)
                }.value
            } else {
                val n = ReplaceRules.update({ (ReplaceRules.id eq dto.id) and (ReplaceRules.userId eq userId) }) {
                    fill(it, dto)
                }
                if (n == 0) throw NotFoundException()
                dto.id
            }
        }
        cache.remove(userId)
        return dto.copy(id = id)
    }

    private fun fill(it: org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>, dto: ReplaceRuleDto) {
        it[ReplaceRules.name] = dto.name.take(255)
        it[ReplaceRules.groupName] = dto.group?.take(255)
        it[ReplaceRules.pattern] = dto.pattern
        it[ReplaceRules.replacement] = dto.replacement
        it[ReplaceRules.scope] = dto.scope
        it[ReplaceRules.scopeTitle] = dto.scopeTitle
        it[ReplaceRules.scopeContent] = dto.scopeContent
        it[ReplaceRules.excludeScope] = dto.excludeScope
        it[ReplaceRules.enabled] = dto.isEnabled
        it[ReplaceRules.isRegex] = dto.isRegex
        it[ReplaceRules.order] = dto.order
    }

    suspend fun delete(userId: Long, ids: List<Long>) {
        dbQuery { ReplaceRules.deleteWhere { (ReplaceRules.userId eq userId) and (ReplaceRules.id inList ids) } }
        cache.remove(userId)
    }

    suspend fun import(userId: Long, json: String): Int {
        val list = GSON.fromJsonArray<ReplaceRuleDto>(json.trim()).getOrElse {
            GSON.fromJson(json, ReplaceRuleDto::class.java)?.let { r -> listOf(r) }
                ?: throw BadRequestException("格式错误")
        }
        var n = 0
        list.filter { it.pattern.isNotEmpty() }.forEach {
            runCatching { save(userId, it.copy(id = 0)) }.onSuccess { n++ }
        }
        return n
    }

    private suspend fun enabledRules(userId: Long): List<ReplaceRuleDto> =
        cache[userId] ?: list(userId).filter { it.isEnabled }.also { cache[userId] = it }

    /**
     * 与 App 相同：范围（排除范围）包含书名或书源地址即匹配。
     * 不能按逗号拆分后逐个比较，书名本身可能含有逗号（如“街头算命，我看上了皇朝女帝”）
     */
    private fun inScope(rule: ReplaceRuleDto, book: Book): Boolean {
        val mentions = { s: String -> (book.name.isNotEmpty() && s.contains(book.name)) || (book.origin.isNotEmpty() && s.contains(book.origin)) }
        val scope = rule.scope
        if (!scope.isNullOrBlank() && !mentions(scope)) return false
        val exclude = rule.excludeScope
        if (!exclude.isNullOrBlank() && mentions(exclude)) return false
        return true
    }

    /**
     * 正文处理，顺序与 App 的 ContentProcessor 相同：删除重复标题 → 重新分段 → 替换规则。
     * 删除重复标题默认开启、重新分段默认关闭，都按书保存在 readConfig 里
     */
    suspend fun apply(userId: Long, book: Book, title: String, content: String): String {
        val config = book.readConfig
        var text = if (config?.removeSameTitle != false) removeSameTitle(book.name, title, content) else content
        if (config?.reSegment == true && book.type and BookType.image == 0) text = reSegment(text, title)
        val rules = enabledRules(userId).filter { it.scopeContent && inScope(it, book) }
        for (rule in rules) {
            text = try {
                if (rule.isRegex) {
                    Regex(rule.pattern).replace(TimeoutCharSequence(text, 3000), rule.replacement)
                } else {
                    text.replace(rule.pattern, rule.replacement)
                }
            } catch (_: RegexTimeout) {
                text
            } catch (_: Exception) {
                text
            }
        }
        return text
    }

    /**
     * 删除正文开头与章节名相同的一行，与 App 的 sameTitleLineMatcher 相同：
     * 前面可以有空白、标点或书名，章节名中的空白可多可少
     */
    internal fun removeSameTitle(bookName: String, title: String, content: String): String {
        val words = title.trim().split(spaceRegex).filter { it.isNotEmpty() }
        if (words.isEmpty()) return content
        // 词与词之间可以有任意空白（含换行和全角空格：章节名常被拆成两行，正文每行带全角空格缩进）
        val titlePattern = words.joinToString("[\\s\\p{Zs}]*") { Pattern.quote(it) }
        val namePattern = Pattern.quote(bookName)
        return try {
            val m = Pattern.compile(
                // 比 App 多允许全角空格等（\p{Zs}）：Java 的 \s 不包含全角空格，以全角空格缩进的标题行也要能删除
                "^(\\s|\\p{Zs}|\\p{P}|$namePattern)*$titlePattern[\\t\\x0B\\f\\p{Zs}]*(?:(?:\\r\\n|\\r|\\n)\\s*|$)"
            ).matcher(content)
            if (m.find()) content.substring(m.end()) else content
        } catch (_: Exception) {
            content
        }
    }

    /**
     * 重新分段（App 的 ContentHelp.reSegment）。它会去掉段落里的空白、改写引号，
     * 正文里的图片标签先换成占位符，分段后再换回来（单独成段）
     */
    internal fun reSegment(content: String, title: String): String {
        val images = mutableListOf<String>()
        val masked = imgTagRegex.replace(content) { m ->
            images.add(m.value)
            "\n$IMG_MARK${images.size - 1}$IMG_MARK_END\n"
        }
        if (masked.isBlank()) return content
        val text = try {
            ContentHelp.reSegment(masked, title)
        } catch (_: Exception) {
            return content
        }
        if (images.isEmpty()) return text
        return imgMarkRegex.replace(text) { m ->
            "\n" + (images.getOrNull(m.groupValues[1].toInt()) ?: "") + "\n"
        }.split('\n').filter { it.isNotBlank() }.joinToString("\n")
    }

    private class RegexTimeout : RuntimeException()

    /** 带超时的 CharSequence，防止灾难性回溯卡死线程 */
    private class TimeoutCharSequence(
        private val inner: CharSequence,
        timeoutMs: Long,
        private val deadline: Long = System.currentTimeMillis() + timeoutMs,
    ) : CharSequence {
        override val length: Int get() = inner.length
        override fun get(index: Int): Char {
            if (System.currentTimeMillis() > deadline) throw RegexTimeout()
            return inner[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            TimeoutCharSequence(inner.subSequence(startIndex, endIndex), 0, deadline)
        override fun toString(): String = inner.toString()
    }
}
