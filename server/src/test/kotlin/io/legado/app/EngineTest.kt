package io.legado.app

import com.sun.net.httpserver.HttpServer
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.engine.EngineContext
import io.legado.app.help.engine.Engine
import io.legado.app.help.engine.MemoryEngineStore
import io.legado.app.help.http.CookieStore
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 用本地 HTTP 服务模拟一个小说站点，验证移植后的书源规则引擎
 */
class EngineTest {

    private lateinit var server: HttpServer
    private var port = 0
    private val base get() = "http://127.0.0.1:$port"

    @BeforeTest
    fun setUp() {
        Engine.store = MemoryEngineStore()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            val query = ex.requestURI.rawQuery ?: ""
            val (type, body) = when {
                path == "/search" -> {
                    val key = URLDecoder.decode(query.substringAfter("q=").substringBefore("&"), "UTF-8")
                    "text/html" to """
                        <html><body><ul class="list">
                          <li><a class="name" href="/book/1">$key 之书</a><span class="author">作者：张三</span>
                              <img data-src="/cover/1.jpg"><p class="intro">简介&amp;测试</p></li>
                          <li><a class="name" href="/book/2">另一本书</a><span class="author">李四</span></li>
                        </ul></body></html>
                    """.trimIndent()
                }
                path == "/api/search" -> "application/json" to """
                    {"data":{"list":[{"title":"JSON书","writer":"王五","id":7,"tags":["玄幻","完结"]}]}}
                """.trimIndent()
                path == "/book/1" -> "text/html" to """
                    <html><body>
                      <div id="info"><h1>测试之书</h1><p>作者：张三</p><p class="kind">玄幻</p></div>
                      <div id="intro">这是简介</div>
                      <a id="toc" href="/book/1/toc">目录</a>
                    </body></html>
                """.trimIndent()
                path == "/book/1/toc" -> "text/html" to """
                    <html><body><dl>
                      <dd><a href="/c/1">第一章 开始</a></dd>
                      <dd><a href="/c/2">第二章 继续</a></dd>
                      <dd><a href="/c/3">第三章 结束</a></dd>
                    </dl><a class="next" href="/book/1/toc2">下一页</a></body></html>
                """.trimIndent()
                path == "/book/1/toc2" -> "text/html" to """
                    <html><body><dl><dd><a href="/c/4">第四章 番外</a></dd></dl></body></html>
                """.trimIndent()
                path == "/c/1" -> "text/html" to """
                    <html><body><div id="content">第一段<br>第二段<script>bad()</script><br>广告内容</div>
                    <a id="next" href="/c/1_2">下一页</a></body></html>
                """.trimIndent()
                path == "/c/1_2" -> "text/html" to """
                    <html><body><div id="content">第三段</div><a id="next" href="/c/2">下一章</a></body></html>
                """.trimIndent()
                path.startsWith("/c/") -> "text/html" to """
                    <html><body><div id="content">章节 ${path.substringAfterLast('/')} 的内容</div></body></html>
                """.trimIndent()
                path == "/cookie" -> {
                    ex.responseHeaders.add("Set-Cookie", "sid=abc123; Path=/; Max-Age=3600")
                    "text/plain" to "ok"
                }
                path == "/echo-cookie" -> "text/plain" to (ex.requestHeaders.getFirst("Cookie") ?: "")
                else -> "text/plain" to "not found"
            }
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "$type; charset=utf-8")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        port = server.address.port
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun htmlSource(): BookSource = GSON.fromJsonObject<BookSource>(
        """
        {
          "bookSourceUrl": "$base",
          "bookSourceName": "测试源",
          "searchUrl": "/search?q={{key}}&page={{page}}",
          "header": "{\"Referer\": \"$base\"}",
          "ruleSearch": {
            "bookList": "class.list@tag.li",
            "name": "class.name@text",
            "author": "class.author@text",
            "bookUrl": "class.name@href",
            "coverUrl": "tag.img@data-src",
            "intro": ".intro@text"
          },
          "ruleBookInfo": {
            "name": "//div[@id='info']/h1/text()",
            "author": "#info p.0@text##作者：",
            "kind": "@css:#info .kind@text",
            "intro": "id.intro@text",
            "tocUrl": "id.toc@href"
          },
          "ruleToc": {
            "chapterList": "tag.dd@tag.a",
            "chapterName": "text",
            "chapterUrl": "href",
            "nextTocUrl": "class.next@href",
            "formatJs": "title.replace('第', '[') "
          },
          "ruleContent": {
            "content": "id.content@html##广告内容",
            "nextContentUrl": "id.next@href"
          }
        }
        """.trimIndent()
    ).getOrThrow()

    private fun <T> runAsUser(uid: Long, block: suspend () -> T): T = runBlocking {
        withContext(Dispatchers.IO + EngineContext(uid)) { block() }
    }

