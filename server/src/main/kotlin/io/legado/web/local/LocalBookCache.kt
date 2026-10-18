package io.legado.web.local

import java.io.File

/**
 * 解析好的本地书（UMD 整本解压在内存里、MOBI 持有打开的文件）的小型 LRU 缓存：
 * 读一章不必重新解析整本书。文件被替换（修改时间变化）或被挤出缓存时关闭旧对象。
 */
object LocalBookCache {

    private const val MAX = 4

    private class Entry(val modified: Long, val value: Any)

    private val map = object : LinkedHashMap<String, Entry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean {
            if (size <= MAX) return false
            close(eldest.value.value)
            return true
        }
    }

    private fun close(v: Any) {
        runCatching { (v as? AutoCloseable)?.close() ?: (v as? io.legado.app.lib.mobi.MobiBook)?.close() }
    }

    /** 取缓存的解析结果，没有或文件已变化时用 [open] 重新解析 */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(file: File, open: (File) -> T): T = synchronized(this) {
        val key = file.absolutePath
        val modified = file.lastModified()
        map[key]?.let { e ->
            if (e.modified == modified) return e.value as T
            map.remove(key)
            close(e.value)
        }
        val v = open(file)
        map[key] = Entry(modified, v)
        v
    }

    /** 删除书籍文件前调用 */
    fun evict(file: File) = synchronized(this) {
        map.remove(file.absolutePath)?.let { close(it.value) }
    }
}
