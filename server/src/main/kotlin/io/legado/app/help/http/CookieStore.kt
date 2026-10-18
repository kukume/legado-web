@file:Suppress("unused")

package io.legado.app.help.http

import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.equalsRegex
import io.legado.app.constant.AppPattern.semicolonRegex
import io.legado.app.help.CacheManager
import io.legado.app.help.engine.Engine
import io.legado.app.help.engine.EngineContext
import io.legado.app.help.http.CookieManager.getCookieNoSession
import io.legado.app.help.http.CookieManager.mergeCookiesToMap
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.splitNotBlank

object CookieStore {

    private val uid: Long get() = EngineContext.currentUserId

    /**
     *保存cookie到数据库，会自动识别url的二级域名
     */
    fun setCookie(url: String, cookie: String?) {
        try {
            saveCookie(url, cookie)
        } catch (e: Exception) {
            AppLog.put("保存Cookie失败\n$e", e)
        }
    }

    private fun saveCookie(url: String, cookie: String?) {
        val domain = NetworkUtils.getSubDomain(url)
        val cookieValue = cookie ?: ""
        Engine.store.putCookie(uid, domain, cookieValue)
        CacheManager.putMemory("${domain}_cookie", cookieValue)
    }


    fun replaceCookie(url: String, cookie: String) {
        if (url.isNullOrEmpty() || cookie.isNullOrEmpty()) {
            return
        }
        setCookie(url, mergeCookie(url, cookie))
    }

    internal fun restoreCookie(url: String, cookie: String) {
        if (url.isNullOrEmpty() || cookie.isNullOrEmpty()) {
            return
        }
        saveCookie(url, mergeCookie(url, cookie))
    }

    private fun mergeCookie(url: String, cookie: String): String {
        return mergeCookieValues(getCookieNoSession(url), cookie)
    }

    internal fun mergeCookieValues(oldCookie: String, cookie: String): String {
        if (oldCookie.isEmpty()) {
            return cookie
        }
        val cookieMap = cookieToMap(oldCookie)
        cookieMap.putAll(cookieToMap(cookie))
        return mapToCookie(cookieMap).orEmpty()
    }

    /**
     *获取url所属的二级域名的cookie
     */
    fun getCookie(url: String): String {
        val domain = NetworkUtils.getSubDomain(url)

        val cookie = getCookieNoSession(url)
        val sessionCookie = CookieManager.getSessionCookie(domain)

        val cookieMap = mergeCookiesToMap(cookie, sessionCookie)

        var ck = mapToCookie(cookieMap) ?: ""
        while (ck.length > 4096) {
            val removeKey = cookieMap.keys.random()
            CookieManager.removeCookie(url, removeKey)
            cookieMap.remove(removeKey)
            ck = mapToCookie(cookieMap) ?: ""
        }
        return ck
    }

    fun getKey(url: String, key: String): String {
        val cookie = getCookie(url)
        val sessionCookie = CookieManager.getSessionCookie(url)
        val cookieMap = mergeCookiesToMap(cookie, sessionCookie)
        return cookieMap[key] ?: ""
    }

    fun removeCookie(url: String) {
        val domain = NetworkUtils.getSubDomain(url)
        Engine.store.deleteCookie(uid, domain)
        CacheManager.deleteMemory("${domain}_cookie")
        CacheManager.deleteMemory("${domain}_session_cookie")
    }

    fun cookieToMap(cookie: String): MutableMap<String, String> {
        val cookieMap = mutableMapOf<String, String>()
        if (cookie.isBlank()) {
            return cookieMap
        }
        val pairArray = cookie.split(semicolonRegex).dropLastWhile { it.isEmpty() }.toTypedArray()
        for (pair in pairArray) {
            val pairs = pair.split(equalsRegex, 2).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (pairs.size <= 1) {
                continue
            }
            val key = pairs[0].trim { it <= ' ' }
            val value = pairs[1]
            if (value.isNotBlank() || value.trim { it <= ' ' } == "null") {
                cookieMap[key] = value.trim { it <= ' ' }
            }
        }
        return cookieMap
    }

    fun mapToCookie(cookieMap: Map<String, String>?): String? {
        if (cookieMap.isNullOrEmpty()) {
            return null
        }
        val builder = StringBuilder()
        cookieMap.keys.forEachIndexed { index, key ->
            if (index > 0) builder.append("; ")
            builder.append(key).append("=").append(cookieMap[key])
        }
        return builder.toString()
    }


}