    @Test
    fun searchInfoTocContent() = runAsUser(1) {
        val source = htmlSource()
        val books = WebBook.searchBookAwait(source, "测试", 1)
        assertEquals(2, books.size)
        val first = books.first()
        assertEquals("测试 之书", first.name)
        assertEquals("张三", first.author) // BookHelp.formatBookAuthor 去掉“作者：”
        assertEquals("$base/book/1", first.bookUrl)
        assertEquals("$base/cover/1.jpg", first.coverUrl)
        assertTrue(first.intro!!.contains("简介&测试"))

        val book = first.toBook()
        WebBook.getBookInfoAwait(source, book)
        assertEquals("测试 之书", book.name) // canReName 为空，保留搜索结果名称
        assertEquals("玄幻", book.kind)
        assertEquals("$base/book/1/toc", book.tocUrl)

        val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
        assertEquals(4, chapters.size)
        assertEquals("[一章 开始", chapters[0].title) // formatJs
        assertEquals("$base/c/4", chapters[3].getAbsoluteURL())

        val content = WebBook.getContentAwait(source, book, chapters[0], chapters[1].url, needSave = false)
        assertTrue(content.contains("第一段"), content)
        assertTrue(content.contains("第二段"), content)
        assertTrue(content.contains("第三段"), content) // 正文分页
        assertTrue(!content.contains("广告内容"), content) // ##替换
        assertTrue(!content.contains("bad()"), content) // html 去掉 script
    }

    @Test
    fun jsonAndJsRules() = runAsUser(1) {
        val source = GSON.fromJsonObject<BookSource>(
            """
            {
              "bookSourceUrl": "$base/json",
              "bookSourceName": "JSON源",
              "searchUrl": "@js: '$base/api/search?q=' + encodeURIComponent(key)",
              "ruleSearch": {
                "bookList": "$.data.list[*]",
                "name": "$.title",
                "author": "{{$.writer}}先生",
                "kind": "$.tags[*]",
                "bookUrl": "<js>'$base/book/' + java.getString('$.id')</js>"
              }
            }
            """.trimIndent()
        ).getOrThrow()
        val books = WebBook.searchBookAwait(source, "x", 1)
        assertEquals(1, books.size)
        assertEquals("JSON书", books[0].name)
        assertEquals("王五先生", books[0].author)
        assertEquals("玄幻,完结", books[0].kind)
        assertEquals("$base/book/7", books[0].bookUrl)
    }

    @Test
    fun jsExtensionsAndVariablesAreIsolatedPerUser() {
        val source = htmlSource()
        runAsUser(1) {
            source.put("token", "user1-token")
            val r = source.evalJS(
                """
                var md5 = java.md5Encode('abc');
                var b64 = java.base64Encode('hello');
                var dec = java.base64Decode(b64);
                var aes = java.createSymmetricCrypto('AES/ECB/PKCS5Padding', '1234567890123456').encryptBase64('hi');
                var back = java.createSymmetricCrypto('AES/ECB/PKCS5Padding', '1234567890123456').decryptStr(aes);
                var t2s = java.t2s('漢字');
                md5 + '|' + dec + '|' + back + '|' + t2s + '|' + source.get('token')
                """.trimIndent()
            ).toString()
            assertEquals("900150983cd24fb0d6963f7d28e17f72|hello|hi|汉字|user1-token", r)
        }
        runAsUser(2) {
            // 其他用户看不到 user1 的变量
            assertEquals("", source.get("token"))
        }
    }

    @Test
    fun cookieJarPerUser() {
        val source = htmlSource().apply { enabledCookieJar = true }
        runAsUser(1) {
            source.evalJS("java.ajax('$base/cookie')")
            val echo = source.evalJS("java.ajax('$base/echo-cookie')").toString()
            assertTrue(echo.contains("sid=abc123"), echo)
            assertTrue(CookieStore.getCookie(base).contains("sid=abc123"))
        }
        runAsUser(2) {
            assertEquals("", CookieStore.getCookie(base))
        }
    }

