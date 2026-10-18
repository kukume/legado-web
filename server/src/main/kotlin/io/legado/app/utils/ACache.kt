package io.legado.app.utils

import io.legado.app.help.engine.Engine
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Android 版 ACache 的精简服务端实现：按 key 存到文件，支持过期时间（秒）。
 * 注意：此缓存是全局的（不区分用户），只用于 jsLib 下载等与用户无关的内容。
 */
@Suppress("unused")
class ACache private constructor(private val dir: File) {

    companion object {
        private val instances = ConcurrentHashMap<String, ACache>()

        fun get(cacheName: String = "ACache"): ACache = get(File(Engine.cacheDir, cacheName))

        fun get(dir: File): ACache = instances.getOrPut(dir.absolutePath) { ACache(dir) }
    }

    private fun fileOf(key: String): File {
        dir.mkdirs()
        return File(dir, MD5Utils.md5Encode(key))
    }

    private fun deadlineFile(f: File) = File(f.path + ".t")

    fun put(key: String, value: String, saveTime: Int = 0) = put(key, value.toByteArray(), saveTime)

    fun put(key: String, value: ByteArray, saveTime: Int = 0) {
        val f = fileOf(key)
        f.writeBytes(value)
        val d = deadlineFile(f)
        if (saveTime > 0) d.writeText((System.currentTimeMillis() + saveTime * 1000L).toString()) else d.delete()
    }

    fun getAsBinary(key: String): ByteArray? {
        val f = fileOf(key)
        if (!f.exists()) return null
        val d = deadlineFile(f)
        if (d.exists()) {
            val deadline = d.readText().toLongOrNull() ?: 0
            if (deadline in 1 until System.currentTimeMillis()) {
                f.delete()
                d.delete()
                return null
            }
        }
        return f.readBytes()
    }

    fun getAsString(key: String): String? = getAsBinary(key)?.let { String(it) }

    fun remove(key: String): Boolean {
        val f = fileOf(key)
        deadlineFile(f).delete()
        return f.delete()
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
