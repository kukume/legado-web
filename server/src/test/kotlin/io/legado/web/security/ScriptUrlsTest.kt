package io.legado.web.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptUrlsTest {

    @Test
    fun detectsScriptUrls() {
        assertTrue(ScriptUrls.hasScript("http://x/book/{{java.ajax('y')}}"))
        assertTrue(ScriptUrls.hasScript("@js:'http://x/'+1"))
        assertTrue(ScriptUrls.hasScript("http://x/a<js>java.log('')</js>"))
        assertTrue(ScriptUrls.hasScript("""http://x/s,{"method":"POST","js":"java.ajax('z')"}"""))
        assertTrue(ScriptUrls.hasScript("""http://x/s,{"bodyJs":"x"}"""))
        assertTrue(ScriptUrls.hasScript("""http://x/img,{"click":"java.startBrowser('y')"}"""))
    }

    @Test
    fun plainUrlsAreNotScript() {
        assertFalse(ScriptUrls.hasScript("http://x/book/123"))
        assertFalse(ScriptUrls.hasScript("https://x/a?q=1&p=2"))
        assertFalse(ScriptUrls.hasScript("""http://x/s,{"method":"POST","charset":"gbk"}"""))
        assertFalse(ScriptUrls.hasScript(null))
        assertFalse(ScriptUrls.hasScript(""))
    }

    @Test
    fun remembersPerUser() {
        val js = "http://x/book/{{java.ajax('y')}}"
        assertFalse(ScriptUrls.isRemembered(900001, js))
        ScriptUrls.remember(900001, js)
        assertTrue(ScriptUrls.isRemembered(900001, js))
        // 另一个用户看不到
        assertFalse(ScriptUrls.isRemembered(900002, js))
        // 只记带脚本的
        ScriptUrls.remember(900001, "http://x/plain")
        assertFalse(ScriptUrls.isRemembered(900001, "http://x/plain"))
    }

    @Test
    fun remembersContentImages() {
        ScriptUrls.rememberContentImages(900003, """<p><img src="http://x/i,{&quot;click&quot;:&quot;java.log('')&quot;}"></p>""")
        // 前端拿到的是浏览器解码后的地址
        assertTrue(ScriptUrls.isRemembered(900003, """http://x/i,{"click":"java.log('')"}"""))
    }
}
