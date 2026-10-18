package io.legado.app.model

import io.legado.app.help.engine.EngineContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 书源调试日志。服务端版本不保存全局回调，
 * 日志写入当前协程 [EngineContext] 中的 debugSink（只有调试请求才会设置）。
 */
object Debug {

    private val debugTimeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("[mm:ss.SSS]", Locale.getDefault()).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
    }

    /** 当前请求是否处于调试模式 */
    val callback: Any? get() = EngineContext.current?.debugSink

    private val startTimes = java.util.concurrent.ConcurrentHashMap<Any, Long>()

    @Synchronized
    fun log(
        sourceUrl: String?,
        msg: String = "",
        print: Boolean = true,
        isHtml: Boolean = false,
        showTime: Boolean = true,
        state: Int = 1
    ) {
        val sink = EngineContext.current?.debugSink ?: return
        if (!print) return
        var printMsg = msg
        if (isHtml) {
            printMsg = io.legado.app.utils.HtmlFormatter.format(msg)
        }
        if (state == 10 || state == 20 || state == 30 || state == 40) {
            // 源码，单独发送
            sink.log(state, printMsg)
            return
        }
        if (showTime) {
            val start = startTimes.getOrPut(sink) { System.currentTimeMillis() }
            val time = debugTimeFormat.get().format(Date(System.currentTimeMillis() - start))
            printMsg = "$time $printMsg"
        }
        sink.log(state, printMsg)
    }

    /** 当前请求是否正在调试该书源 */
    internal fun isDebugging(sourceUrl: String): Boolean = EngineContext.current?.debugSink != null

    fun log(msg: String?) {
        log(null, msg ?: "", true)
    }

    fun reset(sink: Any) {
        startTimes.remove(sink)
    }
}
