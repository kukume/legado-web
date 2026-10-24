package io.legado.web.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * 检查更新：比较本镜像的构建时间和镜像仓库里 latest 镜像的构建时间。
 *
 * - 本地：构建镜像时 GitHub Actions 取一次当前时间，作为 SOURCE_DATE_EPOCH 传给 Docker，
 *   同时写进环境变量 LEGADO_BUILD_TIME（秒）
 * - 远程：镜像配置里的 created 字段。构建时设置了 SOURCE_DATE_EPOCH，created 就是这个时间，与本地的完全相同
 *
 * 远程比本地晚就是有新版本。不是从镜像运行的（本地开发）没有构建时间，不检查。
 * 服务启动时不检查，管理员打开网页时检查（见 [checkIfStale]）。
 */
class UpdateService(
    /** 本镜像的构建时间（秒），没有时为 null */
    val buildTime: Long?,
    /** 检查哪个镜像，如 ghcr.io/kukume/legado-web */
    val image: String,
    private val enabled: () -> Boolean,
) {
    data class Status(
        val buildTime: Long?,
        val image: String,
        val latestTime: Long?,
        val checkedAt: Long?,
        val error: String?,
        val hasUpdate: Boolean,
        val enabled: Boolean,
    )

    private val logger = LoggerFactory.getLogger(UpdateService::class.java)

    // 单独的客户端：不经过书源引擎的拦截器和内网防护
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var latestTime: Long? = null
    @Volatile private var checkedAt: Long? = null
    @Volatile private var error: String? = null

    fun status() = Status(
        buildTime = buildTime,
        image = image,
        latestTime = latestTime,
        checkedAt = checkedAt,
        error = error,
        hasUpdate = buildTime != null && (latestTime ?: 0) > buildTime,
        enabled = enabled(),
    )

    /**
     * 管理员打开网页时调用：开关打开、不是本地开发、距上次检查超过 30 分钟时检查一次
     * （多个标签页、频繁刷新不会重复请求镜像仓库）
     */
    fun checkIfStale(): Status {
        val last = checkedAt
        if (buildTime != null && enabled() && (last == null || System.currentTimeMillis() - last > 30 * 60_000)) check()
        return status()
    }

    /** 立即检查一次（管理员点“检查更新”） */
    fun check(): Status {
        try {
            latestTime = remoteCreated()
            error = null
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            logger.warn("检查更新失败：{}", error)
        }
        checkedAt = System.currentTimeMillis()
        return status()
    }

    // ---------------- 镜像仓库（Docker Registry HTTP API v2） ----------------

    private val manifestTypes = listOf(
        "application/vnd.oci.image.index.v1+json",
        "application/vnd.docker.distribution.manifest.list.v2+json",
        "application/vnd.oci.image.manifest.v1+json",
        "application/vnd.docker.distribution.manifest.v2+json",
    ).joinToString(",")

    /** latest 镜像（与本机相同的 CPU 架构）的构建时间，秒 */
    private fun remoteCreated(): Long {
        val (registry, repo, tag) = parseImage(image)
        val base = "https://$registry/v2/$repo"
        var token: String? = null

        fun get(url: String, accept: String? = null): JsonObject {
            fun request(t: String?) = Request.Builder().url(url)
                .apply { accept?.let { header("Accept", it) } }
                .apply { t?.let { header("Authorization", "Bearer $it") } }
                .build()
            var res = client.newCall(request(token)).execute()
            // 公开镜像也要先取匿名令牌：按 401 返回的 WWW-Authenticate 获取
            if (res.code == 401 && token == null) {
                val auth = res.header("WWW-Authenticate").orEmpty()
                res.close()
                token = anonymousToken(auth, repo)
                res = client.newCall(request(token)).execute()
            }
            return res.use {
                if (!it.isSuccessful) error("镜像仓库返回 ${it.code}（$url）")
                JsonParser.parseString(it.body.string()).asJsonObject
            }
        }

        var manifest = get("$base/manifests/$tag", manifestTypes)
        // 多平台镜像：选与本机相同的架构
        manifest.getAsJsonArray("manifests")?.let { list ->
            val arch = currentArch()
            val entry = list.map { it.asJsonObject }.firstOrNull {
                val p = it.getAsJsonObject("platform")
                p?.get("os")?.asString == "linux" && p.get("architecture")?.asString == arch
            } ?: list.map { it.asJsonObject }.firstOrNull {
                it.getAsJsonObject("platform")?.get("os")?.asString == "linux"
            } ?: error("镜像中没有可用的平台")
            manifest = get("$base/manifests/${entry.get("digest").asString}", manifestTypes)
        }
        val configDigest = manifest.getAsJsonObject("config")?.get("digest")?.asString ?: error("镜像清单中没有配置")
        val config = get("$base/blobs/$configDigest")
        val created = config.get("created")?.asString ?: error("镜像配置中没有构建时间")
        return Instant.parse(created).epochSecond
    }

    private fun anonymousToken(wwwAuthenticate: String, repo: String): String {
        val params = Regex("""(\w+)="([^"]*)"""").findAll(wwwAuthenticate).associate { it.groupValues[1] to it.groupValues[2] }
        val realm = params["realm"] ?: error("镜像仓库没有返回认证地址")
        val url = realm.toHttpUrl().newBuilder()
            .apply { params["service"]?.let { addQueryParameter("service", it) } }
            .addQueryParameter("scope", params["scope"] ?: "repository:$repo:pull")
            .build()
        return client.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) error("获取镜像仓库令牌失败（${res.code}）")
            val json = JsonParser.parseString(res.body.string()).asJsonObject
            (json.get("token") ?: json.get("access_token"))?.asString ?: error("镜像仓库没有返回令牌")
        }
    }

    private fun currentArch() = when (System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "amd64"
        "aarch64", "arm64" -> "arm64"
        else -> System.getProperty("os.arch").lowercase()
    }

    companion object {
        /** ghcr.io/kukume/legado-web:latest、kukume/legado-web（Docker Hub）→ 仓库地址、名称、标签 */
        fun parseImage(image: String): Triple<String, String, String> {
            var ref = image.trim()
            var tag = "latest"
            val lastSlash = ref.lastIndexOf('/')
            val colon = ref.lastIndexOf(':')
            if (colon > lastSlash) {
                tag = ref.substring(colon + 1)
                ref = ref.substring(0, colon)
            }
            val first = ref.substringBefore('/')
            val hasRegistry = ref.contains('/') && (first.contains('.') || first.contains(':') || first == "localhost")
            return if (hasRegistry) {
                Triple(first, ref.substringAfter('/'), tag)
            } else {
                // Docker Hub：官方镜像在 library/ 下
                Triple("registry-1.docker.io", if (ref.contains('/')) ref else "library/$ref", tag)
            }
        }
    }
}
