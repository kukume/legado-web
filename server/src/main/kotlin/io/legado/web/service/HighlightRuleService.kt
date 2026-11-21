package io.legado.web.service

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.web.db.UserPrefs
import io.legado.web.db.dbQuery
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.UUID

/**
 * 高亮规则（阅读时给正文中匹配的文字加样式）。匹配和显示都在前端（web/src/utils/highlight.ts），
 * 服务器只保存：存在用户偏好 [PREF_KEY] 里，格式与阅读 App（LegadoTeam/legado 的 HighlightRule）相同，
 * 这样备份里的 highlightRule.json 可以直接互相导入。
 *
 * 规则以 uuid 区分：导入时 uuid 相同的覆盖，其余追加到后面（与 App 的 mergeImportedHighlightRules 相同）
 */
class HighlightRuleService {

    companion object {
        const val PREF_KEY = "highlightRules"
        /** 偏好接口对这一项放宽的大小上限（规则的样式是 JSON 字符串，比一般设置大） */
        const val MAX_SIZE = 512 * 1024
    }

    suspend fun list(userId: Long): JsonArray {
        val raw = dbQuery {
            UserPrefs.selectAll().where { (UserPrefs.userId eq userId) and (UserPrefs.key eq PREF_KEY) }
                .firstOrNull()?.get(UserPrefs.value)
        } ?: return JsonArray()
        return runCatching { JsonParser.parseString(raw) }.getOrNull()?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    }

    /** 导入 App 的 highlightRule.json（规则数组，或 {"type":"highlightRule","rules":[...]}），返回导入的条数 */
    suspend fun import(userId: Long, json: String): Int {
        val parsed = JsonParser.parseString(json)
        val incoming = when {
            parsed.isJsonArray -> parsed.asJsonArray
            parsed.isJsonObject && parsed.asJsonObject.get("rules")?.isJsonArray == true -> parsed.asJsonObject.getAsJsonArray("rules")
            else -> throw IllegalArgumentException("格式错误")
        }
        // 同一个文件里 uuid 重复时用后面的
        val rules = incoming.mapNotNull { normalize(it) }.associateBy { uuidOf(it) }.values.toList()
        if (rules.isEmpty()) return 0
        val current = list(userId).mapNotNull { normalize(it) }
        val byUuid = rules.associateBy { uuidOf(it) }
        val currentUuids = current.map { uuidOf(it) }.toSet()
        val merged = current.map { byUuid[uuidOf(it)] ?: it } + rules.filter { uuidOf(it) !in currentUuids }
        val out = JsonArray()
        merged.forEachIndexed { i, r -> out.add(r.deepCopy().apply { addProperty("order", i) }) }
        val text = out.toString()
        if (text.length > MAX_SIZE) throw IllegalArgumentException("高亮规则过多")
        dbQuery {
            UserPrefs.upsert {
                it[UserPrefs.userId] = userId
                it[UserPrefs.key] = PREF_KEY
                it[UserPrefs.value] = text
            }
        }
        return rules.size
    }

    private fun uuidOf(r: JsonObject) = r.get("uuid").asString.lowercase()

    /** 没有内容的丢弃；uuid 无效时重新生成；去掉 App 数据库里的 id */
    private fun normalize(e: JsonElement?): JsonObject? {
        if (e == null || !e.isJsonObject) return null
        val r = e.asJsonObject.deepCopy()
        val pattern = r.get("pattern")?.takeIf { it.isJsonPrimitive }?.asString
        if (pattern.isNullOrEmpty()) return null
        val uuid = r.get("uuid")?.takeIf { it.isJsonPrimitive }?.asString
            ?.let { runCatching { UUID.fromString(it).toString() }.getOrNull() }
        r.addProperty("uuid", uuid ?: UUID.randomUUID().toString())
        r.remove("id")
        return r
    }
}
