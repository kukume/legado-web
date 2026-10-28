package io.legado.web.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

object Users : LongIdTable("users") {
    val username = varchar("username", 64).uniqueIndex()
    val passwordHash = varchar("password_hash", 128)
    val role = varchar("role", 16).default("user")
    val enabled = bool("enabled").default(true)
    val invitedBy = long("invited_by").nullable()
    val createdAt = long("created_at")
    val lastLoginAt = long("last_login_at").nullable()
    /** 修改密码/禁用后递增，使旧 token 失效 */
    val tokenVersion = integer("token_version").default(0)
    /** 单独授权：允许该用户添加书源（管理员始终允许） */
    val canAddSource = bool("can_add_source").default(false)
}

object InviteCodes : LongIdTable("invite_codes") {
    val code = varchar("code", 64).uniqueIndex()
    val createdBy = long("created_by")
    val maxUses = integer("max_uses").default(1)
    val usedCount = integer("used_count").default(0)
    val expiresAt = long("expires_at").nullable()
    val note = varchar("note", 255).nullable()
    val disabled = bool("disabled").default(false)
    val createdAt = long("created_at")
}

object InviteUsages : LongIdTable("invite_usages") {
    val inviteId = long("invite_id").index()
    val userId = long("user_id")
    val usedAt = long("used_at")
}

object Settings : Table("settings") {
    val key = varchar("key", 64)
    val value = text("value")
    override val primaryKey = PrimaryKey(key)
}

/**
 * 书源。完整的书源 JSON 保存在 json 字段，其余字段用于列表查询
 */
object BookSources : LongIdTable("book_sources") {
    val ownerId = long("owner_id").index()
    val url = varchar("url", 1024)
    val name = varchar("name", 255)
    val groupName = varchar("group_name", 255).nullable()
    val type = integer("type").default(0)
    val enabled = bool("enabled").default(true)
    val enabledExplore = bool("enabled_explore").default(true)
    val hasExplore = bool("has_explore").default(false)
    val hasLogin = bool("has_login").default(false)
    val customOrder = integer("custom_order").default(0)
    val weight = integer("weight").default(0)
    val respondTime = long("respond_time").default(180000)
    val isPublic = bool("is_public").default(false)
    val comment = text("comment").nullable()
    val json = text("json")
    val lastUpdateTime = long("last_update_time").default(0)
    val createdAt = long("created_at")

    init {
        uniqueIndex("ux_source_owner_url", ownerId, url)
    }
}

/** 用户对公共书源的个人设置（启用/禁用） */
object UserSourcePrefs : Table("user_source_prefs") {
    val userId = long("user_id")
    val sourceId = long("source_id").index()
    val enabled = bool("enabled").default(true)
    override val primaryKey = PrimaryKey(userId, sourceId)
}

object SourceSubscriptions : LongIdTable("source_subscriptions") {
    val ownerId = long("owner_id").index()
    val name = varchar("name", 255)
    val url = varchar("url", 2048)
    val isPublic = bool("is_public").default(false)
    val autoUpdate = bool("auto_update").default(true)
    val lastUpdateAt = long("last_update_at").default(0)
    val lastResult = varchar("last_result", 512).nullable()
    val createdAt = long("created_at")
}

/** 引擎缓存（书源变量、登录信息、js 缓存等），按用户隔离 */
object EngineCaches : Table("engine_caches") {
    val userId = long("user_id")
    val key = varchar("cache_key", 512)
    val value = text("value")
    val deadline = long("deadline").default(0)
    override val primaryKey = PrimaryKey(userId, key)
}

object Cookies : Table("cookies") {
    val userId = long("user_id")
    val domain = varchar("domain", 255)
    val cookie = text("cookie")
    override val primaryKey = PrimaryKey(userId, domain)
}

object BookGroups : LongIdTable("book_groups") {
    val userId = long("user_id").index()
    val name = varchar("name", 64)
    val order = integer("sort_order").default(0)
}

object Books : LongIdTable("books") {
    val userId = long("user_id").index()
    val bookUrl = varchar("book_url", 2048)
    val tocUrl = varchar("toc_url", 2048).default("")
    val origin = varchar("origin", 1024)
    val originName = varchar("origin_name", 255).default("")
    val name = varchar("name", 255)
    val author = varchar("author", 255).default("")
    val kind = text("kind").nullable()
    val coverUrl = text("cover_url").nullable()
    val customCoverUrl = text("custom_cover_url").nullable()
    val intro = text("intro").nullable()
    val customIntro = text("custom_intro").nullable()
    val type = integer("type").default(8)
    val groupId = long("group_id").default(0)
    val inShelf = bool("in_shelf").default(true)
    val latestChapterTitle = text("latest_chapter_title").nullable()
    val latestChapterTime = long("latest_chapter_time").default(0)
    val lastCheckTime = long("last_check_time").default(0)
    val lastCheckCount = integer("last_check_count").default(0)
    val totalChapterNum = integer("total_chapter_num").default(0)
    val durChapterTitle = text("dur_chapter_title").nullable()
    val durChapterIndex = integer("dur_chapter_index").default(0)
    val durChapterPos = integer("dur_chapter_pos").default(0)
    val durChapterTime = long("dur_chapter_time").default(0)
    val wordCount = varchar("word_count", 64).nullable()
    val canUpdate = bool("can_update").default(true)
    val order = integer("sort_order").default(0)
    val variable = text("variable").nullable()
    val readConfig = text("read_config").nullable()
    val createdAt = long("created_at")

