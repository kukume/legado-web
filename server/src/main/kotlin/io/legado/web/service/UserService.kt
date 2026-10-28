package io.legado.web.service

import io.legado.web.auth.BadRequestException
import io.legado.web.auth.ForbiddenException
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.Passwords
import io.legado.web.db.InviteCodes
import io.legado.web.db.InviteUsages
import io.legado.web.db.Users
import io.legado.web.db.dbBlocking
import io.legado.web.db.dbQuery
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

data class UserDto(
    val id: Long,
    val username: String,
    val role: String,
    val enabled: Boolean,
    val createdAt: Long,
    val lastLoginAt: Long?,
    val invitedBy: Long?,
    /** 是否单独授权添加书源 */
    val canAddSource: Boolean = false,
) {
    @Transient
    var tokenVersion: Int = 0
}

data class InviteDto(
    val id: Long,
    val code: String,
    val createdBy: Long,
    val createdByName: String?,
    val maxUses: Int,
    val usedCount: Int,
    val expiresAt: Long?,
    val note: String?,
    val disabled: Boolean,
    val createdAt: Long,
)

class UserService(private val settings: SettingService) {

    private val usernameRegex = Regex("^[A-Za-z0-9_\\-\\u4e00-\\u9fa5]{2,32}$")

    /** 短时间缓存用户状态，避免每个请求都查库 */
    private data class Cached(val user: UserDto?, val at: Long)

    private val activeCache = ConcurrentHashMap<Long, Cached>()

    private fun ResultRow.toUser() = UserDto(
        id = this[Users.id].value,
        username = this[Users.username],
        role = this[Users.role],
        enabled = this[Users.enabled],
        createdAt = this[Users.createdAt],
        lastLoginAt = this[Users.lastLoginAt],
        invitedBy = this[Users.invitedBy],
        canAddSource = this[Users.canAddSource],
    ).also { it.tokenVersion = this[Users.tokenVersion] }

    fun findActive(id: Long): UserDto? {
        val now = System.currentTimeMillis()
        activeCache[id]?.let { if (now - it.at < 30_000) return it.user }
        val user = dbBlocking {
            Users.selectAll().where { Users.id eq id }.firstOrNull()?.toUser()
        }?.takeIf { it.enabled }
        activeCache[id] = Cached(user, now)
        return user
    }

    private fun invalidate(id: Long) {
        activeCache.remove(id)
    }

    suspend fun hasUsers(): Boolean = dbQuery { Users.selectAll().limit(1).any() }

    suspend fun register(username: String, password: String, inviteCode: String?): UserDto {
        val name = username.trim()
        if (!usernameRegex.matches(name)) {
            throw BadRequestException("用户名为 2-32 位字母、数字、下划线或中文")
        }
        if (password.length < 6) throw BadRequestException("密码至少 6 位")
        val mode = settings.get(SettingService.REGISTRATION_MODE)
        return dbQuery {
            val isFirst = Users.selectAll().limit(1).empty()
            if (Users.selectAll().where { Users.username eq name }.any()) {
                throw BadRequestException("用户名已存在")
            }
            var inviterId: Long? = null
            var inviteId: Long? = null
            if (!isFirst) {
                when (mode) {
                    "closed" -> throw ForbiddenException("系统已关闭注册")
                    // 已取消开放注册：除“关闭”外一律要求邀请码（含历史存的 open）
                    else -> {
                        val code = inviteCode?.trim()
                        if (code.isNullOrEmpty()) throw BadRequestException("需要邀请码")
                        val invite = InviteCodes.selectAll().where { InviteCodes.code eq code }.firstOrNull()
                            ?: throw BadRequestException("邀请码无效")
                        if (invite[InviteCodes.disabled]) throw BadRequestException("邀请码已停用")
                        invite[InviteCodes.expiresAt]?.let {
                            if (it < System.currentTimeMillis()) throw BadRequestException("邀请码已过期")
                        }
                        val max = invite[InviteCodes.maxUses]
                        if (max > 0 && invite[InviteCodes.usedCount] >= max) {
                            throw BadRequestException("邀请码已用完")
                        }
                        inviteId = invite[InviteCodes.id].value
                        inviterId = invite[InviteCodes.createdBy]
                    }
                }
            }
            val now = System.currentTimeMillis()
            val id = Users.insertAndGetId {
                it[Users.username] = name
                it[passwordHash] = Passwords.hash(password)
                it[role] = if (isFirst) "admin" else "user"
                it[enabled] = true
                it[invitedBy] = inviterId
                it[createdAt] = now
            }.value
            inviteId?.let { iid ->
                InviteCodes.update({ InviteCodes.id eq iid }) {
                    it[usedCount] = usedCount + 1
                }
                InviteUsages.insertAndGetId {
                    it[InviteUsages.inviteId] = iid
                    it[userId] = id
                    it[usedAt] = now
                }
            }
            Users.selectAll().where { Users.id eq id }.first().toUser()
        }
    }

    /** 管理员直接创建用户（不受注册模式限制）。只能创建普通用户：管理员只有第一个注册的用户 */
    suspend fun adminCreate(operatorId: Long, username: String, password: String): UserDto {
        val name = username.trim()
        if (!usernameRegex.matches(name)) {
            throw BadRequestException("用户名为 2-32 位字母、数字、下划线或中文")
        }
        if (password.length < 6) throw BadRequestException("密码至少 6 位")
        return dbQuery {
            if (Users.selectAll().where { Users.username eq name }.any()) {
                throw BadRequestException("用户名已存在")
            }
            val id = Users.insertAndGetId {
                it[Users.username] = name
                it[passwordHash] = Passwords.hash(password)
                it[Users.role] = "user"
                it[enabled] = true
                it[invitedBy] = operatorId
                it[createdAt] = System.currentTimeMillis()
            }.value
            Users.selectAll().where { Users.id eq id }.first().toUser()
        }
    }

