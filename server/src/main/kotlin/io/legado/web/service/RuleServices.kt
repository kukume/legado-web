package io.legado.web.service

import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.ForbiddenException
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.UserPrincipal
import io.legado.web.db.DictRules
import io.legado.web.db.TxtTocRules
import io.legado.web.db.dbQuery
import io.legado.web.engine.runEngine
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.regex.Pattern

/** 解析 App 格式的规则 JSON：数组或单个对象 */
private inline fun <reified T> parseRules(json: String): List<T> {
    val text = json.trim()
    return GSON.fromJsonArray<T>(text).getOrElse {
        runCatching { GSON.fromJson(text, T::class.java) }.getOrNull()?.let { listOf(it) }
            ?: throw BadRequestException("格式错误，请粘贴规则 JSON")
    }
}

private fun readDefault(name: String): String =
    RuleDefaults::class.java.getResourceAsStream("/defaultData/$name")?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: error("缺少内置规则 $name")

/** App 自带的规则（app/src/main/assets/defaultData），原样随服务端打包 */
object RuleDefaults {
    val dictRules: List<DictRule> by lazy { GSON.fromJsonArray<DictRule>(readDefault("dictRules.json")).getOrThrow() }
    val txtTocRules: List<TxtTocRule> by lazy { GSON.fromJsonArray<TxtTocRule>(readDefault("txtTocRule.json")).getOrThrow() }
}

/**
 * 字典规则：正文选中文字后查词。每个用户一份，第一次使用时写入 App 自带的规则（全部删除后会重新写入）。
 *
 * 规则里的地址和 js 在服务器上执行（与书源相同），所以新建、修改、导入需要“添加书源”的权限；
 * 普通用户可以使用、启用/停用、排序自带规则。
 */
class DictRuleService(private val sourceService: SourceService) {

    private fun ResultRow.toRule() = DictRule(
        name = this[DictRules.name],
        urlRule = this[DictRules.urlRule],
        showRule = this[DictRules.showRule],
        enabled = this[DictRules.enabled],
        sortNumber = this[DictRules.sortNumber],
    )

    private fun JdbcTransaction.seedIfEmpty(userId: Long) {
        if (DictRules.selectAll().where { DictRules.userId eq userId }.limit(1).any()) return
        RuleDefaults.dictRules.forEach { r -> insertRow(userId, r) }
    }

    private fun insertRow(userId: Long, r: DictRule) {
        DictRules.insert {
            it[DictRules.userId] = userId
            it[name] = r.name.take(255)
            it[urlRule] = r.urlRule
            it[showRule] = r.showRule
            it[enabled] = r.enabled
            it[sortNumber] = r.sortNumber
        }
    }

    private fun upsert(userId: Long, r: DictRule) {
        val n = DictRules.update({ (DictRules.userId eq userId) and (DictRules.name eq r.name) }) {
            it[urlRule] = r.urlRule
            it[showRule] = r.showRule
            it[enabled] = r.enabled
            it[sortNumber] = r.sortNumber
        }
        if (n == 0) insertRow(userId, r)
    }

    suspend fun list(userId: Long): List<DictRule> = dbQuery {
        seedIfEmpty(userId)
        DictRules.selectAll().where { DictRules.userId eq userId }
            .orderBy(DictRules.sortNumber to SortOrder.ASC, DictRules.id to SortOrder.ASC)
            .map { it.toRule() }
    }

    fun canEdit(user: UserPrincipal) = sourceService.canAddSource(user)

    private fun requireEdit(user: UserPrincipal) {
        if (!canEdit(user)) throw ForbiddenException("字典规则会在服务器上请求网址、执行 JS，需要“添加书源”的权限")
    }

