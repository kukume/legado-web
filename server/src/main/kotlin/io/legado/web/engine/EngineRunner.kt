package io.legado.web.engine

import io.legado.app.help.engine.DebugSink
import io.legado.app.help.engine.EngineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 以指定用户身份执行书源引擎代码（IO 线程 + 用户隔离上下文）
 * @param ctx 需要读取脚本产生的提示/打开网页请求时，可传入自己创建的上下文
 *
 * 超时计时在脚本等待用户操作（书源网页、验证码）期间暂停，用户操作完成后重新计时。
 */
suspend fun <T> runEngine(
    userId: Long,
    timeoutMs: Long = 120_000,
    debugSink: DebugSink? = null,
    ctx: EngineContext = EngineContext(userId, debugSink),
    block: suspend () -> T
): T = withContext(Dispatchers.IO + ctx) {
    if (ctx.clientId == null) ctx.clientId = coroutineContext[ClientTab]?.id
    val job = async { block() }
    job.invokeOnCompletion { if (it is CancellationException) ctx.cancelled = true }
    try {
        val start = System.currentTimeMillis()
        while (true) {
            // 等待用户操作期间不计时：截止时间 = 开始时间 + 超时 + 已等待用户的时长
            val waiting = ctx.waitingForUser.get() > 0
            val wait = if (waiting) 5_000 else start + timeoutMs + ctx.userWaitMs.get() - System.currentTimeMillis()
            if (wait > 0 && withTimeoutOrNull(wait) { job.join() } != null) break
            if (job.isCompleted) break
            if (waiting || ctx.waitingForUser.get() > 0) continue
            if (start + timeoutMs + ctx.userWaitMs.get() > System.currentTimeMillis()) continue
            ctx.cancelled = true
            job.cancel()
            withTimeout(0) {} // 抛出 TimeoutCancellationException，与原先 withTimeout 的行为一致
        }
        job.await()
    } catch (e: CancellationException) {
        ctx.cancelled = true
        job.cancel()
        throw e
    }
}
