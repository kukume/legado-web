package io.legado.web.service

import io.legado.web.db.Settings
import io.legado.web.db.dbBlocking
import io.legado.web.db.dbQuery
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.concurrent.ConcurrentHashMap

/**
 * 系统设置（管理员可修改）
 */
class SettingService {

    companion object {
        /** invite: 需要邀请码；closed: 关闭注册（已取消开放注册，历史存的 open 视为 invite） */
        const val REGISTRATION_MODE = "registrationMode"
        /** 普通用户是否可以添加自己的书源（书源 JS 在服务器上执行，请谨慎开启） */
        const val ALLOW_USER_SOURCES = "allowUserSources"
        /** 普通用户是否可以生成邀请码 */
        const val ALLOW_USER_INVITE = "allowUserInvite"
        /** 普通用户生成邀请码的数量上限 */
        const val USER_INVITE_LIMIT = "userInviteLimit"
        const val SITE_NAME = "siteName"
        /** 是否允许用户上传本地书籍（EPUB / TXT） */
        const val LOCAL_BOOK_ENABLED = "localBookEnabled"
        /** 单个本地书籍文件的大小上限（MB） */
        const val LOCAL_BOOK_MAX_MB = "localBookMaxMb"
        /** 每个用户本地书籍的总空间（MB） */
        const val LOCAL_BOOK_QUOTA_MB = "localBookQuotaMb"
        /** 登录、注册是否需要 Cap 人机验证 */
        const val CAPTCHA_ENABLED = "captchaEnabled"
        /** Cap Standalone 实例地址，例如 https://cap.example.com */
        const val CAP_ENDPOINT = "capEndpoint"
        const val CAP_SITE_KEY = "capSiteKey"
        /** Cap 的 Site Secret（不是控制台的管理密钥），只在服务端使用，接口返回时打码 */
        const val CAP_SECRET = "capSecret"
        /** 书源访问内网始终被阻止，这里是例外允许访问的主机：主机名、*.后缀、IP 或网段，逗号或换行分隔 */
        const val PRIVATE_NETWORK_ALLOWLIST = "privateNetworkAllowlist"
        /**
         * 书源要打开网页让用户操作（人机验证、登录）时，是否在服务器的浏览器里打开并把画面推给用户（远程浏览器）。
         * 关闭时下载网页交给前端显示（原来的方式）。需要服务器上有 Chrome / Edge
         */
        const val REMOTE_BROWSER_ENABLED = "remoteBrowserEnabled"
        /** 浏览器的调试地址（Chrome DevTools Protocol，如 http://browser:9222）：webView 书源和远程浏览器使用，留空时不使用浏览器 */
        const val BROWSER_CDP = "browserCdp"
        /** 管理员打开网页时检查镜像仓库里是否有新版本（只提示管理员） */
        const val UPDATE_CHECK_ENABLED = "updateCheckEnabled"
        /** 读取设置时密钥的占位符；保存时收到它表示“不修改” */
        const val SECRET_MASK = "********"
        private val secretKeys = setOf(CAP_SECRET)

        val defaults = mapOf(
            REGISTRATION_MODE to "invite",
            ALLOW_USER_SOURCES to "false",
            ALLOW_USER_INVITE to "false",
            USER_INVITE_LIMIT to "5",
            SITE_NAME to "阅读 Web",
            LOCAL_BOOK_ENABLED to "true",
            LOCAL_BOOK_MAX_MB to "50",
            LOCAL_BOOK_QUOTA_MB to "100",
            CAPTCHA_ENABLED to "false",
            CAP_ENDPOINT to "",
            CAP_SITE_KEY to "",
            CAP_SECRET to "",
            PRIVATE_NETWORK_ALLOWLIST to "",
            REMOTE_BROWSER_ENABLED to "false",
            BROWSER_CDP to "",
            UPDATE_CHECK_ENABLED to "true",
        )
    }

    private val cache = ConcurrentHashMap<String, String>()

    fun get(key: String): String {
        val v = cache[key] ?: run {
            val x = dbBlocking {
                Settings.selectAll().where { Settings.key eq key }.firstOrNull()?.get(Settings.value)
            } ?: defaults[key] ?: ""
            cache[key] = x
            x
        }
        // 已取消“开放注册”：历史存的 open 一律当作邀请码注册
        if (key == REGISTRATION_MODE && v == "open") return "invite"
        return v
    }

    fun getBool(key: String) = get(key) == "true"

    fun getInt(key: String) = get(key).toIntOrNull() ?: 0

    /** 给管理员页面用：密钥只返回是否已填写 */
    fun all(): Map<String, String> = defaults.keys.associateWith { k ->
        get(k).let { v -> if (k in secretKeys && v.isNotEmpty()) SECRET_MASK else v }
    }

    suspend fun update(values: Map<String, String>) {
        val allowed = values.filter { (k, v) -> k in defaults && !(k in secretKeys && v == SECRET_MASK) }
        allowed[CAP_ENDPOINT]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (!it.startsWith("http://") && !it.startsWith("https://")) {
                throw io.legado.web.auth.BadRequestException("Cap 实例地址需要以 http:// 或 https:// 开头")
            }
        }
        allowed[BROWSER_CDP]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (!Regex("^(https?|wss?)://", RegexOption.IGNORE_CASE).containsMatchIn(it)) {
                throw io.legado.web.auth.BadRequestException("浏览器调试地址需要以 http:// 或 ws:// 开头")
            }
        }
        if (allowed[REGISTRATION_MODE]?.let { it !in setOf("invite", "closed") } == true) {
            throw io.legado.web.auth.BadRequestException("registrationMode 只能是 invite/closed")
        }
        dbQuery {
            allowed.forEach { (k, v) ->
                Settings.upsert {
                    it[Settings.key] = k
                    it[Settings.value] = v
                }
            }
        }
        allowed.forEach { (k, v) -> cache[k] = v }
        listeners.forEach { runCatching { it(allowed.keys) } }
    }

    /** 设置保存后的回调（参数为修改的设置项），如浏览器地址改了立即重新连接 */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Set<String>) -> Unit>()

    fun onChange(listener: (Set<String>) -> Unit) {
        listeners += listener
    }
}
