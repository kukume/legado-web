package io.legado.web.zlib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.security.MessageDigest

class ZLibraryAlgoTest {
    @Test fun jhashMatchesPython() {
        assertEquals(928, ZLibraryClient.solveJhash(12345))
        assertEquals(489, ZLibraryClient.solveJhash(0))
        assertEquals(829, ZLibraryClient.solveJhash(999999))
    }

    @Test fun parsesProxyForms() {
        // 标准形式：带/不带用户名密码，http/socks5 都能构造成功（不会抛异常）
        ZLibraryClient(ZLibraryClient.DEFAULT_HOST, "socks5://user:pass@127.0.0.1:1080")
        ZLibraryClient(ZLibraryClient.DEFAULT_HOST, "socks5://127.0.0.1:1080")
        ZLibraryClient(ZLibraryClient.DEFAULT_HOST, "http://1.2.3.4:8080")
        ZLibraryClient(ZLibraryClient.DEFAULT_HOST, "http://u:p@1.2.3.4:8080")
        ZLibraryClient(ZLibraryClient.DEFAULT_HOST, "")
        for (bad in listOf("127.0.0.1:1080", "socks5://127.0.0.1", "ftp://1.2.3.4:21", "socks5://1.2.3.4:abc")) {
            assertFailsWith<IllegalArgumentException>(bad) { ZLibraryClient(ZLibraryClient.DEFAULT_HOST, bad) }
        }
    }

    @Test fun errorMessageHidesServerIp() {
        val page = """<html><body><article class="error-page">
            There are more than 5 downloads from your IP 4.193.146.94 during last 24 hours.
            Please sign in to your account or complete a simple registration to download more books
            </article></body></html>"""
        val msg = ZLibraryClient.sanitizeMessageForTest(page)!!
        assert(!msg.contains("4.193.146.94")) { "不应包含服务器 IP: $msg" }
        assert(msg.contains("今日下载上限")) { "应为简洁提示: $msg" }

        // 其它带 IP 的提示也要去掉 IP
        val other = ZLibraryClient.sanitizeMessageForTest("""<h1>Blocked from 10.0.0.5 and ::ffff:8.8.8.8</h1>""")!!
        assert(!other.contains("10.0.0.5") && !other.contains("8.8.8.8")) { "IP 未去除: $other" }
    }

    @Test fun parsesDownloadQuota() {
        val html = """<div class="d-count">  3/10 </div> ... <div class="d-reset">
            Downloads will be reset in 5 hours 30 minutes </div>"""
        val (used, limit, reset) = ZLibraryClient.parseQuotaForTest(html)
        assertEquals(3, used); assertEquals(10, limit); assertEquals("5 hours 30 minutes", reset)
        // 没有重置信息时只拿到计数
        val (u2, l2, r2) = ZLibraryClient.parseQuotaForTest("""<div class="d-count">0/999</div>""")
        assertEquals(0, u2); assertEquals(999, l2); assertEquals("", r2)
    }

    @Test fun powMatchesPython() {
        val seed = "a1b2c3d4e5f6a7b8c9d0"
        val html = "x=a0_0x2a54=['foo','$seed','bar'];"
        val token = ZLibraryClient.solvePow(html)
        assertEquals("${seed}102551", token)
        // 验证 token 确实满足条件
        val check = seed[0].digitToInt(16)
        val d = MessageDigest.getInstance("SHA-1").digest(token!!.toByteArray())
        assertEquals(0xB0, d[check].toInt() and 0xff)
        assertEquals(0x0B, d[check + 1].toInt() and 0xff)
    }
}
