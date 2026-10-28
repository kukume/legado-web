package io.legado.web.service

import com.google.gson.JsonParser
import io.legado.app.data.entities.BookSource
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.model.jsSource.JsSourceConfig
import io.legado.web.engine.runEngine
import kotlin.coroutines.coroutineContext
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.ForbiddenException
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.UserPrincipal
import io.legado.web.db.BookSources
import io.legado.web.db.Books
import io.legado.web.security.ScriptUrls
import io.legado.web.db.UserSourcePrefs
import io.legado.web.db.dbQuery
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.concurrent.ConcurrentHashMap

data class SourceSummary(
    val id: Long,
    val bookSourceUrl: String,
    val bookSourceName: String,
    val bookSourceGroup: String?,
    val bookSourceType: Int,
    /** 当前用户视角下是否启用 */
    val enabled: Boolean,
    val enabledExplore: Boolean,
    val hasExplore: Boolean,
    val hasLogin: Boolean,
    val customOrder: Int,
    val respondTime: Long,
    val isPublic: Boolean,
    val owned: Boolean,
    val canEdit: Boolean,
    val ownerId: Long,
    val bookSourceComment: String?,
    val lastUpdateTime: Long,
)

/** 带数据库 id 的书源 */
data class StoredSource(val id: Long, val ownerId: Long, val isPublic: Boolean, val source: BookSource)

/** 导入预览的一条：status 为 new（新增）、update（比已有的新）、exists（已有，不比已有的新） */
data class ImportPreviewItem(
    val name: String,
    val url: String,
    val group: String?,
    val status: String,
    val json: String,
)

data class ImportResult(val added: Int, val updated: Int, val failed: Int, val errors: List<String>)

