package io.legado.web.service

import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.engine.EngineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.ForbiddenException
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.UserPrincipal
import io.legado.web.db.TtsEngines
import io.legado.web.db.Users
import io.legado.web.db.dbQuery
import io.legado.web.engine.runEngine
import okhttp3.Response
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * 在线朗读引擎（App 的 httpTTS）：请求地址规则在服务器上执行，返回音频。
 *
 * 规则中可以执行 JavaScript、访问任意网址，与书源相同，所以只有能添加书源的用户可以导入；
 * 管理员导入的引擎所有用户都可以使用。
 */
class TtsService(private val sourceService: SourceService) {

    data class TtsEngineDto(
        val id: Long,
        val name: String,
        val url: String,
        val contentType: String?,
        val hasLogin: Boolean,
        /** 自己导入的（可以删除）；否则是管理员提供的公共引擎 */
        val owned: Boolean,
    )

    data class ImportResult(val added: Int, val updated: Int)

    data class SpeechAudio(val bytes: ByteArray, val contentType: String)

    companion object {
        /** 单次朗读的最大字数（前端按段落、句子切分后请求） */
        const val MAX_TEXT = 1000
        private const val MAX_AUDIO = 10L * 1024 * 1024
    }

    private val parsed = ConcurrentHashMap<Long, Pair<String, HttpTTS>>()

    private fun parse(id: Long, json: String): HttpTTS =
        parsed[id]?.takeIf { it.first == json }?.second
            ?: GSON.fromJsonObject<HttpTTS>(json).getOrThrow().copy(id = id).also { parsed[id] = json to it }

    private suspend fun adminIds(): List<Long> = dbQuery {
        Users.select(Users.id).where { Users.role eq "admin" }.map { it[Users.id].value }
    }

    suspend fun list(user: UserPrincipal): List<TtsEngineDto> {
        val admins = adminIds()
        return dbQuery {
            TtsEngines.selectAll()
                .where { (TtsEngines.userId eq user.id) or (TtsEngines.userId inList admins) }
                .orderBy(TtsEngines.order to SortOrder.ASC, TtsEngines.id to SortOrder.ASC)
                .map { r ->
                    val tts = parse(r[TtsEngines.id].value, r[TtsEngines.json])
                    TtsEngineDto(
                        id = r[TtsEngines.id].value,
                        name = r[TtsEngines.name],
                        url = tts.url,
                        contentType = tts.contentType,
                        hasLogin = !tts.loginUrl.isNullOrBlank(),
                        owned = r[TtsEngines.userId] == user.id,
                    )
                }
        }
    }

    /** 可以使用的引擎（自己的或管理员的） */
    private suspend fun usable(user: UserPrincipal, id: Long): HttpTTS {
        val admins = adminIds()
        val row = dbQuery {
            TtsEngines.selectAll().where { TtsEngines.id eq id }.firstOrNull()
        } ?: throw NotFoundException("朗读引擎不存在")
        if (row[TtsEngines.userId] != user.id && row[TtsEngines.userId] !in admins) throw NotFoundException("朗读引擎不存在")
        return parse(id, row[TtsEngines.json])
    }

    /** 导入 App 导出的 httpTTS（数组或单个对象）；同名的覆盖 */
    suspend fun import(user: UserPrincipal, text: String): ImportResult {
        if (!sourceService.canAddSource(user)) throw ForbiddenException("没有导入朗读引擎的权限（与添加书源的权限相同）")
        val t = text.trim()
        val list = when {
            t.startsWith("[") -> GSON.fromJsonArray<HttpTTS>(t).getOrNull()
            t.startsWith("{") -> GSON.fromJsonObject<HttpTTS>(t).getOrNull()?.let { listOf(it) }
            else -> null
        } ?: throw BadRequestException("格式不正确，请粘贴阅读 App 导出的朗读引擎 JSON")
        val valid = list.filter { it.name.isNotBlank() && it.url.isNotBlank() }
        if (valid.isEmpty()) throw BadRequestException("没有有效的朗读引擎（需要 name 和 url）")
        var added = 0
        var updated = 0
        dbQuery {
            val existing = TtsEngines.selectAll().where { TtsEngines.userId eq user.id }
                .associate { it[TtsEngines.name] to it[TtsEngines.id].value }
            var order = existing.size
            valid.forEach { tts ->
                val name = tts.name.take(255)
                val oldId = existing[name]
                if (oldId != null) {
                    TtsEngines.update({ TtsEngines.id eq oldId }) { it[json] = GSON.toJson(tts.copy(id = oldId)) }
                    updated++
                } else {
                    val id = TtsEngines.insertAndGetId {
                        it[userId] = user.id
                        it[TtsEngines.name] = name
                        it[json] = "{}"
                        it[TtsEngines.order] = order++
                        it[createdAt] = System.currentTimeMillis()
                    }.value
                    TtsEngines.update({ TtsEngines.id eq id }) { it[json] = GSON.toJson(tts.copy(id = id)) }
                    added++
                }
            }
        }
        return ImportResult(added, updated)
    }

