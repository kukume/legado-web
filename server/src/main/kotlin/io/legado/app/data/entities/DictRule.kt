package io.legado.app.data.entities

import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import kotlinx.coroutines.currentCoroutineContext

/**
 * 字典规则，移植自 App（字段与 JSON 格式相同）
 */
data class DictRule(
    var name: String = "",
    var urlRule: String = "",
    var showRule: String = "",
    var enabled: Boolean = true,
    var sortNumber: Int = 0
) {

    override fun hashCode(): Int {
        return name.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other is DictRule) {
            return name == other.name
        }
        return false
    }

    /**
     * 搜索字典
     */
    suspend fun search(word: String): String = searchWithUrl(word).first

    /**
     * 搜索字典，同时返回请求的网页地址（服务端新增：网页版显示结果时用它解析图片等相对地址）
     */
    suspend fun searchWithUrl(word: String): Pair<String, String> {
        val analyzeUrl = AnalyzeUrl(urlRule, key = word, coroutineContext = currentCoroutineContext())
        val res = analyzeUrl.getStrResponseAwait()
        val body = res.body
        // data: 地址（如百度汉语）没有对应的网页
        val pageUrl = if (urlRule.trimStart().startsWith("data:")) "" else res.url
        if (showRule.isBlank()) {
            return body!! to pageUrl
        }
        val analyzeRule = AnalyzeRule().setCoroutineContext(currentCoroutineContext())
        analyzeRule.setRuleName(name)
        return analyzeRule.getString(showRule, mContent = body) to pageUrl
    }

    suspend fun buttonClick(name: String, click: String) {
        val analyzeRule = AnalyzeRule().setCoroutineContext(currentCoroutineContext())
        analyzeRule.setRuleName(this.name)
        analyzeRule.evalJS(click, name)
    }

}
