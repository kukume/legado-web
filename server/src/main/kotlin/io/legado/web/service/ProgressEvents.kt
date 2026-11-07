package io.legado.web.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 阅读进度变化：推送给同一用户的其他在线端（网页的其他标签页、其他设备、IDE 插件） */
data class ProgressEvent(
    val bookId: Long,
    /** 章节序号（服务器目录中的序号） */
    val index: Int,
    /** 章内位置（比例 0~10000：视口顶部在本章内容中的位置） */
    val pos: Int,
    val title: String?,
    val time: Long,
    /** 发出这次进度的客户端（网页标签页 id 或插件实例 id），推送时跳过它自己 */
    val client: String?,
)

/**
 * 多端同步进度：任何一端保存了进度（[ShelfService.saveProgress] 实际更新了才算），推送给同一用户的其他连接。
 * 只在内存里转发，不保证送达：离线的一端下次打开书时会读取服务器上的进度。
 */
object ProgressEvents {

    class Subscription internal constructor(val userId: Long, val client: String?) {
        /** 只保留最近的几条：进度只关心最新位置，处理不过来时丢掉旧的 */
        val channel = Channel<ProgressEvent>(16, BufferOverflow.DROP_OLDEST)
    }

    private val subs = ConcurrentHashMap<Long, CopyOnWriteArrayList<Subscription>>()

    /** 用户偏好的 key：值为 true 时开启多端阅读同步（“我的”页面里设置，默认关闭） */
    const val PREF_KEY = "progressSync"

    /** 读取用户是否开启了多端阅读同步（由 Application 设置为读数据库） */
    @Volatile
    var loadUserEnabled: (Long) -> Boolean = { false }

    /** 用户的开关（读一次后缓存，用户修改时 [invalidate]） */
    private val userEnabled = ConcurrentHashMap<Long, Boolean>()

    /** 用户改了自己的开关 */
    fun invalidate(userId: Long) {
        userEnabled.remove(userId)
    }

    private fun isEnabled(userId: Long) = userEnabled.computeIfAbsent(userId) { runCatching { loadUserEnabled(it) }.getOrDefault(false) }

    /** SSE 的 data：单行 JSON（多行时客户端要拼接多个 data 行） */
    private val compactGson = com.google.gson.Gson()

    fun toJson(e: ProgressEvent): String = compactGson.toJson(e)

    fun subscribe(userId: Long, client: String?): Subscription =
        Subscription(userId, client?.takeIf { it.isNotBlank() }).also {
            subs.computeIfAbsent(userId) { CopyOnWriteArrayList() }.add(it)
        }

    fun unsubscribe(sub: Subscription) {
        sub.channel.close()
        subs.computeIfPresent(sub.userId) { _, list -> list.apply { remove(sub) }.takeIf { it.isNotEmpty() } }
    }

    fun publish(userId: Long, event: ProgressEvent) {
        // 用户没有开启多端阅读同步：进度照常保存，只是不推送给其他端
        if (!isEnabled(userId)) return
        subs[userId]?.forEach { if (event.client == null || it.client != event.client) it.channel.trySend(event) }
    }
}
