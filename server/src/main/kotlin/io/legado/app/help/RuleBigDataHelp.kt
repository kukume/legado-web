package io.legado.app.help

import io.legado.app.utils.MD5Utils

/**
 * 超过 10000 字符的书籍/章节变量，服务端统一存到缓存表
 */
object RuleBigDataHelp {

    private fun bookKey(bookUrl: String, key: String) =
        "bookVar_${MD5Utils.md5Encode(bookUrl)}_${MD5Utils.md5Encode(key)}"

    private fun chapterKey(bookUrl: String, chapterUrl: String, key: String) =
        "chapterVar_${MD5Utils.md5Encode(bookUrl)}_${MD5Utils.md5Encode(chapterUrl)}_${MD5Utils.md5Encode(key)}"

    fun putBookVariable(bookUrl: String, key: String, value: String?) {
        if (value == null) CacheManager.delete(bookKey(bookUrl, key))
        else CacheManager.put(bookKey(bookUrl, key), value)
    }

    fun getBookVariable(bookUrl: String, key: String?): String? {
        key ?: return null
        return CacheManager.get(bookKey(bookUrl, key))
    }

    fun putChapterVariable(bookUrl: String, chapterUrl: String, key: String, value: String?) {
        if (value == null) CacheManager.delete(chapterKey(bookUrl, chapterUrl, key))
        else CacheManager.put(chapterKey(bookUrl, chapterUrl, key), value)
    }

    fun getChapterVariable(bookUrl: String, chapterUrl: String, key: String): String? {
        return CacheManager.get(chapterKey(bookUrl, chapterUrl, key))
    }
}
