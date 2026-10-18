@file:Suppress("unused")

package io.legado.app.utils

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory

private val debugLogger = LoggerFactory.getLogger("legado.debug")

fun Throwable.printOnDebug() {
    debugLogger.debug(message, this)
}

/** 服务端没有主线程概念 */
val isMainThread: Boolean get() = false

@OptIn(ExperimentalCoroutinesApi::class)
inline fun <T, R> Flow<T>.mapParallelSafe(
    concurrency: Int,
    crossinline transform: suspend (T) -> R,
): Flow<R> = flatMapMerge(concurrency) { value ->
    flow {
        try {
            emit(transform(value))
        } catch (_: Throwable) {
            currentCoroutineContext().ensureActive()
        }
    }
}.buffer(0)

inline fun <T, R> Flow<T>.mapAsync(
    concurrency: Int,
    crossinline transform: suspend (T) -> R
): Flow<R> = if (concurrency == 1) {
    map { transform(it) }
} else {
    Semaphore(concurrency).let { semaphore ->
        channelFlow {
            collect {
                semaphore.acquire()
                send(async { transform(it) })
            }
        }.map {
            it.await()
        }.onEach { semaphore.release() }
    }
}

fun ByteArray.indexOf(target: ByteArray, start: Int = 0): Int {
    if (target.isEmpty()) return start
    outer@ for (i in start..(size - target.size)) {
        for (j in target.indices) {
            if (this[i + j] != target[j]) continue@outer
        }
        return i
    }
    return -1
}
