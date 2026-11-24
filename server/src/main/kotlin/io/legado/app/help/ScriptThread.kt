package io.legado.app.help

import com.script.rhino.RhinoContext
import org.htmlunit.corejs.javascript.Context

/**
 * 书源 JS 里的 java.lang.Thread：只提供 sleep（书源常用来等一会儿再请求）。
 * 真正的 Thread 被 RhinoClassShutter 屏蔽（起线程能绕过书源超时和取消），由 RhinoWrapFactory 换成这个类。
 * 分段睡眠并检查取消，搜索超时或用户取消时立即停止；单次最多睡 [MAX_SLEEP_MS]
 */
object ScriptThread {

    private const val MAX_SLEEP_MS = 10_000L
    private const val STEP_MS = 50L

    @JvmStatic
    fun sleep(millis: Long) {
        val cx = Context.getCurrentContext() as? RhinoContext
        var left = millis.coerceIn(0, MAX_SLEEP_MS)
        while (left > 0) {
            cx?.ensureActive()
            val step = minOf(left, STEP_MS)
            try {
                Thread.sleep(step)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            left -= step
        }
        cx?.ensureActive()
    }

    @JvmStatic
    fun sleep(millis: Long, @Suppress("UNUSED_PARAMETER") nanos: Int) = sleep(millis)
}
