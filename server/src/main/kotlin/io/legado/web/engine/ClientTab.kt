package io.legado.web.engine

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 发起请求的浏览器标签页（请求头 X-Client-Id）。
 * 书源脚本弹出的网页、验证码只推送给这个标签页，而不是该用户打开的所有页面。
 */
class ClientTab(val id: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ClientTab>
}