    @Test
    fun scriptCannotAccessProtectedClasses() {
        val source = htmlSource()
        runAsUser(1) {
            val r = runCatching {
                source.evalJS("java.lang.Runtime.getRuntime().exec('whoami')")
            }
            assertTrue(r.isFailure, "Runtime 应该被屏蔽")
            val r2 = runCatching {
                source.evalJS("Packages.io.legado.web.db.DatabaseFactory.INSTANCE")
            }
            assertTrue(r2.isFailure || r2.getOrNull() == null || r2.getOrNull().toString().startsWith("[JavaPackage"),
                "服务端内部类应该被屏蔽: ${r2.getOrNull()}")

            // 之前探测到可绕过的几类，逐一确认已被屏蔽（new 必须报错，不能静默返回对象）
            fun blocked(js: String, name: String) {
                val res = runCatching { source.evalJS(js) }
                assertTrue(res.isFailure, "$name 应该被屏蔽，但执行成功了：${res.getOrNull()}")
            }
            // XMLDecoder / Expression：能实例化任意类、调用任意方法，等于完全绕过黑名单
            blocked("""new Packages.java.beans.Expression(new Packages.java.lang.StringBuilder("ab"),"reverse",[]).getValue()""", "java.beans.Expression")
            blocked("""new Packages.java.beans.XMLDecoder(new Packages.java.io.ByteArrayInputStream(new Packages.java.lang.String("<java/>").getBytes()))""", "java.beans.XMLDecoder")
            // 直连网络会绕过内网防护
            blocked("""new Packages.java.net.Socket("127.0.0.1", 1)""", "java.net.Socket")
            blocked("""new Packages.java.net.URL("http://127.0.0.1/").openConnection()""", "java.net.URL")
            // 通过 URI.toURL() 也拿不到可用的 URL 实例
            blocked("""new Packages.java.net.URI("http://127.0.0.1/").toURL().openConnection()""", "URI.toURL()")
            // 起线程可绕过超时 / 取消
            blocked("""var t=new Packages.java.lang.Thread(function(){}); t.start()""", "java.lang.Thread")
            // System.getProperty 信息泄露
            blocked("""Packages.java.lang.System.getProperty("user.dir")""", "System.getProperty")
        }
    }

    @Test
    fun loginInfoWithChangedSecretIsClearedQuietly() {
        val source = htmlSource()
        val old = Engine.secret
        try {
            runAsUser(1) {
                Engine.secret = "secret-a"
                assertTrue(source.putLoginInfo("""{"user":"bob"}"""))
                assertEquals("bob", source.getLoginInfoMap()["user"])
                // 换密钥后旧数据无法解密：返回空并清除记录，而不是抛异常
                Engine.secret = "secret-b"
                assertEquals(null, source.getLoginInfo())
                assertEquals(null, io.legado.app.help.CacheManager.get("userInfo_${source.getKey()}"))
                // 重新保存后可正常读取
                assertTrue(source.putLoginInfo("""{"user":"alice"}"""))
                assertEquals("alice", source.getLoginInfoMap()["user"])
            }
        } finally {
            Engine.secret = old
        }
    }

    @Test
    fun pureJsSource() = runAsUser(1) {
        // LegadoTeam 新增的纯 JS 单文件书源，同时验证 ES6+ 语法（箭头函数、模板字符串、解构）
        val script = """
            const config = {
              bookSourceUrl: "$base/jssource",
              bookSourceName: "JS源测试",
            };
            function search(key, page) {
              const html = java.ajax(`$base/search?q=${'$'}{encodeURIComponent(key)}`);
              const names = [...html.matchAll(/class="name" href="([^"]+)">([^<]+)</g)];
              return names.map(m => ({ name: m[2], author: "佚名", bookUrl: `$base${'$'}{m[1]}` }));
            }
            function getChapters(book) {
              return [1, 2, 3].map(i => ({ title: `第${'$'}{i}章`, url: `$base/c/${'$'}{i}` }));
            }
            function getContent(chapter) {
              const html = String(java.ajax(chapter.url));
              return html.replace(/<[^>]+>/g, "").trim();
            }
        """.trimIndent()
        val source = io.legado.app.model.jsSource.JsSourceConfig.extract(script, kotlin.coroutines.coroutineContext)
        assertTrue(source.isJsSource())
        assertEquals("JS源测试", source.bookSourceName)
        val books = WebBook.searchBookAwait(source, "测试", 1)
        assertEquals(2, books.size)
        assertEquals("测试 之书", books[0].name)
        val book = books[0].toBook()
        val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
        assertEquals(3, chapters.size)
        val content = WebBook.getContentAwait(source, book, chapters[1], needSave = false)
        assertTrue(content.contains("章节 2 的内容"), content)
    }

    @Test
    fun analyzeRuleVariants() = runAsUser(1) {
        val source = htmlSource()
        val book = Book(bookUrl = "$base/book/1")
        val rule = io.legado.app.model.analyzeRule.AnalyzeRule(book, source)
        rule.setContent("<div><p class='a'>1</p><p class='a'>2</p><p class='a'>3</p><span>x</span></div>")
        assertEquals("1\n2\n3", rule.getString("class.a@text"))
        assertEquals(listOf("3", "2", "1"), rule.getStringList("class.a[-1:0]@text"))
        assertEquals(listOf("1", "3"), rule.getStringList("class.a!1@text"))
        assertEquals("1", rule.getString("//p[1]/text()"))
        assertEquals("x", rule.getString("tag.span@text||class.a@text"))
        assertEquals("1\nx", rule.getString("class.a.0@text&&tag.span@text"))
        assertEquals("3", rule.getString("class.a.-1@text"))
        // put/get
        rule.getString("@put:{bid:\"class.a.1@text\"}tag.span@text")
        assertEquals("2", rule.getString("@get:{bid}"))
        // 正则 AllInOne
        rule.setContent("id=11,name=aa;id=22,name=bb;")
        val els = rule.getElements(":id=(\\d+),name=(\\w+)")
        assertEquals(2, els.size)
    }
}
