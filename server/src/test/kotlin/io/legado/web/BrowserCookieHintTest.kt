package io.legado.web

import com.google.gson.JsonParser
import io.legado.web.service.BrowserCookieHint
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrowserCookieHintTest {

    private val code = """
        function fq_login() {
            try { cookie.removeCookie("fanqienovel.com") } catch (e) {}
            java.startBrowserAwait("https://fanqienovel.com/", "登录")
            var sid = String(cookie.getKey("fanqienovel.com", "sessionid"))
            return sid
        }
        function loginqt() {
            let ck = String(cookie.getKey(base, "qttoken"));   // 打开网页之前读取
            let s = "}{ 字符串里的括号不影响 }";
            java.startBrowserAwait(base + '/handoff?code=' + ck, '后台');
        }
        var helper = function () {
            java.startBrowserAwait(url, 'x');
            return getFanqieCookie();   // 通过辅助函数读取
        }
        function noBrowser() { return cookie.getKey("a", "b") }
    """.trimIndent()

    @Test
    fun analyzeSnippets() {
        assertEquals(true, BrowserCookieHint.analyze(code, "fq_login"))
        assertEquals(false, BrowserCookieHint.analyze(code, "loginqt"))
        assertEquals(true, BrowserCookieHint.analyze(code, "helper"))
        assertNull(BrowserCookieHint.analyze(code, "noBrowser"))
        assertNull(BrowserCookieHint.analyze(code, "missing"))
    }

    /** 用户提供的真实书源（文件存在时才检查） */
    @Test
    fun analyzeRealSources() {
        fun load(path: String): String? {
            val f = File(path).takeIf { it.isFile } ?: return null
            val obj = JsonParser.parseString(f.readText()).let { if (it.isJsonArray) it.asJsonArray[0] else it }.asJsonObject
            return listOf("loginUrl", "jsLib").mapNotNull { obj.get(it)?.takeIf { v -> !v.isJsonNull }?.asString }.joinToString("\n")
        }
        load("C:/Users/kuku/Downloads/安卓阅读app-大灰狼融合4.0(vip完全版).json")?.let { c ->
            assertEquals(true, BrowserCookieHint.analyze(c, "fq_login"), "大灰狼 番茄登录")
            assertEquals(false, BrowserCookieHint.analyze(c, "loginqt"), "大灰狼 用户后台")
        }
        load("C:/Users/kuku/Downloads/shuyuan.json")?.let { c ->
            assertEquals(true, BrowserCookieHint.analyze(c, "login_"), "番茄四合一 番茄账号登录")
        }
    }
}
