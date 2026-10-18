package io.legado.app.help.config

import io.legado.app.help.engine.Engine

/**
 * Android 版 AppConfig 的服务端精简版，只保留引擎用到的配置
 */
object AppConfig {
    val userAgent: String get() = Engine.userAgent
    val threadCount: Int get() = Engine.threadCount
    const val replaceEnableDefault = true
    const val chineseConverterType = 0
    const val tocCountWords = false
    const val blockSourceNavigation = false
    const val adaptSpecialStyle = true
    val themeMode: String? = "0"
}
