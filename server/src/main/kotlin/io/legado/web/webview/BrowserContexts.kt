package io.legado.web.webview

import com.google.gson.JsonObject
import org.slf4j.LoggerFactory
import java.io.File

/**
 * 记录本服务在浏览器里创建的浏览器上下文（每个网页一个，相当于无痕窗口），保存在数据目录的文件里。
 *
 * 正常情况下网页用完就销毁；但服务被强制结束（IDE 停止、kill）时来不及销毁，而浏览器常放在代理服务后面，
 * 代理与浏览器的连接一直保持，浏览器感知不到断开，这些网页会一直留着。
 * 下次连接浏览器时，销毁上次留下的（只处理本服务自己创建的，不影响同一个浏览器里的其他使用者）。
 */
class BrowserContexts(private val file: File) {

    private val logger = LoggerFactory.getLogger(BrowserContexts::class.java)

    /** 本次运行创建、还没销毁的：浏览器地址 → 上下文 id */
    private val current = LinkedHashSet<Pair<String, String>>()

    /** 上次运行留下的（启动时从文件读取），连接对应的浏览器时销毁 */
    private val stale = LinkedHashSet<Pair<String, String>>()

    init {
        runCatching {
            if (file.isFile) {
                file.readLines().mapNotNull { line ->
                    val i = line.lastIndexOf(' ')
                    if (i > 0) line.substring(0, i) to line.substring(i + 1) else null
                }.forEach { stale += it }
            }
        }
    }

    @Synchronized
    fun add(cdp: String, contextId: String) {
        current += cdp to contextId
        save()
    }

    @Synchronized
    fun remove(cdp: String, contextId: String) {
        current -= cdp to contextId
        save()
    }

    /** 连接上浏览器后调用：销毁上次运行在这个浏览器里留下的网页 */
    internal suspend fun cleanup(cdp: String, conn: CdpWebViewProvider.CdpConnection) {
        val leftovers = synchronized(this) { stale.filter { it.first == cdp }.also { stale.removeAll(it.toSet()); save() } }
        if (leftovers.isEmpty()) return
        var closed = 0
        for ((_, id) in leftovers) {
            runCatching {
                conn.send("Target.disposeBrowserContext", JsonObject().apply { addProperty("browserContextId", id) })
                closed++
            }
        }
        if (closed > 0) logger.info("已关闭上次运行留在浏览器里的 {} 个网页", closed)
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText((stale + current).joinToString("") { "${it.first} ${it.second}\n" })
        }
    }
}
