package io.legado.web.service

import io.legado.web.auth.BadRequestException
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.UserPrincipal
import io.legado.web.db.ApiKeys
import io.legado.web.db.dbQuery
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.SecureRandom

data class ApiKeyDto(
    val id: Long,
    val name: String,
    /** 明文前缀，如 lgd_a1b2c3d4 */
    val prefix: String,
    val createdAt: Long,
    val lastUsedAt: Long?,
)

/** 创建结果：明文密钥只在这里返回一次 */
data class CreatedApiKey(val key: String, val info: ApiKeyDto)

/**
 * 开放接口密钥。数据库只保存 SHA-256 哈希，明文仅在创建时返回一次。
 */
class ApiKeyService(private val userService: UserService) {

    companion object {
        const val PREFIX = "lgd_"
        private const val MAX_KEYS_PER_USER = 10
    }

    private val random = SecureRandom()

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun newKey(): String {
        val bytes = ByteArray(24).also(random::nextBytes)
        return PREFIX + bytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun list(userId: Long): List<ApiKeyDto> = dbQuery {
        ApiKeys.selectAll().where { ApiKeys.userId eq userId }
            .orderBy(ApiKeys.id, SortOrder.DESC)
            .map {
                ApiKeyDto(
                    id = it[ApiKeys.id].value,
                    name = it[ApiKeys.name],
                    prefix = it[ApiKeys.prefix],
                    createdAt = it[ApiKeys.createdAt],
                    lastUsedAt = it[ApiKeys.lastUsedAt],
                )
            }
    }

    suspend fun create(userId: Long, name: String): CreatedApiKey {
        val keyName = name.trim().ifEmpty { "默认" }.take(64)
        val count = dbQuery { ApiKeys.selectAll().where { ApiKeys.userId eq userId }.count() }
        if (count >= MAX_KEYS_PER_USER) throw BadRequestException("最多只能创建 $MAX_KEYS_PER_USER 个 API Key")
        val key = newKey()
        val now = System.currentTimeMillis()
        val prefix = key.take(PREFIX.length + 8)
        val id = dbQuery {
            ApiKeys.insertAndGetId {
                it[ApiKeys.userId] = userId
                it[ApiKeys.name] = keyName
                it[keyHash] = sha256(key)
                it[ApiKeys.prefix] = prefix
                it[createdAt] = now
            }.value
        }
        return CreatedApiKey(key, ApiKeyDto(id, keyName, prefix, now, null))
    }

    suspend fun delete(userId: Long, id: Long) {
        val n = dbQuery { ApiKeys.deleteWhere { (ApiKeys.id eq id) and (ApiKeys.userId eq userId) } }
        if (n == 0) throw NotFoundException("API Key 不存在")
    }

    /**
     * 校验密钥，返回所属用户（用户被禁用时视为无效）
     */
    suspend fun authenticate(key: String?): UserPrincipal? {
        if (key.isNullOrBlank() || !key.startsWith(PREFIX)) return null
        val hash = sha256(key.trim())
        val row = dbQuery { ApiKeys.selectAll().where { ApiKeys.keyHash eq hash }.firstOrNull() } ?: return null
        val user = userService.findActive(row[ApiKeys.userId]) ?: return null
        // 最近使用时间：最多每分钟写一次
        val now = System.currentTimeMillis()
        if ((row[ApiKeys.lastUsedAt] ?: 0) < now - 60_000) {
            val id = row[ApiKeys.id].value
            dbQuery { ApiKeys.update({ ApiKeys.id eq id }) { it[lastUsedAt] = now } }
        }
        return UserPrincipal(user.id, user.username, user.role)
    }
}
