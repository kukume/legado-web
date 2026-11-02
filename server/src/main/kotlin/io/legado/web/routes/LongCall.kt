package io.legado.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.legado.web.auth.AUTH_JWT
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.user
import io.legado.web.engine.ClientTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 可能很久才完成的请求（书源脚本等用户在网页里登录、过验证码）。
 *
 * 反向代理对单个请求有超时（如 Cloudflare 100 秒后返回 524），超时断开后请求被取消，脚本和打开的网页也随之结束。
 * 这里把处理放到后台执行：[FIRST_WAIT_MS] 内完成就直接返回结果；否则返回 202 `{"pending": id}`，
 * 前端再用 `GET /api/jobs/{id}` 分段等待结果（每次最多 [POLL_WAIT_MS]），任何一个请求都不会挂太久。
 * 请求断开也不会取消处理；前端不再来取结果（如关闭了页面）超过 [ABANDON_MS] 时才取消。
 */
object LongCalls {
    private const val FIRST_WAIT_MS = 20_000L
    private const val POLL_WAIT_MS = 25_000L
    private const val ABANDON_MS = 90_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Job(val userId: Long, val deferred: Deferred<Any>) {
        @Volatile
        var lastPoll = System.currentTimeMillis()
    }

    private val jobs = ConcurrentHashMap<String, Job>()

    suspend fun respond(call: ApplicationCall, block: suspend () -> Any) {
        val userId = call.user.id
        val tab = currentCoroutineContext()[ClientTab] ?: EmptyCoroutineContext
        val deferred = scope.async(tab) { block() }
        val done = try {
            withTimeoutOrNull(FIRST_WAIT_MS) { deferred.join() } != null
        } catch (e: CancellationException) {
            // 前端自己取消了请求（如切换章节）
            deferred.cancel()
            throw e
        }
        if (done) {
            call.respond(deferred.await())
            return
        }
        cleanup()
        val id = UUID.randomUUID().toString()
        jobs[id] = Job(userId, deferred)
        call.respond(HttpStatusCode.Accepted, mapOf("pending" to id))
    }

    suspend fun poll(call: ApplicationCall, id: String) {
        cleanup()
        val job = jobs[id]?.takeIf { it.userId == call.user.id } ?: throw NotFoundException("任务不存在或已过期")
        job.lastPoll = System.currentTimeMillis()
        val done = withTimeoutOrNull(POLL_WAIT_MS) { job.deferred.join() } != null
        if (!done) {
            job.lastPoll = System.currentTimeMillis()
            call.respond(HttpStatusCode.Accepted, mapOf("pending" to id))
            return
        }
        jobs.remove(id)
        call.respond(job.deferred.await())
    }

    /** 没人来取的任务：未完成的取消，已完成的丢弃 */
    private fun cleanup() {
        val now = System.currentTimeMillis()
        jobs.entries.removeIf { (_, j) ->
            (now - j.lastPoll > ABANDON_MS).also { if (it) j.deferred.cancel() }
        }
    }
}

/** 见 [LongCalls] */
suspend fun ApplicationCall.respondLong(block: suspend () -> Any) = LongCalls.respond(this, block)

fun Route.longCallRoutes() {
    authenticate(AUTH_JWT) {
        get("/api/jobs/{id}") {
            LongCalls.poll(call, call.parameters["id"].orEmpty())
        }
    }
}