    /**
     * 保存规则；[oldName] 为修改前的名称（改名时使用），新建时为空。
     * 没有编辑权限时只能修改启用状态和排序。
     */
    suspend fun save(user: UserPrincipal, rule: DictRule, oldName: String?): DictRule {
        val name = rule.name.trim()
        if (name.isEmpty()) throw BadRequestException("名称不能为空")
        if (rule.urlRule.isBlank()) throw BadRequestException("网址规则不能为空")
        val r = rule.copy(name = name)
        val editable = canEdit(user)
        dbQuery {
            seedIfEmpty(user.id)
            val key = oldName?.takeIf { it.isNotEmpty() } ?: name
            val old = DictRules.selectAll().where { (DictRules.userId eq user.id) and (DictRules.name eq key) }
                .firstOrNull()?.toRule()
            val onlyToggle = old != null && old.name == r.name && old.urlRule == r.urlRule && old.showRule == r.showRule
            if (!onlyToggle && !editable) {
                throw ForbiddenException("字典规则会在服务器上请求网址、执行 JS，需要“添加书源”的权限")
            }
            if (old != null && key != name) {
                if (DictRules.selectAll().where { (DictRules.userId eq user.id) and (DictRules.name eq name) }.any()) {
                    throw BadRequestException("已经有名为「$name」的字典规则")
                }
                DictRules.deleteWhere { (DictRules.userId eq user.id) and (DictRules.name eq key) }
            }
            upsert(user.id, r)
        }
        return r
    }

    suspend fun delete(userId: Long, names: List<String>) = dbQuery {
        if (names.isNotEmpty()) DictRules.deleteWhere { (DictRules.userId eq userId) and (DictRules.name inList names) }
    }

    /** 导入 App 格式的字典规则 JSON（按名称覆盖） */
    suspend fun import(user: UserPrincipal, json: String): Int {
        requireEdit(user)
        val list = parseRules<DictRule>(json).filter { it.name.isNotBlank() && it.urlRule.isNotBlank() }
        if (list.isEmpty()) throw BadRequestException("没有可导入的字典规则")
        dbQuery {
            seedIfEmpty(user.id)
            list.forEach { upsert(user.id, it.copy(name = it.name.trim())) }
        }
        return list.size
    }

    /** 导入默认规则：与 App 相同，按名称覆盖自带规则，不影响自定义规则 */
    suspend fun importDefaults(userId: Long) = dbQuery {
        RuleDefaults.dictRules.forEach { upsert(userId, it) }
    }

    /** 查词：以当前用户身份执行规则（与 App 的 DictRule.search 相同），返回 (HTML, 网页地址) */
    suspend fun search(user: UserPrincipal, name: String, word: String): Pair<String, String> {
        if (word.isBlank()) throw BadRequestException("没有要查询的文字")
        val rule = list(user.id).firstOrNull { it.name == name } ?: throw NotFoundException("字典规则「$name」不存在")
        return runEngine(user.id, 60_000) { rule.searchWithUrl(word.trim().take(200)) }
    }
}

/**
 * TXT 目录规则：上传 TXT 时自动选择（或手动指定）分章规则。每个用户一份，
 * 与 App 相同：没有任何规则时写入自带规则。
 *
 * “js 处理”在服务器上执行，所以带 js 处理的规则需要“添加书源”的权限才能保存或导入。
 */
class TxtTocRuleService(private val sourceService: SourceService) {

    private fun ResultRow.toRule() = TxtTocRule(
        id = this[TxtTocRules.ruleId],
        name = this[TxtTocRules.name],
        rule = this[TxtTocRules.rule],
        replacement = this[TxtTocRules.replacement],
        example = this[TxtTocRules.example],
        serialNumber = this[TxtTocRules.serialNumber],
        enable = this[TxtTocRules.enable],
    )

    private fun JdbcTransaction.seedIfEmpty(userId: Long) {
        if (TxtTocRules.selectAll().where { TxtTocRules.userId eq userId }.limit(1).any()) return
        RuleDefaults.txtTocRules.forEach { upsert(userId, it) }
    }

