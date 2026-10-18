package io.legado.app.help.engine

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 书源引擎的执行上下文。
 *
 * Android 版是单用户的，cookie / 缓存 / 书源变量都是全局的；
 * 服务端需要按用户隔离，这里通过协程上下文 + ThreadLocal 把当前用户传递到引擎内部
 * （包括 JS 里同步调用的 java.ajax 等方法和 OkHttp 拦截器）。
 */
class EngineContext(
    val userId: Long,
    /** 调试输出，书源调试时不为空 */
    val debugSink: DebugSink? = null,
) : AbstractCoroutineContextElement(Key), ThreadContextElement<EngineContext?> {

    /** 脚本调用 java.toast / longToast 产生的提示，由 Web 层返回给前端 */
    val messages: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** 脚本调用 startBrowser / openUrl 请求打开的网页，由前端在新标签页打开 */
    val openUrls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** 脚本请求的刷新动作（refreshBookInfo / refreshBookToc / refreshContent） */
    val events: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** 脚本调用 upLoginData 更新的表单值 */
    val uiData: MutableMap<String, String?> = java.util.Collections.synchronizedMap(linkedMapOf())

    /** 脚本调用 copyText 要复制的文本 */
    @Volatile
    var clipboard: String? = null

    /** 正在等待用户在网页中操作的数量；大于 0 时暂停执行超时计时 */
    val waitingForUser = java.util.concurrent.atomic.AtomicInteger(0)

    /** 已结束的等待用户操作的总时长（毫秒），不计入执行超时 */
    val userWaitMs = java.util.concurrent.atomic.AtomicLong(0)

    /** 不是用户主动发起的操作（如加载图片时执行图片参数中的 js）：不弹出书源网页 */
    @Volatile
    var suppressUi: Boolean = false

    /** 发起请求的浏览器标签页（弹窗优先只显示在这个标签页） */
    @Volatile
    var clientId: String? = null

    /** 所在请求已取消（超时或客户端断开），阻塞等待用户操作的脚本据此结束 */
    @Volatile
    var cancelled: Boolean = false

    companion object Key : CoroutineContext.Key<EngineContext> {
        private val threadLocal = ThreadLocal<EngineContext?>()

        val current: EngineContext? get() = threadLocal.get()

        val currentUserId: Long get() = threadLocal.get()?.userId ?: 0L

        /** 在指定上下文中同步执行（用于 OkHttp 线程等非协程场景） */
        inline fun <T> with(ctx: EngineContext?, block: () -> T): T {
            if (ctx == null) return block()
            val old = ctx.updateThreadContext(kotlin.coroutines.EmptyCoroutineContext)
            try {
                return block()
            } finally {
                ctx.restoreThreadContext(kotlin.coroutines.EmptyCoroutineContext, old)
            }
        }
    }

    override fun updateThreadContext(context: CoroutineContext): EngineContext? {
        val old = threadLocal.get()
        threadLocal.set(this)
        return old
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: EngineContext?) {
        if (oldState == null) threadLocal.remove() else threadLocal.set(oldState)
    }
}

fun interface DebugSink {
    fun log(state: Int, msg: String)
}
