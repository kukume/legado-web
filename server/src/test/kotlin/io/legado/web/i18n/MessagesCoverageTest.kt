package io.legado.web.i18n

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 服务端返回给用户的提示都要有英文翻译（resources/i18n/en.json，见 [Messages]）。
 *
 * 扫描源码中会直接返回给用户的文字：异常消息、Messages.translate(call, "…")、压缩包跳过原因、下载任务失败原因，
 * 字符串模板里的 ${'$'}{…} / ${'$'}name 按出现顺序写作 {0} {1}…。缺少翻译时列出“文件:行  原文”，补到 en.json 即可。
 * 只检查含中文的字面量；拼接出来的、来自变量的提示扫描不到。
 */
class MessagesCoverageTest {

    private val sinks = Regex(
        """\b(?:BadRequestException|ForbiddenException|NotFoundException|TooManyRequestsException|UnauthorizedException|""" +
            """LocalBookException|ZLibraryException|NoStackTraceException|TocEmptyException|ContentEmptyException|Skipped|TooLarge)\(""" +
            """|Messages\.translate\(call,|\.error = """,
    )
    private val cjk = Regex("[一-鿿]")

    @Test
    fun parsesTemplates() {
        assertEquals(listOf("书源「{0}」不存在"), literals("""BadRequestException("书源「${'$'}{s.name}」不存在")"""))
        assertEquals(listOf("共 {0} 本，{1}.x"), literals("""X("共 ${'$'}n 本，${'$'}a.x")"""))
        // 模板里嵌套的字符串（如 ?: "默认原因"）也单独算一条
        assertEquals(listOf("被拒绝：{0}", "未知原因"), literals("""X("被拒绝：${'$'}{m ?: "未知原因"}")"""))
        assertEquals(listOf("引号\"和\$符号"), literals("""X("引号\"和\${'$'}符号")"""))
    }

    @Test
    fun everyUserFacingMessageHasEnglish() {
        val src = File("src/main/kotlin")
        assertTrue(src.isDirectory, "需要在 server 模块目录下运行")
        val en: Map<String, String> = File("src/main/resources/i18n/en.json").reader(Charsets.UTF_8).use {
            Gson().fromJson(it, object : TypeToken<Map<String, String>>() {}.type)
        }
        val missing = linkedMapOf<String, String>()
        src.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.forEach { f ->
            val text = f.readText(Charsets.UTF_8)
            sinks.findAll(text).forEach { m ->
                val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                literals(text.substring(m.range.first, argsEnd(text, m.range.last + 1, untilNewline = !m.value.endsWith("("))))
                    .filter { cjk.containsMatchIn(it) && it !in en }
                    .forEach { missing.putIfAbsent(it, "${f.relativeTo(src).invariantSeparatorsPath}:$line") }
            }
        }
        if (missing.isNotEmpty()) {
            fail(
                "en.json 缺少 ${missing.size} 条服务端提示的英文翻译：\n" +
                    missing.entries.joinToString("\n") { (k, at) -> "  $at  ${Gson().toJson(k)}" },
            )
        }
    }

    @Test
    fun placeholdersMatch() {
        val en: Map<String, String> = File("src/main/resources/i18n/en.json").reader(Charsets.UTF_8).use {
            Gson().fromJson(it, object : TypeToken<Map<String, String>>() {}.type)
        }
        val ph = Regex("""\{\d+\}""")
        val bad = en.filter { (k, v) -> ph.findAll(k).map { it.value }.toSet() != ph.findAll(v).map { it.value }.toSet() }
        assertTrue(bad.isEmpty(), "英文译文的 {0} 占位符与原文不一致：$bad")
    }

    @Test
    fun translatesNestedReason() {
        // 外层模板 + 填入的原因本身也是可以翻译的提示
        assertEquals(
            "Download rejected: The daily download limit may have been reached, or sign-in is required",
            Messages.translate("下载被拒绝：可能已达今日下载上限或需要登录", Messages.Lang.EN),
        )
        assertEquals("Download failed: HTTP 503", Messages.translate("下载失败：HTTP 503", Messages.Lang.EN))
        // 没有翻译的（如书源网站返回的）原样返回
        assertEquals("书源网站自己的提示", Messages.translate("书源网站自己的提示", Messages.Lang.EN))
    }

    /** 从 [start]（参数开始处，或赋值语句的右边）到这一段结束：括号配平后的 ')'；赋值语句（[untilNewline]）到行尾 */
    private fun argsEnd(s: String, start: Int, untilNewline: Boolean): Int {
        var depth = 0
        var i = start
        while (i < s.length) {
            when (s[i]) {
                '"' -> { i = readLiteral(s, i, mutableListOf()); continue }
                '(' -> depth++
                ')' -> { if (depth == 0) return i; depth-- }
                '\n' -> if (untilNewline && depth == 0) return i
            }
            i++
        }
        return s.length
    }

    /** 一段代码里的所有普通字符串字面量（不含三引号字符串），按出现顺序 */
    private fun literals(code: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < code.length) {
            if (code.startsWith("\"\"\"", i)) {
                i = code.indexOf("\"\"\"", i + 3).let { if (it < 0) code.length else it + 3 }
            } else if (code[i] == '"') {
                i = readLiteral(code, i, out)
            } else i++
        }
        return out
    }

    /**
     * 读取从 [start]（开头的引号）开始的字符串字面量，规范化后加入 [out]（模板里嵌套的字面量也加入），返回结束位置之后。
     */
    private fun readLiteral(s: String, start: Int, out: MutableList<String>): Int {
        val sb = StringBuilder()
        val slot = out.size
        out.add("")
        var n = 0
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> {
                    sb.append(
                        when (val e = s[i + 1]) {
                            'n' -> '\n'
                            't' -> '\t'
                            else -> e
                        },
                    )
                    i += 2
                }
                c == '$' && i + 1 < s.length && s[i + 1] == '{' -> {
                    // ${ 表达式 }：跳过（里面的字符串字面量另算），直到配平的 }
                    var depth = 1
                    i += 2
                    while (i < s.length && depth > 0) {
                        when (s[i]) {
                            '"' -> { i = readLiteral(s, i, out); continue }
                            '{' -> depth++
                            '}' -> depth--
                        }
                        i++
                    }
                    sb.append('{').append(n++).append('}')
                }
                c == '$' && i + 1 < s.length && (s[i + 1].isLetter() || s[i + 1] == '_') -> {
                    i++
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                    sb.append('{').append(n++).append('}')
                }
                c == '"' -> { out[slot] = sb.toString(); return i + 1 }
                else -> { sb.append(c); i++ }
            }
        }
        out[slot] = sb.toString()
        return s.length
    }
}