    private fun upsert(userId: Long, r: TxtTocRule) {
        val n = TxtTocRules.update({ (TxtTocRules.userId eq userId) and (TxtTocRules.ruleId eq r.id) }) {
            it[name] = r.name.take(255)
            it[rule] = r.rule
            it[replacement] = r.replacement
            it[example] = r.example
            it[serialNumber] = r.serialNumber
            it[enable] = r.enable
        }
        if (n == 0) TxtTocRules.insert {
            it[TxtTocRules.userId] = userId
            it[ruleId] = r.id
            it[name] = r.name.take(255)
            it[rule] = r.rule
            it[replacement] = r.replacement
            it[example] = r.example
            it[serialNumber] = r.serialNumber
            it[enable] = r.enable
        }
    }

    suspend fun list(userId: Long): List<TxtTocRule> = dbQuery {
        seedIfEmpty(userId)
        TxtTocRules.selectAll().where { TxtTocRules.userId eq userId }
            .orderBy(TxtTocRules.serialNumber to SortOrder.ASC, TxtTocRules.id to SortOrder.ASC)
            .map { it.toRule() }
    }

    /** 自动分章时尝试的规则（启用的，按序号） */
    suspend fun enabled(userId: Long): List<TxtTocRule> = list(userId).filter { it.enable }

    suspend fun get(userId: Long, ruleId: Long): TxtTocRule =
        list(userId).firstOrNull { it.id == ruleId } ?: throw NotFoundException("目录规则不存在")

    fun canEdit(user: UserPrincipal) = sourceService.canAddSource(user)

    private fun validate(editable: Boolean, r: TxtTocRule, old: TxtTocRule?) {
        if (r.rule.isBlank()) throw BadRequestException("目录正则不能为空")
        try {
            Pattern.compile(r.rule, Pattern.MULTILINE)
        } catch (e: Exception) {
            throw BadRequestException("正则错误：${e.message}")
        }
        if (r.replacement.isNotBlank() && r.replacement != old?.replacement && !editable) {
            throw ForbiddenException("“js 处理”会在服务器上执行，需要“添加书源”的权限")
        }
    }

    suspend fun save(user: UserPrincipal, rule: TxtTocRule): TxtTocRule {
        val r = if (rule.id == 0L) rule.copy(id = System.currentTimeMillis()) else rule
        val editable = canEdit(user)
        dbQuery {
            seedIfEmpty(user.id)
            val old = TxtTocRules.selectAll().where { (TxtTocRules.userId eq user.id) and (TxtTocRules.ruleId eq r.id) }
                .firstOrNull()?.toRule()
            validate(editable, r, old)
            upsert(user.id, r)
        }
        return r
    }

    suspend fun delete(userId: Long, ids: List<Long>) = dbQuery {
        if (ids.isNotEmpty()) TxtTocRules.deleteWhere { (TxtTocRules.userId eq userId) and (TxtTocRules.ruleId inList ids) }
    }

    /** 导入 App 格式的目录规则 JSON（按 id 覆盖） */
    suspend fun import(user: UserPrincipal, json: String): Int {
        val list = parseRules<TxtTocRule>(json).filter { it.rule.isNotBlank() }
        if (list.isEmpty()) throw BadRequestException("没有可导入的目录规则")
        val editable = canEdit(user)
        dbQuery {
            seedIfEmpty(user.id)
            list.forEach {
                val old = TxtTocRules.selectAll().where { (TxtTocRules.userId eq user.id) and (TxtTocRules.ruleId eq it.id) }
                    .firstOrNull()?.toRule()
                validate(editable, it, old)
            }
            list.forEach { upsert(user.id, it) }
        }
        return list.size
    }

    /** 导入默认规则：与 App 相同，先删除自带规则（id < 0）再写入，不影响自定义规则 */
    suspend fun importDefaults(userId: Long) = dbQuery {
        TxtTocRules.deleteWhere { (TxtTocRules.userId eq userId) and (TxtTocRules.ruleId less 0L) }
        RuleDefaults.txtTocRules.forEach { upsert(userId, it) }
    }
}
