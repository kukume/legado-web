package io.legado.web.service

import io.legado.web.auth.BadRequestException
import io.legado.web.auth.ForbiddenException
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.UserPrincipal
import io.legado.web.db.SourceSubscriptions
import io.legado.web.db.Users
import io.legado.web.db.dbQuery
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory

data class SubscriptionDto(
    val id: Long,
    val name: String,
    val url: String,
    val isPublic: Boolean,
    val autoUpdate: Boolean,
    val lastUpdateAt: Long,
    val lastResult: String?,
    val owned: Boolean,
)

/**
 * 书源订阅：定期从链接拉取书源并导入
 */
class SubscriptionService(private val sourceService: SourceService) {

    private val logger = LoggerFactory.getLogger(SubscriptionService::class.java)

    suspend fun list(user: UserPrincipal): List<SubscriptionDto> = dbQuery {
        SourceSubscriptions.selectAll()
            .where { (SourceSubscriptions.ownerId eq user.id) or (SourceSubscriptions.isPublic eq true) }
            .orderBy(SourceSubscriptions.id, SortOrder.ASC)
            .map {
                SubscriptionDto(
                    id = it[SourceSubscriptions.id].value,
                    name = it[SourceSubscriptions.name],
                    url = it[SourceSubscriptions.url],
                    isPublic = it[SourceSubscriptions.isPublic],
                    autoUpdate = it[SourceSubscriptions.autoUpdate],
                    lastUpdateAt = it[SourceSubscriptions.lastUpdateAt],
                    lastResult = it[SourceSubscriptions.lastResult],
                    owned = it[SourceSubscriptions.ownerId] == user.id,
                )
            }
    }

    suspend fun create(user: UserPrincipal, name: String, url: String, isPublic: Boolean, autoUpdate: Boolean): Long {
        if (!sourceService.canAddSource(user)) throw ForbiddenException("管理员未开放用户添加书源")
        if (!url.startsWith("http")) throw BadRequestException("订阅链接无效")
        val id = dbQuery {
            SourceSubscriptions.insertAndGetId {
                it[ownerId] = user.id
                it[SourceSubscriptions.name] = name.ifBlank { url }.take(255)
                it[SourceSubscriptions.url] = url.take(2048)
                it[SourceSubscriptions.isPublic] = isPublic && user.isAdmin
                it[SourceSubscriptions.autoUpdate] = autoUpdate
                it[createdAt] = System.currentTimeMillis()
            }.value
        }
        runCatching { refresh(user, id) }
        return id
    }

    suspend fun delete(user: UserPrincipal, id: Long) {
        dbQuery {
            val row = SourceSubscriptions.selectAll().where { SourceSubscriptions.id eq id }.firstOrNull()
                ?: throw NotFoundException()
            if (row[SourceSubscriptions.ownerId] != user.id && !user.isAdmin) throw ForbiddenException()
            SourceSubscriptions.deleteWhere { SourceSubscriptions.id eq id }
        }
    }

    suspend fun refresh(user: UserPrincipal, id: Long): ImportResult {
        val row = dbQuery { SourceSubscriptions.selectAll().where { SourceSubscriptions.id eq id }.firstOrNull() }
            ?: throw NotFoundException()
        if (row[SourceSubscriptions.ownerId] != user.id && !user.isAdmin) throw ForbiddenException()
        val result = try {
            // 以订阅所有者身份导入
            sourceService.importFromUrl(user, row[SourceSubscriptions.url], row[SourceSubscriptions.isPublic])
        } catch (e: Exception) {
            dbQuery {
                SourceSubscriptions.update({ SourceSubscriptions.id eq id }) {
                    it[lastResult] = "失败: ${e.message}".take(500)
                    it[lastUpdateAt] = System.currentTimeMillis()
                }
            }
            throw e
        }
        dbQuery {
            SourceSubscriptions.update({ SourceSubscriptions.id eq id }) {
                it[lastResult] = "新增 ${result.added}，更新 ${result.updated}，失败 ${result.failed}"
                it[lastUpdateAt] = System.currentTimeMillis()
            }
        }
        return result
    }

    /** 定时任务：刷新所有开启自动更新的订阅 */
    suspend fun refreshAll() {
        val subs = dbQuery {
            SourceSubscriptions.selectAll().where { SourceSubscriptions.autoUpdate eq true }.map {
                Triple(it[SourceSubscriptions.id].value, it[SourceSubscriptions.ownerId], it[SourceSubscriptions.lastUpdateAt])
            }
        }
        val now = System.currentTimeMillis()
        for ((id, ownerId, last) in subs) {
            if (now - last < 20 * 3600_000L) continue
            val owner = dbQuery {
                Users.selectAll().where { Users.id eq ownerId }.firstOrNull()
            } ?: continue
            val principal = UserPrincipal(ownerId, owner[Users.username], owner[Users.role])
            runCatching { refresh(principal, id) }.onFailure {
                logger.warn("刷新书源订阅失败 {}: {}", id, it.message)
            }
        }
    }
}
