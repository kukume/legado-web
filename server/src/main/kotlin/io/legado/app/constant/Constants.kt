@file:Suppress("ConstPropertyName", "unused")

package io.legado.app.constant

import io.legado.app.help.engine.Engine
import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.util.Locale

object AppConst {
    const val UA_NAME = "User-Agent"

    val dateFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

    /**
     * Android 版用 androidId 作为书源登录信息的加密密钥，
     * 服务端改为使用配置的 secret（js 中 java.androidId() 也返回它的摘要）
     */
    val androidId: String
        get() = io.legado.app.utils.MD5Utils.md5Encode(Engine.secret)
}

object AppLog {
    private val logger = LoggerFactory.getLogger("legado.engine")

    fun put(msg: String?, throwable: Throwable? = null) {
        if (throwable == null) logger.warn(msg) else logger.warn(msg, throwable)
    }

    fun putDebug(msg: String?, throwable: Throwable? = null) {
        if (throwable == null) logger.debug(msg) else logger.debug(msg, throwable)
    }
}

object BookType {
    const val video = 0b100
    const val text = 0b1000
    const val updateError = 0b10000
    const val audio = 0b100000
    const val image = 0b1000000
    const val webFile = 0b10000000
    const val local = 0b100000000
    const val archive = 0b1000000000
    const val notShelf = 0b100_0000_0000

    const val allBookType = video or text or image or audio or webFile
    const val allBookTypeLocal = video or text or image or audio or webFile or local

    const val localTag = "loc_book"
    const val webDavTag = "webDav::"
}

object BookSourceType {
    const val default = 0
    const val audio = 1
    const val image = 2
    const val file = 3
    const val video = 4
}

object SourceType {
    const val book = 0
    const val rss = 1
}