class SourceService(
    private val settings: SettingService,
    private val userService: UserService,
) {

    /** 解析后的书源缓存（id -> 更新时间 + 对象），避免每次都解析 JSON */
    private val parsedCache = ConcurrentHashMap<Long, Pair<Long, BookSource>>()

    /**
     * 前端传回、要交给引擎的网址（书籍、目录、封面、发现分类、图片）：带脚本的必须是服务器交给过这个用户的，
     * 或者已经在他的书架里（见 [ScriptUrls]）。能添加书源的用户本来就可以写 js，不检查
     */
    suspend fun requireTrustedUrls(user: UserPrincipal, vararg urls: String?) {
        val risky = urls.filterNotNull().filter { ScriptUrls.hasScript(it) && !ScriptUrls.isRemembered(user.id, it) }
        if (risky.isEmpty() || canAddSource(user)) return
        val known = dbQuery {
            risky.all { u ->
                Books.selectAll().where {
                    (Books.userId eq user.id) and ((Books.bookUrl eq u) or (Books.tocUrl eq u) or (Books.coverUrl eq u) or (Books.customCoverUrl eq u))
                }.limit(1).any()
            }
        }
        if (!known) throw ForbiddenException("网址中含有脚本，只能使用书源返回的网址")
    }

    fun canAddSource(user: UserPrincipal): Boolean =
        user.isAdmin || settings.getBool(SettingService.ALLOW_USER_SOURCES) ||
            userService.findActive(user.id)?.canAddSource == true

    private fun ResultRow.canEdit(user: UserPrincipal) =
        this[BookSources.ownerId] == user.id || user.isAdmin

    private fun ResultRow.toSource(): BookSource {
        val id = this[BookSources.id].value
        val ts = this[BookSources.lastUpdateTime]
        parsedCache[id]?.let { if (it.first == ts) return it.second.copy() }
        val src = GSON.fromJsonObject<BookSource>(this[BookSources.json]).getOrNull()
            ?: BookSource(bookSourceUrl = this[BookSources.url], bookSourceName = this[BookSources.name])
        parsedCache[id] = ts to src
        return src.copy()
    }

    private fun visibleOp(user: UserPrincipal) =
        (BookSources.ownerId eq user.id) or (BookSources.isPublic eq true)

    suspend fun list(user: UserPrincipal): List<SourceSummary> = dbQuery {
        val prefs = UserSourcePrefs.selectAll().where { UserSourcePrefs.userId eq user.id }
            .associate { it[UserSourcePrefs.sourceId] to it[UserSourcePrefs.enabled] }
        val rows = BookSources.selectAll()
            .where { visibleOp(user) }
            .orderBy(BookSources.customOrder to SortOrder.ASC, BookSources.id to SortOrder.ASC)
            .toList()
        // 同一 url 用户自己的优先
        val ownUrls = rows.filter { it[BookSources.ownerId] == user.id }.map { it[BookSources.url] }.toSet()
        rows.filter { it[BookSources.ownerId] == user.id || it[BookSources.url] !in ownUrls }
            .map { it.toSummary(user, prefs) }
    }

    private fun ResultRow.toSummary(user: UserPrincipal, prefs: Map<Long, Boolean>): SourceSummary {
        val id = this[BookSources.id].value
        val owned = this[BookSources.ownerId] == user.id
        val enabled = if (owned) this[BookSources.enabled] else (prefs[id] ?: this[BookSources.enabled])
        return SourceSummary(
            id = id,
            bookSourceUrl = this[BookSources.url],
            bookSourceName = this[BookSources.name],
            bookSourceGroup = this[BookSources.groupName],
            bookSourceType = this[BookSources.type],
            enabled = enabled,
            enabledExplore = this[BookSources.enabledExplore],
            hasExplore = this[BookSources.hasExplore],
            hasLogin = this[BookSources.hasLogin],
            customOrder = this[BookSources.customOrder],
            respondTime = this[BookSources.respondTime],
            isPublic = this[BookSources.isPublic],
            owned = owned,
            canEdit = canEdit(user),
            ownerId = this[BookSources.ownerId],
            bookSourceComment = this[BookSources.comment]?.take(500),
            lastUpdateTime = this[BookSources.lastUpdateTime],
        )
    }

    /** 当前用户启用的书源（用于搜索） */
    suspend fun enabledSources(user: UserPrincipal, group: String? = null, ids: Set<Long>? = null): List<StoredSource> {
        val list = list(user).filter { s ->
            s.enabled &&
                (ids == null || s.id in ids) &&
                (group.isNullOrBlank() || s.bookSourceGroup?.split(Regex("[,;，；]"))
                    ?.map { it.trim() }?.contains(group) == true)
        }
        if (list.isEmpty()) return emptyList()
        val idSet = list.map { it.id }
        return dbQuery {
            BookSources.selectAll().where { BookSources.id inList idSet }
                .map { StoredSource(it[BookSources.id].value, it[BookSources.ownerId], it[BookSources.isPublic], it.toSource()) }
        }.sortedBy { idSet.indexOf(it.id) }
    }

    suspend fun get(user: UserPrincipal, id: Long): StoredSource = dbQuery {
        val row = BookSources.selectAll().where { BookSources.id eq id }.firstOrNull()
            ?: throw NotFoundException("书源不存在")
        if (row[BookSources.ownerId] != user.id && !row[BookSources.isPublic] && !user.isAdmin) {
            throw ForbiddenException()
        }
        StoredSource(id, row[BookSources.ownerId], row[BookSources.isPublic], row.toSource())
    }

    /**
     * 根据书源地址查找当前用户可用的书源（优先用户自己的）
     */
    suspend fun findByUrl(user: UserPrincipal, url: String): StoredSource? = dbQuery {
        BookSources.selectAll()
            .where { (BookSources.url eq url) and visibleOp(user) }
            .sortedByDescending { it[BookSources.ownerId] == user.id }
            .firstOrNull()
            ?.let { StoredSource(it[BookSources.id].value, it[BookSources.ownerId], it[BookSources.isPublic], it.toSource()) }
    }

    fun parseSources(text: String): List<BookSource> {
        val trimmed = text.trim().removePrefix("﻿")
        if (trimmed.isEmpty()) throw BadRequestException("内容为空")
        val element = kotlin.runCatching { JsonParser.parseString(trimmed) }.getOrElse {
            throw BadRequestException("JSON 格式错误: ${it.message}")
        }
        val list = when {
            element.isJsonArray -> GSON.fromJsonArray<BookSource>(trimmed).getOrElse {
                // 逐个解析，跳过坏数据
                element.asJsonArray.mapNotNull { e ->
                    kotlin.runCatching { GSON.fromJson(e, BookSource::class.java) }.getOrNull()
                }
            }
            element.isJsonObject -> listOfNotNull(GSON.fromJsonObject<BookSource>(trimmed).getOrNull())
            else -> emptyList()
        }
        return list.filter { it.bookSourceUrl.isNotBlank() && it.bookSourceName.isNotBlank() }
    }

    suspend fun fetchText(url: String): String {
        if (!url.isAbsUrl()) throw BadRequestException("链接无效")
        val res = okHttpClient.newCallStrResponse { url(url) }
        if (!res.isSuccessful()) throw BadRequestException("下载失败: HTTP ${res.code()}")
        return res.body ?: throw BadRequestException("下载内容为空")
    }

    /**
     * 与阅读 App 的导入逻辑一致：JSON 为普通书源；否则按纯 JS 单文件书源解析
     */
    suspend fun parseAny(user: UserPrincipal, text: String): List<BookSource> {
        val trimmed = text.trim().removePrefix("﻿")
        if (trimmed.isJsonObject() || trimmed.isJsonArray()) return parseSources(trimmed)
        return try {
            listOf(runEngine(user.id, 30_000) { JsSourceConfig.extract(trimmed, coroutineContext) })
        } catch (e: Exception) {
            throw BadRequestException("格式错误：既不是书源 JSON，也不是有效的 JS 书源（${e.message}）")
        }
    }

    suspend fun importFromUrl(user: UserPrincipal, url: String, isPublic: Boolean): ImportResult {
        val text = fetchText(url.trim())
        return import(user, parseAny(user, text), isPublic)
    }

    /**
     * 导入前预览（与 App 的导入弹窗一致）：列出每个书源是新增、更新还是已有，由用户勾选后再导入
     */
    suspend fun previewImport(user: UserPrincipal, sources: List<BookSource>): List<ImportPreviewItem> {
        if (!canAddSource(user)) throw ForbiddenException("管理员未开放用户添加书源")
        val urls = sources.map { it.bookSourceUrl.take(1024) }.distinct()
        val existing = dbQuery {
            // 分批查询，避免书源很多时超出 SQL 参数个数上限
            urls.chunked(500).flatMap { part ->
                BookSources.selectAll().where { (BookSources.ownerId eq user.id) and (BookSources.url inList part) }
                    .map { it[BookSources.url] to it.toSource().lastUpdateTime }
            }.toMap()
        }
        return sources.map { src ->
            val old = existing[src.bookSourceUrl.take(1024)]
            val status = when {
                old == null -> "new"
                src.lastUpdateTime > old -> "update"
                else -> "exists"
            }
            ImportPreviewItem(src.bookSourceName, src.bookSourceUrl, src.bookSourceGroup, status, sourceJson(src))
        }
    }

    suspend fun import(user: UserPrincipal, sources: List<BookSource>, isPublic: Boolean): ImportResult {
        if (!canAddSource(user)) throw ForbiddenException("管理员未开放用户添加书源")
        val public = isPublic && user.isAdmin
        var added = 0
        var updated = 0
        var failed = 0
        val errors = mutableListOf<String>()
        dbQuery {
            val existing = BookSources.selectAll().where { BookSources.ownerId eq user.id }
                .associate { it[BookSources.url] to it[BookSources.id].value }
            sources.forEach { src ->
                try {
                    val id = existing[src.bookSourceUrl]
                    if (id != null) {
                        writeSource(id, src, public)
                        updated++
                    } else {
                        insertSource(user.id, src, public)
                        added++
                    }
                } catch (e: Exception) {
                    failed++
                    if (errors.size < 20) errors.add("${src.bookSourceName}: ${e.message}")
                }
            }
        }
        return ImportResult(added, updated, failed, errors)
    }

    private fun sourceJson(src: BookSource): String = GSON.toJson(src)

    private fun insertSource(ownerId: Long, src: BookSource, public: Boolean): Long {
        val now = System.currentTimeMillis()
        if (src.lastUpdateTime == 0L) src.lastUpdateTime = now
        return BookSources.insertAndGetId {
            it[BookSources.ownerId] = ownerId
            it[url] = src.bookSourceUrl.take(1024)
            it[createdAt] = now
            fill(it, src, public)
        }.value
    }

    private fun writeSource(id: Long, src: BookSource, public: Boolean?) {
        BookSources.update({ BookSources.id eq id }) {
            it[url] = src.bookSourceUrl.take(1024)
            fill(it, src, public)
        }
        parsedCache.remove(id)
    }

    private fun fill(
        it: org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>,
        src: BookSource,
        public: Boolean?
    ) {
        it[BookSources.name] = src.bookSourceName.take(255)
        it[BookSources.groupName] = src.bookSourceGroup?.take(255)
        it[BookSources.type] = src.bookSourceType
        it[BookSources.enabled] = src.enabled
        it[BookSources.enabledExplore] = src.enabledExplore
        it[BookSources.hasExplore] = !src.exploreUrl.isNullOrBlank()
        it[BookSources.hasLogin] = !src.loginUrl.isNullOrBlank() || !src.loginUi.isNullOrBlank()
        it[BookSources.customOrder] = src.customOrder
        it[BookSources.weight] = src.weight
        it[BookSources.respondTime] = src.respondTime
        it[BookSources.comment] = src.bookSourceComment
        it[BookSources.json] = sourceJson(src)
        it[BookSources.lastUpdateTime] = System.currentTimeMillis()
        public?.let { p -> it[BookSources.isPublic] = p }
    }

    /**
     * 保存（新建或修改）书源
     */
    suspend fun save(user: UserPrincipal, id: Long?, input: BookSource, isPublic: Boolean?): Long {
        // 纯 JS 书源：以脚本为准重新解析配置，保留启用状态、排序等用户设置
        val src = if (input.isJsSource()) {
            val script = input.mainJs!!
            try {
                runEngine(user.id, 30_000) { JsSourceConfig.extract(script, coroutineContext) }
                    .also { io.legado.app.model.jsSource.JsSourceUpsert.preserveUserState(it, input) }
            } catch (e: Exception) {
                throw BadRequestException("JS 书源解析失败: ${e.message}")
            }
        } else input
        if (src.bookSourceUrl.isBlank()) throw BadRequestException("书源地址不能为空")
        if (src.bookSourceName.isBlank()) throw BadRequestException("书源名称不能为空")
        return dbQuery {
            if (id == null) {
                if (!canAddSource(user)) throw ForbiddenException("管理员未开放用户添加书源")
                val dup = BookSources.selectAll()
                    .where { (BookSources.ownerId eq user.id) and (BookSources.url eq src.bookSourceUrl) }
                    .firstOrNull()
                if (dup != null) {
                    writeSource(dup[BookSources.id].value, src, isPublic?.takeIf { user.isAdmin })
                    dup[BookSources.id].value
                } else {
                    insertSource(user.id, src, isPublic == true && user.isAdmin)
                }
            } else {
                val row = BookSources.selectAll().where { BookSources.id eq id }.firstOrNull()
                    ?: throw NotFoundException("书源不存在")
                if (!row.canEdit(user)) throw ForbiddenException("只能修改自己的书源")
                writeSource(id, src, isPublic?.takeIf { user.isAdmin })
                id
            }
        }
    }

    suspend fun delete(user: UserPrincipal, ids: List<Long>) {
        dbQuery {
            ids.forEach { id ->
                val row = BookSources.selectAll().where { BookSources.id eq id }.firstOrNull() ?: return@forEach
                if (!row.canEdit(user)) throw ForbiddenException("只能删除自己的书源")
                BookSources.deleteWhere { BookSources.id eq id }
                UserSourcePrefs.deleteWhere { UserSourcePrefs.sourceId eq id }
                parsedCache.remove(id)
            }
        }
    }

    /**
     * 批量操作
     * action: enable / disable / enableExplore / disableExplore / addGroup / removeGroup / setPublic / setPrivate
     */
    suspend fun batch(user: UserPrincipal, ids: List<Long>, action: String, value: String?) {
        if (action == "delete") return delete(user, ids)
        dbQuery {
            ids.forEach { id ->
                val row = BookSources.selectAll().where { BookSources.id eq id }.firstOrNull() ?: return@forEach
                val editable = row.canEdit(user)
                // 公共书源的启用状态对普通用户是个人设置
                if (!editable || (row[BookSources.ownerId] != user.id && action in setOf("enable", "disable"))) {
                    if (action == "enable" || action == "disable") {
                        UserSourcePrefs.upsert {
                            it[userId] = user.id
                            it[sourceId] = id
                            it[enabled] = action == "enable"
                        }
                        return@forEach
                    }
                    if (!editable) throw ForbiddenException("只能修改自己的书源")
                }
                val src = row.toSource()
                var public: Boolean? = null
                when (action) {
                    "enable" -> src.enabled = true
                    "disable" -> src.enabled = false
                    "enableExplore" -> src.enabledExplore = true
                    "disableExplore" -> src.enabledExplore = false
                    "addGroup" -> value?.let { src.addGroup(it) }
                    "removeGroup" -> value?.let { src.removeGroup(it) }
                    "setPublic" -> if (user.isAdmin) public = true else throw ForbiddenException()
                    "setPrivate" -> if (user.isAdmin) public = false else throw ForbiddenException()
                    "top" -> src.customOrder = -1
                    else -> throw BadRequestException("未知操作 $action")
                }
                writeSource(id, src, public)
            }
        }
    }

    suspend fun groups(user: UserPrincipal): List<String> =
        list(user).flatMap { it.bookSourceGroup?.split(Regex("[,;，；]")) ?: emptyList() }
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

    suspend fun export(user: UserPrincipal, ids: List<Long>?): List<BookSource> {
        val visible = list(user).map { it.id }.toSet()
        val target = ids?.filter { it in visible } ?: visible.toList()
        if (target.isEmpty()) return emptyList()
        return dbQuery {
            BookSources.selectAll().where { BookSources.id inList target }
                .orderBy(BookSources.customOrder to SortOrder.ASC)
                .map { it.toSource() }
        }
    }

    /**
     * 书源校验的结果写回书源（与 App 相同）：去掉旧的“…失效”“校验超时”分组，失败时加上新的分组，记录响应时间。
     * 只修改自己能编辑的书源，返回是否能修改（公共书源普通用户不能改）
     */
    /** 校验后记录响应时间（用于排序，不改分组）；返回当前用户能否修改 / 删除这个书源 */
    suspend fun recordCheckTime(user: UserPrincipal, id: Long, time: Long): Boolean = dbQuery {
        val row = BookSources.selectAll().where { BookSources.id eq id }.firstOrNull() ?: return@dbQuery false
        BookSources.update({ BookSources.id eq id }) { it[respondTime] = time }
        row.canEdit(user)
    }

    /** 更新书源响应时间（用于排序） */
    suspend fun updateRespondTime(id: Long, time: Long) {
        dbQuery {
            BookSources.update({ BookSources.id eq id }) { it[respondTime] = time }
        }
    }
}