    suspend fun delete(user: UserPrincipal, id: Long) {
        val n = dbQuery { TtsEngines.deleteWhere { (TtsEngines.id eq id) and (TtsEngines.userId eq user.id) } }
        if (n == 0) throw NotFoundException("朗读引擎不存在")
        parsed.remove(id)
    }

    /** 导出自己导入的引擎（App 可以导入） */
    suspend fun export(user: UserPrincipal): List<HttpTTS> = dbQuery {
        TtsEngines.selectAll().where { TtsEngines.userId eq user.id }
            .orderBy(TtsEngines.order to SortOrder.ASC)
            .map { parse(it[TtsEngines.id].value, it[TtsEngines.json]) }
    }

    /**
     * 合成一段语音。[speed] 与 App 相同：10 为正常语速（界面上的 1.0 倍）。
     * 与 App 相同的检查：返回 JSON / 文本，或与引擎的 contentType 正则不匹配时视为出错，把返回内容作为错误信息。
     */
    suspend fun speak(user: UserPrincipal, id: Long, text: String, speed: Int): SpeechAudio {
        val speakText = text.trim()
        if (speakText.isEmpty()) throw BadRequestException("没有要朗读的文字")
        if (speakText.length > MAX_TEXT) throw BadRequestException("单次朗读的文字过长")
        val tts = usable(user, id)
        // 朗读时不应弹出书源网页
        val ctx = EngineContext(user.id).apply { suppressUi = true }
        return runEngine(user.id, 90_000, ctx = ctx) {
            val analyzeUrl = AnalyzeUrl(
                tts.url,
                speakText = speakText,
                speakSpeed = speed.coerceIn(1, 50),
                source = tts,
                readTimeout = 60_000L,
                coroutineContext = coroutineContext,
            )
            var response = analyzeUrl.getResponseAwait()
            tts.loginCheckJs?.takeIf { it.isNotBlank() }?.let { js ->
                response = analyzeUrl.evalJS(js, response) as? Response ?: response
            }
            response.use { res ->
                val type = res.header("Content-Type")?.substringBefore(";")?.trim().orEmpty()
                fun fail(prefix: String): Nothing =
                    throw NoStackTraceException(prefix + res.body.string().take(300))
                if (!res.isSuccessful) fail("朗读引擎返回错误（${res.code}）：")
                if (type == "application/json" || type.startsWith("text/")) fail("朗读引擎返回错误：")
                val ct = tts.contentType
                if (!ct.isNullOrBlank() && type.isNotEmpty() && !type.matches(ct.toRegex())) fail("朗读引擎返回错误：")
                val len = res.body.contentLength()
                if (len > MAX_AUDIO) throw NoStackTraceException("朗读引擎返回的音频过大")
                val bytes = res.body.byteStream().use { it.readNBytes((MAX_AUDIO + 1).toInt()) }
                if (bytes.size > MAX_AUDIO) throw NoStackTraceException("朗读引擎返回的音频过大")
                if (bytes.isEmpty()) throw NoStackTraceException("朗读引擎没有返回音频")
                SpeechAudio(bytes, type.takeIf { it.startsWith("audio/") || it.startsWith("video/") } ?: "audio/mpeg")
            }
        }
    }
}
