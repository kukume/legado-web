package io.legado.web.i18n

import com.github.liuyueyi.quick.transfer.ChineseUtils
import io.ktor.server.application.ApplicationCall
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.slf4j.LoggerFactory

/**
 * 返回给用户的提示文字的翻译（错误提示、导入警告等）。
 *
 * - 代码里直接写简体中文；按请求头 Accept-Language 翻译：zh-TW / zh-HK / zh-Hant 转为台湾正体（含常用词），
 *   en 按 resources/i18n/en.json 翻译，其它语言和没有这个请求头的（开放接口、插件等）保持简体中文
 * - en.json 的 key 是原文模板，代码里的 ${...} 写作 {0} {1}…，按位置对应
 * - 书源脚本产生的错误（来自书源网站或书源作者）不在表里，原样返回
 */
object Messages {

    enum class Lang { ZH_CN, ZH_TW, EN }

    private val log = LoggerFactory.getLogger(Messages::class.java)

    private class Template(val regex: Regex, val target: String)

    /** 没有占位符的条目：直接查表 */
    private val exact = HashMap<String, String>()

    /** 有占位符的条目：按正则匹配后把捕获的内容填进译文 */
    private val templates = ArrayList<Template>()

    init {
        val json = Messages::class.java.getResourceAsStream("/i18n/en.json")?.use { it.readBytes().toString(Charsets.UTF_8) }
        val map = json?.let { GSON.fromJsonObject<Map<String, String>>(it).getOrNull() }.orEmpty()
        for ((src, target) in map) {
            if (!src.contains(Regex("""\{\d+\}"""))) {
                exact[src] = target
                continue
            }
            // “书源「{0}」不存在” → ^\Q书源「\E(.+?)\Q」不存在\E$
            val pattern = buildString {
                append('^')
                var last = 0
                Regex("""\{(\d+)\}""").findAll(src).forEach { m ->
                    append(Regex.escape(src.substring(last, m.range.first)))
                    append("(?<g").append(m.groupValues[1]).append(">[\\s\\S]*?)")
                    last = m.range.last + 1
                }
                append(Regex.escape(src.substring(last)))
                append('$')
            }
            runCatching { templates.add(Template(Regex(pattern), target)) }
                .onFailure { log.warn("翻译模板无效：{}", src) }
        }
        // 先匹配更长、更具体的模板
        templates.sortByDescending { it.regex.pattern.length }
    }

    /** 从请求头判断语言（取第一个） */
    fun langOf(call: ApplicationCall): Lang = parse(call.request.headers["Accept-Language"])

    fun parse(header: String?): Lang {
        val first = header?.split(',')?.firstOrNull()?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            first.startsWith("zh-tw") || first.startsWith("zh-hk") || first.startsWith("zh-mo") || first.startsWith("zh-hant") -> Lang.ZH_TW
            first.startsWith("en") -> Lang.EN
            else -> Lang.ZH_CN
        }
    }

    fun translate(message: String, lang: Lang): String = when (lang) {
        Lang.ZH_CN -> message
        Lang.ZH_TW -> runCatching { ChineseUtils.s2tw(message) }.getOrDefault(message)
        Lang.EN -> toEnglish(message)
    }

    fun translate(call: ApplicationCall, message: String): String = translate(message, langOf(call))

    private fun toEnglish(message: String, depth: Int = 0): String {
        exact[message]?.let { return it }
        for (t in templates) {
            val m = t.regex.matchEntire(message) ?: continue
            return Regex("""\{(\d+)\}""").replace(t.target) { r ->
                // 填入的内容本身也可能是可以翻译的提示（如“网络错误: …”里的原因），最多再往里翻译一层
                m.groups["g${r.groupValues[1]}"]?.value?.let { if (depth < 1) toEnglish(it, depth + 1) else exact[it] ?: it } ?: r.value
            }
        }
        return message
    }
}
