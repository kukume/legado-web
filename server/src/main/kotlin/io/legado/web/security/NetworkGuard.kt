package io.legado.web.security

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * 书源请求的内网防护：阻止服务器替书源（以及用户提交的书籍地址等）访问本机、内网、链路本地等地址，
 * 避免服务被用来探测或访问服务器所在的内网（SSRF）。
 *
 * 在建立 TCP 连接时检查实际连接的 IP，所以域名解析到内网、IP 直连、重定向到内网、
 * 书源固定的 IP（urlOption 的 dns）、HTTP 代理地址都能拦住；SOCKS 代理由 [checkHost] 单独检查。
 *
 * 放在 io.legado.web 包下：书源脚本的 ClassShutter 屏蔽了这个包，脚本无法修改设置。
 */
object NetworkGuard {

    /** 被阻止的连接 */
    class BlockedException(host: String) :
        IOException("已阻止访问内网地址 $host，可以请管理员在“系统设置”中允许")

    /**
     * 服务启动时调用 [configure] 后始终开启，不能关闭（只能用允许列表放行指定主机）。
     * 没有调用时（引擎的单元测试连本机的模拟站点）不检查
     */
    @Volatile
    private var enabled = false

    @Volatile
    private var allowlistSupplier: () -> String = { "" }

    /** 当前是否开启 */
    val isEnabled: Boolean get() = enabled

    /** 由 Application 在启动时调用：开启防护，allowlist 读取系统设置里允许访问的内网地址 */
    fun configure(allowlist: () -> String) {
        allowlistSupplier = allowlist
        enabled = true
    }

    /** 只给测试用：恢复为未开启 */
    internal fun resetForTests() {
        enabled = false
        allowlistSupplier = { "" }
    }

    private class Rule(val host: String?, val suffix: String?, val net: ByteArray?, val prefix: Int)

    @Volatile
    private var parsedFrom: String? = null

    @Volatile
    private var rules: List<Rule> = emptyList()

    /** 允许列表：主机名（nas.lan）、通配（*.lan）、IP（192.168.1.10）、网段（192.168.1.0/24、fd00::/8），逗号或换行分隔 */
    private fun currentRules(): List<Rule> {
        val text = allowlistSupplier()
        if (text == parsedFrom) return rules
        val list = text.split(',', '\n', '\r', ' ', '\t').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.mapNotNull { e ->
            when {
                e.startsWith("*.") -> Rule(null, e.substring(1), null, 0)
                e.contains('/') -> {
                    val ip = parseIp(e.substringBefore('/')) ?: return@mapNotNull null
                    val bits = e.substringAfter('/').toIntOrNull() ?: return@mapNotNull null
                    Rule(null, null, ip.address, bits.coerceIn(0, ip.address.size * 8))
                }
                else -> parseIp(e)?.let { Rule(null, null, it.address, it.address.size * 8) } ?: Rule(e.trimEnd('.'), null, null, 0)
            }
        }
        rules = list
        parsedFrom = text
        return list
    }

    /** 只接受 IP 字面量，不做域名解析 */
    private fun parseIp(s: String): InetAddress? {
        val v = s.removePrefix("[").removeSuffix("]")
        val looksLikeIp = v.contains(':') || v.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
        return if (looksLikeIp) runCatching { InetAddress.getByName(v) }.getOrNull() else null
    }

    private fun inNet(addr: InetAddress, net: ByteArray, prefix: Int): Boolean {
        val a = addr.address
        if (a.size != net.size) return false
        var bits = prefix
        for (i in a.indices) {
            if (bits <= 0) return true
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((a[i].toInt() and mask) != (net[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }

    /** 本机、内网、链路本地、运营商级 NAT、组播等不应由书源访问的地址 */
    fun isPrivate(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isMulticastAddress
        ) return true
        val b = addr.address.map { it.toInt() and 0xFF }
        return when (addr) {
            is Inet4Address -> b[0] == 0 || // 0.0.0.0/8
                (b[0] == 100 && b[1] in 64..127) || // 100.64.0.0/10 运营商级 NAT
                (b[0] == 198 && b[1] in 18..19) || // 198.18.0.0/15
                (b[0] == 192 && b[1] == 0 && b[2] == 0) || // 192.0.0.0/24
                b[0] >= 240 // 保留地址、广播
            is Inet6Address -> (b[0] and 0xFE) == 0xFC || // fc00::/7 唯一本地地址
                // IPv4 映射 / 兼容地址（::ffff:10.0.0.1）按其中的 IPv4 判断
                (b.subList(0, 10).all { it == 0 } && ((b[10] == 0xFF && b[11] == 0xFF) || (b[10] == 0 && b[11] == 0)) &&
                    b.subList(12, 16).any { it != 0 } &&
                    isPrivate(InetAddress.getByAddress(addr.address.copyOfRange(12, 16))))
            else -> false
        }
    }

    private fun allowed(host: String?, addr: InetAddress): Boolean = currentRules().any { r ->
        when {
            r.host != null -> host != null && host.trimEnd('.').equals(r.host, true)
            r.suffix != null -> host != null && host.trimEnd('.').lowercase().let { it.endsWith(r.suffix) || it == r.suffix.substring(1) }
            r.net != null -> inNet(addr, r.net, r.prefix)
            else -> false
        }
    }

    /** 连接前检查；host 为请求里的主机名（可能为空） */
    fun check(host: String?, addr: InetAddress) {
        if (!enabled || !isPrivate(addr) || allowed(host, addr)) return
        throw BlockedException(host?.takeIf { it.isNotBlank() && it != addr.hostAddress }?.let { "$it (${addr.hostAddress})" } ?: addr.hostAddress)
    }

    /** 按主机名检查（会解析域名）：SOCKS 代理地址、无头浏览器的请求 */
    fun checkHost(host: String) {
        if (!enabled) return
        val h = host.removePrefix("[").removeSuffix("]")
        val addrs = try {
            InetAddress.getAllByName(h)
        } catch (_: Exception) {
            return // 解析失败时交给后面的连接报错
        }
        addrs.forEach { check(h, it) }
    }

    fun isBlockedHost(host: String): Boolean = runCatching { checkHost(host) }.isFailure

    /** 原始的主机名：InetAddress 由域名解析得到时保留着域名（toString 为 “域名/IP”），不触发反向解析 */
    private fun hostOf(addr: InetAddress): String? = addr.toString().substringBefore('/').takeIf { it.isNotEmpty() }

    private class GuardedSocket : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            (endpoint as? InetSocketAddress)?.let { ep ->
                val addr = ep.address
                if (addr != null) check(hostOf(addr) ?: ep.hostString, addr)
                else checkHost(ep.hostString)
            }
            super.connect(endpoint, timeout)
        }
    }

    /** 给书源用的 OkHttp 客户端：建立连接前检查目标地址 */
    val socketFactory: SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = GuardedSocket()

        override fun createSocket(host: String, port: Int): Socket =
            GuardedSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            GuardedSocket().apply {
                bind(InetSocketAddress(localHost, localPort))
                connect(InetSocketAddress(host, port))
            }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            GuardedSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            GuardedSocket().apply {
                bind(InetSocketAddress(localAddress, localPort))
                connect(InetSocketAddress(address, port))
            }
    }
}
