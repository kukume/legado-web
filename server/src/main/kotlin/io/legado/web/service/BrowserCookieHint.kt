package io.legado.web.service

import io.legado.app.data.entities.BaseSource
import org.htmlunit.corejs.javascript.EvaluatorException

/**
 * 判断书源用 java.startBrowserAwait 打开网页后，会不会读取 Cookie。
 *
 * 书源不会声明“打开网页是为了登录后拿 Cookie”，App 中也不需要（内置浏览器与书源共用 Cookie）。
 * 网页版读不到浏览器的 Cookie，需要让用户粘贴——但只有打开网页后要读 Cookie 的按钮才需要，例如：
 * - 番茄登录：打开 fanqienovel.com，之后 cookie.getKey("fanqienovel.com", "sessionid") → 需要
 * - 用户后台：先读 qttoken 换一次性交接码，再打开网页，之后不再读取 → 不需要
 *
 * 做法：从 JS 调用栈（Rhino 解释模式）找到调用 startBrowserAwait 的书源函数，
 * 在书源代码中找到这个函数，看打开网页之后的代码里有没有读取 Cookie。
 */
object BrowserCookieHint {

    /** 读取 Cookie 的写法：cookie.getKey / cookie.getCookie / java.getCookie，以及 getXxxCookie / readXxxCookie 之类的辅助函数 */
    private val cookieRead = Regex("""(?:\bcookie\s*\.\s*(?:getKey|getCookie)|\bjava\s*\.\s*getCookie|\bget\w*Cookie\w*|\bread\w*Cookie\w*)\s*\(""")
    private val browserCall = Regex("""\bstartBrowserAwait\s*\(""")

    /**
     * @return true：之后会读 Cookie；false：不会；null：无法判断（找不到调用函数等）
     */
    fun needsCookie(source: BaseSource): Boolean? {
        val fn = callerFunction() ?: return null
        val code = listOfNotNull(source.getLoginJs(), source.jsLib).joinToString("\n")
        return analyze(code, fn)
    }

    /** 当前 JS 调用栈中最内层的具名函数（即调用 java.startBrowserAwait 的书源函数） */
    private fun callerFunction(): String? = runCatching {
        EvaluatorException("stack").scriptStack
            .firstNotNullOfOrNull { el -> el.functionName?.takeIf { it.isNotBlank() && it != "anonymous" } }
    }.getOrNull()

    /** 在代码中找到函数 [fn]，判断其中 startBrowserAwait 之后是否读取 Cookie */
    internal fun analyze(code: String, fn: String): Boolean? {
        val name = Regex.escape(fn)
        val decl = Regex("""(?:\bfunction\s+$name\s*\(|\b$name\s*[:=]\s*(?:async\s+)?function\s*\(|\b$name\s*[:=]\s*\([^)]*\)\s*=>\s*\{)""")
        var found = false
        for (m in decl.findAll(code)) {
            val open = code.indexOf('{', m.range.last)
            if (open < 0) continue
            val body = code.substring(open, matchBrace(code, open))
            val call = browserCall.find(body) ?: continue
            found = true
            if (cookieRead.containsMatchIn(body.substring(call.range.last))) return true
        }
        return if (found) false else null
    }

    /** 从 [open]（'{' 的位置）找到匹配的 '}'，跳过字符串、模板字符串和注释；返回结束位置（不含） */
    private fun matchBrace(code: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < code.length) {
            when (val c = code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
                '"', '\'', '`' -> {
                    i++
                    while (i < code.length && code[i] != c) {
                        if (code[i] == '\\') i++
                        i++
                    }
                }
                '/' -> if (i + 1 < code.length) {
                    when (code[i + 1]) {
                        '/' -> i = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                        '*' -> i = code.indexOf("*/", i + 2).let { if (it < 0) code.length else it + 1 }
                    }
                }
            }
            i++
        }
        return code.length
    }
}
