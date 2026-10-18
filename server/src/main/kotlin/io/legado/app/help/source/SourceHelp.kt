package io.legado.app.help.source

import io.legado.app.data.entities.BaseSource
import io.legado.app.help.engine.EngineContext
import io.legado.app.model.Debug

/**
 * Android 版 SourceHelp 中与书源脚本相关的部分（其余为数据库操作，服务端由 Web 层负责）
 */
object SourceHelp {

    /** 网页版：视频链接交给前端在新标签页打开 */
    fun openVideoPlayer(source: BaseSource?, url: String, title: String, isFloat: Boolean) {
        if (url.startsWith("http", true)) {
            EngineContext.current?.openUrls?.add(url)
        }
        Debug.log(source?.getKey(), "⇒请求播放视频（$title）: $url")
    }
}
