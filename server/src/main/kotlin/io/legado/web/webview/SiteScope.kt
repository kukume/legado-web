package io.legado.web.webview

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 网站范围：按公共后缀列表（publicsuffix.org，OkHttp 自带）取可注册域名，允许它本身和它的所有子域名。
 * 如 www.google.com → google.com，允许 google.com、www.google.com、accounts.google.com；
 * a.github.io → a.github.io（github.io 是公共后缀），不允许 b.github.io。IP 地址只允许它自己
 *
 * @param urls 决定范围的网址（书源地址、打开的网页）；都无法解析时不限制
 */
class SiteScope(urls: Collection<String?>) {

    val domains: Set<String> = urls.mapNotNull { registrableDomain(it) }.toSet()

    /** 非 http(s) 地址（about:blank、data: 等）不访问其他网站，不限制 */
    fun allows(url: String): Boolean {
        if (domains.isEmpty()) return true
        val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return true
        return domains.any { host == it || host.endsWith(".$it") }
    }

    companion object {
        fun registrableDomain(url: String?): String? {
            val u = url?.substringBefore(",{")?.trim()?.toHttpUrlOrNull() ?: return null
            return (u.topPrivateDomain() ?: u.host).lowercase()
        }
    }
}
