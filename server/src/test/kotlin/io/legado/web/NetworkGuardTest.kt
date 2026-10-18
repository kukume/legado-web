package io.legado.web

import io.legado.app.help.http.okHttpClient
import io.legado.web.security.NetworkGuard
import okhttp3.Request
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkGuardTest {

    private var allowlist = ""

    private fun enable(allow: String = "") {
        allowlist = allow
        NetworkGuard.configure { allowlist }
    }

    @AfterTest
    fun reset() {
        NetworkGuard.resetForTests()
    }

    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun classifiesAddresses() {
        for (a in listOf(
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.169.254", "0.0.0.0",
            "100.64.0.1", "198.18.0.1", "224.0.0.1", "255.255.255.255", "::1", "fe80::1", "fd00::1", "fc12::1",
            "::ffff:10.0.0.1", "::ffff:127.0.0.1",
        )) assertTrue(NetworkGuard.isPrivate(ip(a)), a)
        for (a in listOf("8.8.8.8", "1.1.1.1", "172.32.0.1", "100.128.0.1", "2001:4860:4860::8888", "::ffff:8.8.8.8")) {
            assertFalse(NetworkGuard.isPrivate(ip(a)), a)
        }
    }

    @Test
    fun allowlist() {
        enable("nas.lan\n*.home.arpa, 192.168.5.0/24\n10.0.0.8\nfd00::/8")
        NetworkGuard.check("nas.lan", ip("192.168.1.2"))
        NetworkGuard.check("book.home.arpa", ip("192.168.1.3"))
        NetworkGuard.check("home.arpa", ip("192.168.1.3"))
        NetworkGuard.check(null, ip("192.168.5.77"))
        NetworkGuard.check(null, ip("10.0.0.8"))
        NetworkGuard.check(null, ip("fd12::1"))
        assertFailsWith<NetworkGuard.BlockedException> { NetworkGuard.check("other.lan", ip("192.168.1.2")) }
        assertFailsWith<NetworkGuard.BlockedException> { NetworkGuard.check(null, ip("10.0.0.9")) }
        assertFailsWith<NetworkGuard.BlockedException> { NetworkGuard.check("evilhome.arpa", ip("192.168.1.3")) }
        // 公网地址不受影响
        NetworkGuard.check("example.com", ip("93.184.216.34"))
    }

    @Test
    fun blocksEngineRequests() {
        // 本机起一个最简单的 HTTP 服务
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port = server.localPort
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                s.getInputStream().read(ByteArray(4096))
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                s.close()
            }
        }
        try {
            val req = { host: String -> Request.Builder().url("http://$host:$port/").build() }
            // 未开启时可以访问
            okHttpClient.newCall(req("127.0.0.1")).execute().use { assertEquals("ok", it.body.string()) }
            enable()
            val e = assertFailsWith<Exception> { okHttpClient.newCall(req("127.0.0.1")).execute().close() }
            assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it is NetworkGuard.BlockedException }, e.toString())
            assertFailsWith<Exception> { okHttpClient.newCall(req("localhost")).execute().close() }
            // 放行后可以访问
            enable("127.0.0.1")
            okHttpClient.newCall(req("127.0.0.1")).execute().use { assertEquals("ok", it.body.string()) }
            enable("localhost")
            okHttpClient.newCall(req("localhost")).execute().use { assertEquals("ok", it.body.string()) }
        } finally {
            server.close()
        }
    }

    @Test
    fun checksSocksProxyHost() {
        enable()
        assertFailsWith<NetworkGuard.BlockedException> { io.legado.app.help.http.getProxyClient("socks5://127.0.0.1:1080") }
        assertFailsWith<NetworkGuard.BlockedException> { io.legado.app.help.http.getProxyClient("http://192.168.1.1:8080") }
    }
}
