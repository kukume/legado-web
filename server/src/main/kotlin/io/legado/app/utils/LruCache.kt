package io.legado.app.utils

/**
 * 线程安全的简易 LRU 缓存，替代 androidx.collection.LruCache
 */
open class LruCache<K : Any, V : Any>(private val maxSize: Int) {

    private var size = 0
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {}

    protected open fun sizeOf(key: K, value: V): Int = 1

    @Synchronized
    operator fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V): V? {
        val previous = map.put(key, value)
        size += safeSizeOf(key, value)
        if (previous != null) size -= safeSizeOf(key, previous)
        trimToSize(maxSize)
        return previous
    }

    @Synchronized
    fun remove(key: K): V? {
        val previous = map.remove(key)
        if (previous != null) size -= safeSizeOf(key, previous)
        return previous
    }

    @Synchronized
    fun snapshot(): Map<K, V> = LinkedHashMap(map)

    @Synchronized
    fun evictAll() {
        map.clear()
        size = 0
    }

    private fun trimToSize(max: Int) {
        val it = map.entries.iterator()
        while (size > max && it.hasNext()) {
            val entry = it.next()
            it.remove()
            size -= safeSizeOf(entry.key, entry.value)
        }
    }

    private fun safeSizeOf(key: K, value: V): Int = sizeOf(key, value).coerceAtLeast(0)
}
