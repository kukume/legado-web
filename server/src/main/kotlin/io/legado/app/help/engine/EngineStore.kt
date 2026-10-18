package io.legado.app.help.engine

import java.io.File

/**
 * 引擎持久化接口，由 Web 层用数据库实现。
 * 所有方法都带 userId，实现方负责按用户隔离。
 */
interface EngineStore {
    fun getCache(userId: Long, key: String): String?
    fun putCache(userId: Long, key: String, value: String, deadline: Long)
    fun deleteCache(userId: Long, key: String)

    fun getCookie(userId: Long, domain: String): String?
    fun putCookie(userId: Long, domain: String, cookie: String)
    fun deleteCookie(userId: Long, domain: String)
}

/** 内存实现，用于测试或未初始化时 */
class MemoryEngineStore : EngineStore {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private val cookies = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun getCache(userId: Long, key: String): String? {
        val v = cache["$userId|$key"] ?: return null
        if (v.second != 0L && v.second < System.currentTimeMillis()) return null
        return v.first
    }

    override fun putCache(userId: Long, key: String, value: String, deadline: Long) {
        cache["$userId|$key"] = value to deadline
    }

    override fun deleteCache(userId: Long, key: String) {
        cache.remove("$userId|$key")
    }

    override fun getCookie(userId: Long, domain: String): String? = cookies["$userId|$domain"]

    override fun putCookie(userId: Long, domain: String, cookie: String) {
        cookies["$userId|$domain"] = cookie
    }

    override fun deleteCookie(userId: Long, domain: String) {
        cookies.remove("$userId|$domain")
    }
}

/**
 * 引擎全局配置
 */
object Engine {
    @Volatile
    var store: EngineStore = MemoryEngineStore()

    /** 书源 JS 可读写的缓存目录（java.downloadFile 等） */
    @Volatile
    var cacheDir: File = File(System.getProperty("java.io.tmpdir"), "legado-web")

    /** 用于加密书源登录信息的密钥（至少 16 字节） */
    @Volatile
    var secret: String = "legado-web-default-secret-key"

    @Volatile
    var userAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    /** 目录/正文并发页数 */
    @Volatile
    var threadCount: Int = 8

    /** 可选的无头浏览器实现，用于 webView:true 的书源 */
    @Volatile
    var webViewProvider: WebViewProvider? = null
}