    suspend fun login(username: String, password: String): UserDto {
        val row = dbQuery {
            Users.selectAll().where { Users.username eq username.trim() }.firstOrNull()
        } ?: throw BadRequestException("用户名或密码错误")
        if (!Passwords.verify(password, row[Users.passwordHash])) {
            throw BadRequestException("用户名或密码错误")
        }
        if (!row[Users.enabled]) throw ForbiddenException("账号已被禁用")
        val id = row[Users.id].value
        dbQuery {
            Users.update({ Users.id eq id }) { it[lastLoginAt] = System.currentTimeMillis() }
        }
        return row.toUser()
    }

    suspend fun get(id: Long): UserDto = dbQuery {
        Users.selectAll().where { Users.id eq id }.firstOrNull()?.toUser()
    } ?: throw NotFoundException("用户不存在")

    suspend fun changePassword(id: Long, oldPassword: String, newPassword: String): UserDto {
        if (newPassword.length < 6) throw BadRequestException("密码至少 6 位")
        val row = dbQuery { Users.selectAll().where { Users.id eq id }.first() }
        if (!Passwords.verify(oldPassword, row[Users.passwordHash])) {
            throw BadRequestException("原密码错误")
        }
        dbQuery {
            Users.update({ Users.id eq id }) {
                it[passwordHash] = Passwords.hash(newPassword)
                it[tokenVersion] = tokenVersion + 1
            }
        }
        invalidate(id)
        return get(id)
    }

    suspend fun list(): List<UserDto> = dbQuery {
        Users.selectAll().orderBy(Users.id, SortOrder.ASC).map { it.toUser() }
    }

    suspend fun adminUpdate(
        operatorId: Long,
        id: Long,
        enabled: Boolean?,
        password: String?,
        canAddSource: Boolean? = null,
    ): UserDto {
        // 角色不能修改：管理员只有第一个注册的用户
        if (id == operatorId && enabled == false) throw BadRequestException("不能禁用自己")
        if (password != null && password.length < 6) throw BadRequestException("密码至少 6 位")
        // 状态、密码变化需要让旧登录失效；单纯修改书源权限不影响登录
        val invalidateTokens = enabled != null || password != null
        dbQuery {
            Users.update({ Users.id eq id }) {
                enabled?.let { e -> it[Users.enabled] = e }
                password?.let { p -> it[passwordHash] = Passwords.hash(p) }
                canAddSource?.let { c -> it[Users.canAddSource] = c }
                if (invalidateTokens) it[tokenVersion] = tokenVersion + 1
            }
        }
        invalidate(id)
        return get(id)
    }

    suspend fun delete(operatorId: Long, id: Long) {
        if (id == operatorId) throw BadRequestException("不能删除自己")
        dbQuery { Users.deleteWhere { Users.id eq id } }
        invalidate(id)
    }

    // ---------------- 邀请码 ----------------

    private val random = SecureRandom()
    private val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private fun randomCode(): String = (1..10).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")

    suspend fun listInvites(userId: Long?): List<InviteDto> = dbQuery {
        val names = Users.selectAll().associate { it[Users.id].value to it[Users.username] }
        val q = InviteCodes.selectAll()
        if (userId != null) q.where { InviteCodes.createdBy eq userId }
        q.orderBy(InviteCodes.id, SortOrder.DESC).map {
            InviteDto(
                id = it[InviteCodes.id].value,
                code = it[InviteCodes.code],
                createdBy = it[InviteCodes.createdBy],
                createdByName = names[it[InviteCodes.createdBy]],
                maxUses = it[InviteCodes.maxUses],
                usedCount = it[InviteCodes.usedCount],
                expiresAt = it[InviteCodes.expiresAt],
                note = it[InviteCodes.note],
                disabled = it[InviteCodes.disabled],
                createdAt = it[InviteCodes.createdAt],
            )
        }
    }

    suspend fun createInvites(
        creatorId: Long,
        count: Int,
        maxUses: Int,
        expireDays: Int?,
        note: String?,
    ): List<InviteDto> {
        val n = count.coerceIn(1, 100)
        val uses = maxUses.coerceIn(0, 10000)
        val now = System.currentTimeMillis()
        dbQuery {
            repeat(n) {
                InviteCodes.insertAndGetId {
                    it[code] = randomCode()
                    it[createdBy] = creatorId
                    it[InviteCodes.maxUses] = uses
                    it[expiresAt] = expireDays?.takeIf { d -> d > 0 }?.let { d -> now + d * 86400_000L }
                    it[InviteCodes.note] = note?.take(255)
                    it[createdAt] = now
                }
            }
        }
        return listInvites(creatorId).take(n)
    }

    suspend fun updateInvite(id: Long, disabled: Boolean) {
        dbQuery {
            InviteCodes.selectAll().where { InviteCodes.id eq id }.firstOrNull() ?: throw NotFoundException()
            InviteCodes.update({ InviteCodes.id eq id }) { it[InviteCodes.disabled] = disabled }
        }
    }

    suspend fun deleteInvite(id: Long) {
        dbQuery {
            InviteCodes.selectAll().where { InviteCodes.id eq id }.firstOrNull() ?: throw NotFoundException()
            InviteCodes.deleteWhere { InviteCodes.id eq id }
        }
    }
}
