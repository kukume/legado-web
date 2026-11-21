package io.legado.web

import io.legado.web.service.resolveImportUrl
import kotlin.test.Test
import kotlin.test.assertEquals

/** 阅读 App 的一键导入链接 yuedu://booksource/importonline?src=... */
class ImportUrlTest {
    private val target = "https://example.com/source/a.json?id=1&t=2"

    @Test
    fun plainUrlUnchanged() {
        assertEquals(target, resolveImportUrl("  $target "))
    }

    @Test
    fun unencodedSrcKeepsItsOwnQuery() {
        assertEquals(target, resolveImportUrl("yuedu://booksource/importonline?src=$target"))
    }

    @Test
    fun encodedOnceOrTwice() {
        val once = java.net.URLEncoder.encode(target, Charsets.UTF_8)
        val twice = java.net.URLEncoder.encode(once, Charsets.UTF_8)
        assertEquals(target, resolveImportUrl("yuedu://booksource/importonline?src=$once"))
        assertEquals(target, resolveImportUrl("yuedu://booksource/importonline?src=$twice"))
    }

    @Test
    fun otherSchemesAndParams() {
        val once = java.net.URLEncoder.encode(target, Charsets.UTF_8)
        assertEquals(target, resolveImportUrl("legado://import/bookSource?src=$once"))
        assertEquals(target, resolveImportUrl("YUEDU://booksource/importonline?type=x&SRC=$once"))
    }

    @Test
    fun keepsPercentEscapesInsideUnencodedUrl() {
        val url = "https://example.com/%E4%B9%A6%E6%BA%90.json"
        assertEquals(url, resolveImportUrl("yuedu://booksource/importonline?src=$url"))
    }
}
