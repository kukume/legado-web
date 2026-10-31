package io.legado.web.service

import io.legado.app.help.engine.Engine
import io.legado.app.help.http.CookieStore
import io.legado.app.utils.NetworkUtils

/**
 * 远程浏览器自己的 Cookie，相当于 App 里内置浏览器（WebView）的 Cookie：和书源用的 Cookie 分开保存。
 *
 * App 中书源脚本 cookie.removeCookie 只删除书源用的 Cookie，WebView 里仍是登录状态，
 * 打开内置浏览器后网站直接是已登录，关闭时 WebView 的 Cookie 又写回书源（如大灰狼的“番茄登录”先清除 Cookie 再打开网页）。
 * 这里同样：远程网页关闭时（完成或取消）保存网页的 Cookie，下次打开同一网站时先带上它，再合并书源用的 Cookie。
 *
 * 按网站（二级域名）保存在书源 Cookie 同一张表中，域名加 [PREFIX] 前缀区分，书源读不到
 */
object BrowserCookies {

    private const val PREFIX = "webview|"

    private fun key(url: String) = PREFIX + NetworkUtils.getSubDomain(url)

    /** 网页的 Cookie（name=value 合并：同名覆盖，其他保持原样） */
    fun save(userId: Long, url: String, cookie: String) {
        if (userId == 0L || cookie.isBlank()) return
        val k = key(url)
        val old = Engine.store.getCookie(userId, k).orEmpty()
        Engine.store.putCookie(userId, k, CookieStore.mergeCookieValues(old, cookie))
    }

    /** 打开网页时带上的 Cookie：浏览器自己的，再合并书源用的（同名时用书源的） */
    fun forPage(userId: Long, url: String, sourceCookie: String?): String {
        val own = if (userId == 0L) "" else Engine.store.getCookie(userId, key(url)).orEmpty()
        return CookieStore.mergeCookieValues(own, sourceCookie.orEmpty())
    }
}
