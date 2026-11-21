package io.legado.web

import io.legado.app.help.http.text
import io.legado.app.utils.EncodingDetect
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 网页 / JSON 的编码判断：猜出来的编码名无效时不能抛异常 */
class EncodingDetectTest {

    // 书源合集 JSON：规则字符串里有转义过的 HTML，jsoup 会把 <meta charset=\"utf-8\"> 解析成编码名 \"utf-8\"
    private val sourceJson = """[{"bookSourceName":"测试书源","ruleContent":{"content":"<head><meta charset=\"utf-8\"></head>正文"}}]"""

    @Test
    fun cleansCharsetNames() {
        assertEquals("utf-8", EncodingDetect.validCharset("\\\"utf-8\\\""))
        assertEquals("gbk", EncodingDetect.validCharset(" 'gbk' "))
        assertEquals("UTF-8", EncodingDetect.validCharset("UTF-8; foo"))
        assertNull(EncodingDetect.validCharset("not-a-charset"))
        assertNull(EncodingDetect.validCharset(""))
    }

    @Test
    fun jsonWithoutCharsetIsUtf8() {
        val body = sourceJson.toByteArray(Charsets.UTF_8).toResponseBody("application/json".toMediaType())
        assertEquals(sourceJson, body.text())
    }

    @Test
    fun invalidMetaCharsetDoesNotThrow() {
        // 没有 Content-Type 时按内容猜：猜出的无效编码名不再抛 IllegalCharsetNameException
        val body = sourceJson.toByteArray(Charsets.UTF_8).toResponseBody(null)
        assertEquals(sourceJson, body.text())
    }

    @Test
    fun htmlMetaCharsetStillWorks() {
        val html = "<html><head><meta charset=\"gbk\"></head><body>中文内容</body></html>"
        val body = html.toByteArray(charset("GBK")).toResponseBody("text/html".toMediaType())
        assertEquals(html, body.text())
    }
}
