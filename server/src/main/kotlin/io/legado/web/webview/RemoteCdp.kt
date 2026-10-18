package io.legado.web.webview

import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 外部浏览器：通过 Chrome DevTools Protocol 连接另一台机器 / 另一个容器里的浏览器（地址在“系统设置”中填写），
 * 本机不用安装浏览器。
 */
object RemoteCdp {

    private val ipRegex = Regex("""^(\d{1,3}(\.\d{1,3}){3}|\[[0-9a-fA-F:]+])$""")

    // 单独的客户端：不经过引擎的拦截器和内网防护（这是服务自己配置的地址，常在内网）
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    /**
     * Chrome 只接受 Host 为 IP 或 localhost 的调试请求：用主机名（如 Docker 的服务名 browser）连接时，Host 改为 localhost
     */
    private fun hostHeader(host: String): String? =
        if (host.equals("localhost", true) || ipRegex.matches(host)) null else "localhost"

    /** 浏览器信息（测试连接用）：Browser（名称和版本）、User-Agent 等。只支持 http(s) 地址 */
    fun version(endpoint: String): Map<String, String> {
        val e = endpoint.trim().trimEnd('/')
        if (e.startsWith("ws", true)) return mapOf("Browser" to "（ws 地址，无法读取版本）")
        val base = (if (e.startsWith("http", true)) e else "http://$e").toHttpUrl()
        val req = Request.Builder().url(base.newBuilder().addPathSegments("json/version").build())
            .apply { hostHeader(base.host)?.let { header("Host", it) } }
            .build()
        return client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("浏览器调试地址返回 ${res.code}")
            JsonParser.parseString(res.body.string()).asJsonObject.entrySet()
                .filter { it.value.isJsonPrimitive }.associate { it.key to it.value.asString }
        }
    }

    /**
     * 地址（http://host:9222 或 ws://host:9222/devtools/browser/…）→ 浏览器的 WebSocket 地址和需要使用的 Host 请求头
     */
    fun resolve(endpoint: String): Pair<String, String?> {
        val e = endpoint.trim().trimEnd('/')
        if (e.startsWith("ws://", true) || e.startsWith("wss://", true)) {
            val host = e.substringAfter("://").substringBefore('/').substringBeforeLast(':')
            return e to hostHeader(host)
        }
        val base = (if (e.startsWith("http", true)) e else "http://$e").toHttpUrl()
        val header = hostHeader(base.host)
        val req = Request.Builder().url(base.newBuilder().addPathSegments("json/version").build())
            .apply { header?.let { header("Host", it) } }
            .build()
        val json = client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("浏览器调试地址返回 ${res.code}")
            JsonParser.parseString(res.body.string()).asJsonObject
        }
        val ws = json.get("webSocketDebuggerUrl")?.asString ?: error("浏览器调试地址没有返回 webSocketDebuggerUrl")
        // 返回的地址里主机可能是 localhost / 容器内部地址：换成配置的主机和端口
        val path = ws.substringAfter("://").substringAfter('/', "")
        val scheme = if (base.isHttps) "wss" else "ws"
        return "$scheme://${base.host}:${base.port}/$path" to header
    }
}
