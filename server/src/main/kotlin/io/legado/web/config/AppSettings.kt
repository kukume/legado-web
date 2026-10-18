package io.legado.web.config

import io.ktor.server.config.ApplicationConfig
import java.io.File

data class DbSettings(
    /** sqlite | postgres */
    val type: String,
    val url: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int,
)

data class AppSettings(
    val dataDir: File,
    val db: DbSettings,
    val jwtSecret: String,
    /** 书源登录信息等引擎数据的加密密钥（与 JWT 密钥独立，更换 JWT 密钥不影响已保存的登录信息） */
    val engineSecret: String,
    val jwtIssuer: String,
    val jwtExpireDays: Int,
    val userAgent: String?,
    val threadCount: Int,
    /** 多书源搜索时的并发数 */
    val searchConcurrency: Int,
    /** 单个书源搜索超时（毫秒） */
    val searchTimeout: Long,
    val corsHosts: List<String>,
    /** 强制关闭登录、注册的人机验证（Cap 服务不可用、管理员登录不了时用） */
    val captchaDisabled: Boolean,
) {
    companion object {
        fun from(config: ApplicationConfig): AppSettings {
            fun str(path: String): String? = config.propertyOrNull(path)?.getString()?.takeIf { it.isNotBlank() }

            val dataDir = File(str("legado.dataDir") ?: "./data").absoluteFile.apply { mkdirs() }
            val dbType = (str("legado.db.type") ?: "sqlite").lowercase()
            val dbUrl = str("legado.db.url") ?: when (dbType) {
                "postgres", "postgresql" -> "jdbc:postgresql://localhost:5432/legado"
                else -> "jdbc:sqlite:" + File(dataDir, "legado.db").path.replace('\\', '/')
            }
            var secret = str("legado.jwt.secret")
            if (secret == null) {
                // 没有配置时自动生成并持久化，重启后 token 仍然有效
                val f = File(dataDir, ".jwt-secret")
                secret = if (f.exists()) f.readText().trim() else {
                    val s = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID().toString()
                    f.writeText(s)
                    s
                }
            }
            // 引擎密钥：优先配置项，否则读取 dataDir/.engine-secret；首次生成时沿用当前 JWT 密钥，保证已有数据仍可解密
            val engineSecret = str("legado.engine.secret") ?: run {
                val f = File(dataDir, ".engine-secret")
                if (f.exists()) f.readText().trim() else secret.also { f.writeText(it) }
            }
            return AppSettings(
                dataDir = dataDir,
                db = DbSettings(
                    type = if (dbType.startsWith("postgres")) "postgres" else "sqlite",
                    url = dbUrl,
                    user = str("legado.db.user") ?: "",
                    password = str("legado.db.password") ?: "",
                    maxPoolSize = str("legado.db.maxPoolSize")?.toIntOrNull() ?: 10,
                ),
                jwtSecret = secret,
                engineSecret = engineSecret,
                jwtIssuer = str("legado.jwt.issuer") ?: "legado-web",
                jwtExpireDays = str("legado.jwt.expireDays")?.toIntOrNull() ?: 30,
                userAgent = str("legado.engine.userAgent"),
                threadCount = str("legado.engine.threadCount")?.toIntOrNull() ?: 8,
                searchConcurrency = str("legado.engine.searchConcurrency")?.toIntOrNull() ?: 16,
                searchTimeout = str("legado.engine.searchTimeout")?.toLongOrNull() ?: 30000L,
                corsHosts = str("legado.cors")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: emptyList(),
                captchaDisabled = str("legado.captcha.disabled")?.lowercase() in setOf("true", "1", "yes"),
            )
        }
    }
}
