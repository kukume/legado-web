package io.legado.app.help

import io.legado.app.help.engine.Engine
import io.legado.app.help.engine.EngineContext
import io.legado.app.model.analyzeRule.QueryTTF
import io.legado.app.utils.LruCache
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.memorySize
import java.io.File

private val queryTTFMap = LruCache<String, QueryTTF>(4)

/**
 * 最多只缓存50M的数据,防止OOM
 */
private val memoryLruCache = object : LruCache<String, Any>(1024 * 1024 * 50) {
    override fun sizeOf(key: String, value: Any): Int {
        return value.toString().memorySize()
    }
}

object AppCacheManager {

    fun put(key: String, queryTTF: QueryTTF) {
        queryTTFMap.put(key, queryTTF)
    }

    fun getQueryTTF(key: String): QueryTTF? {
        return queryTTFMap[key]
    }

}

/**
 * js 中的 cache 对象。
 * 服务端版本：所有 key 自动按当前用户隔离，数据库部分由 [Engine.store] 实现，
 * 字节/文件缓存写入 [Engine.cacheDir]。
 */
@Suppress("unused")
object CacheManager {

    private val uid: Long get() = EngineContext.currentUserId

    private fun memKey(key: String) = "$uid|$key"

    private fun fileOf(key: String): File {
        val dir = File(Engine.cacheDir, "acache/$uid")
        dir.mkdirs()
        return File(dir, MD5Utils.md5Encode(key))
    }

    /**
     * saveTime 单位为秒
     */
    @JvmOverloads
    fun put(key: String, value: Any, saveTime: Int = 0) {
        val deadline =
            if (saveTime == 0) 0 else System.currentTimeMillis() + saveTime * 1000L
        when (value) {
            is ByteArray -> putBytes(key, value, deadline)
            else -> Engine.store.putCache(uid, key, value.toString(), deadline)
        }
    }

    fun putMemory(key: String, value: Any) {
        memoryLruCache.put(memKey(key), value)
    }

    //从内存中获取数据 使用lruCache
    fun getFromMemory(key: String): Any? {
        return memoryLruCache[memKey(key)]
    }

    fun deleteMemory(key: String) {
        memoryLruCache.remove(memKey(key))
    }

    fun get(key: String): String? {
        return Engine.store.getCache(uid, key)
    }

    fun getInt(key: String): Int? {
        return get(key)?.toIntOrNull()
    }

    fun getLong(key: String): Long? {
        return get(key)?.toLongOrNull()
    }

    fun getDouble(key: String): Double? {
        return get(key)?.toDoubleOrNull()
    }

    fun getFloat(key: String): Float? {
        return get(key)?.toFloatOrNull()
    }

    private fun putBytes(key: String, value: ByteArray, deadline: Long) {
        val file = fileOf(key)
        file.writeBytes(value)
        File(file.path + ".t").writeText(deadline.toString())
    }

    fun getByteArray(key: String): ByteArray? {
        val file = fileOf(key)
        if (!file.exists()) return null
        val deadline = File(file.path + ".t").takeIf { it.exists() }?.readText()?.toLongOrNull() ?: 0
        if (deadline != 0L && deadline < System.currentTimeMillis()) {
            file.delete()
            return null
        }
        return file.readBytes()
    }

    fun putFile(key: String, value: String, saveTime: Int = 0) {
        val deadline =
            if (saveTime == 0) 0 else System.currentTimeMillis() + saveTime * 1000L
        putBytes(key, value.toByteArray(), deadline)
    }

    fun getFile(key: String): String? {
        return getByteArray(key)?.let { String(it) }
    }

    fun delete(key: String) {
        Engine.store.deleteCache(uid, key)
        deleteMemory(key)
        val file = fileOf(key)
        file.delete()
        File(file.path + ".t").delete()
    }
}
