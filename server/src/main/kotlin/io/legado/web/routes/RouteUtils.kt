package io.legado.web.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondTextWriter
import io.legado.web.auth.BadRequestException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Writer

data class OkResponse(val ok: Boolean = true, val message: String? = null)

fun ApplicationCall.longParam(name: String): Long =
    parameters[name]?.toLongOrNull() ?: throw BadRequestException("参数 $name 无效")

fun ApplicationCall.intParam(name: String): Int =
    parameters[name]?.toIntOrNull() ?: throw BadRequestException("参数 $name 无效")

/**
 * NDJSON 流式响应：每行一个 JSON 对象，前端用 fetch + ReadableStream 逐行读取
 */
suspend fun ApplicationCall.respondNdjson(block: suspend (emit: suspend (Any) -> Unit) -> Unit) {
    response.header(HttpHeaders.CacheControl, "no-cache")
    response.header("X-Accel-Buffering", "no")
    respondTextWriter(ContentType.parse("application/x-ndjson; charset=UTF-8")) {
        val writer: Writer = this
        val mutex = Mutex()
        val emit: suspend (Any) -> Unit = { obj ->
            val line = compactGson.toJson(obj)
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    writer.write(line)
                    writer.write("\n")
                    writer.flush()
                }
            }
        }
        block(emit)
    }
}

/** 非格式化的 Gson（GSON 默认开启了 pretty printing） */
val compactGson by lazy {
    com.google.gson.GsonBuilder()
        .disableHtmlEscaping()
        .create()
}
