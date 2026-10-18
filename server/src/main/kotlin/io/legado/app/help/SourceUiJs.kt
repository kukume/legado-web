package io.legado.app.help

import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.help.engine.EngineContext
import io.legado.app.help.source.clearExploreKindsCache

/**
 * 书源登录界面 / 发现页控件中按钮脚本的 `java` 对象，对应阅读 App 的 SourceLoginJsExtensions。
 * 界面相关的调用（刷新界面、更新表单、复制文本）记录到 [EngineContext]，由前端处理。
 */
@Suppress("unused")
class SourceUiJs(private val source: BaseSource) : JsExtensions {

    override fun getSource(): BaseSource = source

    override fun getTag(): String = source.getTag()

    /** 更新登录界面表单的值 */
    fun upLoginData(data: Map<String, Any?>?) {
        data ?: return
        EngineContext.current?.uiData?.putAll(data.mapValues { it.value?.toString() })
    }

    /** 重新渲染登录界面 */
    @JvmOverloads
    fun reLoginView(deltaUp: Boolean = false) {
        EngineContext.current?.events?.add("reLoginView")
    }

    /** 重新生成发现页 */
    fun refreshExplore() {
        (source as? BookSource)?.clearExploreKindsCache()
        EngineContext.current?.events?.add("refreshExplore")
    }

    /** 复制文本：交给前端写入剪贴板 */
    fun copyText(text: String) {
        EngineContext.current?.clipboard = text
    }

    /** 朗读引擎缓存，网页版无此功能 */
    fun clearTtsCache() = Unit
}