    init {
        index(false, userId, inShelf)
    }
}

object Chapters : Table("chapters") {
    val bookId = long("book_id")
    val index = integer("chapter_index")
    val url = text("url")
    val title = text("title")
    val isVolume = bool("is_volume").default(false)
    val baseUrl = text("base_url").default("")
    val tag = text("tag").nullable()
    val isVip = bool("is_vip").default(false)
    val isPay = bool("is_pay").default(false)
    val wordCount = varchar("word_count", 64).nullable()
    val variable = text("variable").nullable()
    override val primaryKey = PrimaryKey(bookId, index)
}

object ChapterContents : Table("chapter_contents") {
    val bookId = long("book_id")
    val index = integer("chapter_index")
    val chapterUrl = text("chapter_url")
    val content = text("content")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(bookId, index)
}

object ReplaceRules : LongIdTable("replace_rules") {
    val userId = long("user_id").index()
    val name = varchar("name", 255).default("")
    val groupName = varchar("group_name", 255).nullable()
    val pattern = text("pattern")
    val replacement = text("replacement").default("")
    val scope = text("scope").nullable()
    val excludeScope = text("exclude_scope").nullable()
    val scopeTitle = bool("scope_title").default(false)
    val scopeContent = bool("scope_content").default(true)
    val isRegex = bool("is_regex").default(true)
    val enabled = bool("enabled").default(true)
    val order = integer("sort_order").default(0)
}

object UserPrefs : Table("user_prefs") {
    val userId = long("user_id")
    val key = varchar("pref_key", 64)
    val value = text("value")
    override val primaryKey = PrimaryKey(userId, key)
}

/** 用户上传的阅读字体（文件保存在数据目录 fonts/<用户 id>/<id>） */
object UserFonts : LongIdTable("user_fonts") {
    val userId = long("user_id").index()
    val name = varchar("name", 255)
    /** ttf / otf / woff / woff2 / ttc */
    val format = varchar("format", 8)
    val size = long("size")
    val createdAt = long("created_at")
}

val allTables = arrayOf(
    Users, InviteCodes, InviteUsages, Settings, BookSources, UserSourcePrefs,
    SourceSubscriptions, EngineCaches, Cookies, BookGroups, Books, Chapters,
    ChapterContents, ReplaceRules, UserPrefs, ApiKeys, TtsEngines, Bookmarks, ReadingStats,
    DictRules, TxtTocRules, UserFonts,
)

/**
 * 用户的开放接口密钥（只保存 SHA-256 哈希）
 */
object ApiKeys : LongIdTable("api_keys") {
    val userId = long("user_id").index()
    val name = varchar("name", 64)
    val keyHash = varchar("key_hash", 64).uniqueIndex()
    /** 明文前缀，用于列表中辨认 */
    val prefix = varchar("prefix", 16)
    val createdAt = long("created_at")
    val lastUsedAt = long("last_used_at").nullable()
}

/**
 * 用户导入的在线朗读引擎（与 App 的 httpTTS 格式相同，整条保存为 JSON）
 */
/** 书签 */
object Bookmarks : LongIdTable("bookmarks") {
    val userId = long("user_id").index()
    val bookId = long("book_id").index()
    val chapterIndex = integer("chapter_index")
    val chapterName = varchar("chapter_name", 512).default("")
    /** 章内位置（比例，0..10000） */
    val chapterPos = integer("chapter_pos").default(0)
    /** 选中的正文 */
    val bookText = text("book_text").default("")
    /** 备注 */
    val content = text("content").default("")
    val createdAt = long("created_at")
}

/** 字典规则（与 App 的 dictRules 相同，每个用户一份，名称唯一） */
object DictRules : LongIdTable("dict_rules") {
    val userId = long("user_id").index()
    val name = varchar("name", 255)
    val urlRule = text("url_rule")
    val showRule = text("show_rule").default("")
    val enabled = bool("enabled").default(true)
    val sortNumber = integer("sort_number").default(0)

    init {
        uniqueIndex(userId, name)
    }
}

/** TXT 目录规则（与 App 的 txtTocRules 相同，每个用户一份；ruleId 为 App 中的 id，自带规则为负数） */
object TxtTocRules : LongIdTable("txt_toc_rules") {
    val userId = long("user_id").index()
    val ruleId = long("rule_id")
    val name = varchar("name", 255).default("")
    val rule = text("rule")
    val replacement = text("replacement").default("")
    val example = text("example").nullable()
    val serialNumber = integer("serial_number").default(-1)
    val enable = bool("enable").default(true)

    init {
        uniqueIndex(userId, ruleId)
    }
}

/** 阅读统计：每个用户每天每本书的阅读时长（秒）、字数 */
object ReadingStats : Table("reading_stats") {
    val userId = long("user_id")
    /** 阅读者本地日期 yyyy-MM-dd */
    val day = varchar("day", 10)
    val bookId = long("book_id")
    val seconds = long("seconds").default(0)
    val chars = long("chars").default(0)
    override val primaryKey = PrimaryKey(userId, day, bookId)
}

object TtsEngines : LongIdTable("tts_engines") {
    val userId = long("user_id").index()
    val name = varchar("name", 255)
    val json = text("json")
    val order = integer("sort_order").default(0)
    val createdAt = long("created_at")
}
