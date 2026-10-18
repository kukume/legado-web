package io.legado.app.exception

/**
 * 不记录错误堆栈的异常
 */
open class NoStackTraceException(msg: String) : Exception(msg) {
    override fun fillInStackTrace(): Throwable = this
}

class TocEmptyException(msg: String) : NoStackTraceException(msg)

class ContentEmptyException(msg: String) : NoStackTraceException(msg)

class ConcurrentException(msg: String, val waitTime: Long) : NoStackTraceException(msg)

class RegexTimeoutException(msg: String) : NoStackTraceException(msg)

/** 服务端不支持的书源功能 */
class UnsupportedOnServerException(feature: String) :
    NoStackTraceException("网页版暂不支持: $feature")
