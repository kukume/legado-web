package io.legado.web.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SiteScopeTest {

    @Test
    fun registrableDomainUsesPublicSuffixList() {
        assertEquals("google.com", SiteScope.registrableDomain("https://www.google.com/search?q=1"))
        assertEquals("example.co.uk", SiteScope.registrableDomain("https://a.b.example.co.uk/"))
        // github.io 是公共后缀：每个用户的子域名各自算一个网站
        assertEquals("a.github.io", SiteScope.registrableDomain("https://a.github.io/x"))
        assertEquals("192.168.1.10", SiteScope.registrableDomain("http://192.168.1.10:8080/"))
        // 书源格式的网址（带请求参数）
        assertEquals("example.com", SiteScope.registrableDomain("https://m.example.com/s,{\"method\":\"POST\"}"))
    }

    @Test
    fun allowsDomainAndSubdomainsOnly() {
        val scope = SiteScope(listOf("https://www.google.com", null))
        assertTrue(scope.allows("https://google.com/"))
        assertTrue(scope.allows("https://accounts.google.com/login"))
        assertTrue(scope.allows("http://a.b.google.com"))
        assertFalse(scope.allows("https://www.gstatic.com/x.js"))
        assertFalse(scope.allows("https://evilgoogle.com/"))
        assertFalse(scope.allows("https://google.com.evil.net/"))
        assertTrue(scope.allows("about:blank"))
    }

    @Test
    fun publicSuffixSitesAreSeparate() {
        val scope = SiteScope(listOf("https://a.github.io/"))
        assertTrue(scope.allows("https://a.github.io/page"))
        assertFalse(scope.allows("https://b.github.io/"))
        assertFalse(scope.allows("https://github.io/"))
    }

    @Test
    fun combinesSourceAndPageSites() {
        val scope = SiteScope(listOf("https://api.book.com", "https://verify.other.net/check"))
        assertTrue(scope.allows("https://www.book.com/"))
        assertTrue(scope.allows("https://other.net/"))
        assertFalse(scope.allows("https://third.org/"))
    }

    @Test
    fun noUsableUrlMeansNoLimit() {
        assertTrue(SiteScope(listOf("not a url", null)).allows("https://anything.com/"))
    }
}
