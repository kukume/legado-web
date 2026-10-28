package io.legado.web.security

import io.legado.app.constant.AppPattern
import java.util.concurrent.ConcurrentHashMap

/**
 * 带脚本的书源网址（{{js}}、<js></js>、@js:、网址参数里的 js / bodyJs，图片参数里的 click）：引擎请求时会执行其中的 js。
 *
 * 这些网址本应只来自书源规则（搜索、发现、详情、正文的结果）。前端传回的网址如果带脚本，必须是服务器之前
 * 交给这个用户的（记在这里），否则不能交给引擎，防止没有“添加书源”权限的用户借公共书源执行自己写的 js。
 * 只记带脚本的网址（很少），每个用户最多 [MAX_PER_USER] 条，超过时丢弃最早的。
 */
object ScriptUrls {

    private const val MAX_PER_USER = 20_000

    private val optionScript = Regex("""[{,]\s*['"]?(js|bodyJs|click)['"]?\s*:""", RegexOption.IGNORE_CASE)

    fun hasScript(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        if (url.contains("{{") && url.contains("}}")) return true
        if (AppPattern.JS_PATTERN.matcher(url).find()) return true
        val option = url.indexOf('{')
        return option >= 0 && optionScript.containsMatchIn(url.substring(option))
    }

    private val trusted = ConcurrentHashMap<Long, MutableSet<String>>()

    /** 记下服务器交给用户的网址（只记带脚本的） */
    fun remember(userId: Long, urls: Iterable<String?>) {
        val list = urls.filter { hasScript(it) }
        if (list.isEmpty()) return
        val set = trusted.computeIfAbsent(userId) {
            java.util.Collections.newSetFromMap(object : LinkedHashMap<String, Boolean>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > MAX_PER_USER
            })
        }
        synchronized(set) { list.forEach { set.add(it!!) } }
    }

    fun remember(userId: Long, vararg urls: String?) = remember(userId, urls.asIterable())

    fun isRemembered(userId: Long, url: String): Boolean {
        val set = trusted[userId] ?: return false
        return synchronized(set) { url in set }
    }

    private val imgSrc = Regex("""<img[^>]*?\ssrc\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)

    /** 正文里的图片地址（前端会拿它请求图片代理） */
    fun rememberContentImages(userId: Long, content: String?) {
        if (content.isNullOrEmpty() || !content.contains("<img", true)) return
        // 前端拿到的是浏览器解码后的地址：原文和解码后的都记下
        remember(userId, imgSrc.findAll(content).flatMap { m ->
            val raw = m.groupValues[2]
            sequenceOf(raw, raw.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&"))
        }.asIterable())
    }
}
