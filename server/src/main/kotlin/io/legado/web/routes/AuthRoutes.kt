package io.legado.web.routes

import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.legado.web.auth.AUTH_JWT
import io.legado.web.auth.JwtService
import io.legado.web.auth.requireAdmin
import io.legado.web.auth.user
import io.legado.web.service.CaptchaService
import io.legado.web.service.SettingService
import io.legado.web.service.UserDto
import io.legado.web.service.UserService

data class LoginRequest(val username: String = "", val password: String = "", val captchaToken: String? = null)
data class RegisterRequest(
    val username: String = "",
    val password: String = "",
    val inviteCode: String? = null,
    val captchaToken: String? = null,
)
data class ChangePasswordRequest(val oldPassword: String = "", val newPassword: String = "")
data class AuthResponse(val token: String, val user: UserDto)
data class PublicConfig(
    val siteName: String,
    val registrationMode: String,
    val hasUsers: Boolean,
    /** 登录、注册需要的人机验证；null 表示不需要 */
    val captcha: CaptchaService.PublicCaptcha?,
)
data class MeResponse(
    val user: UserDto,
    val canAddSource: Boolean,
)
data class CreateInviteRequest(val count: Int = 1, val maxUses: Int = 1, val expireDays: Int? = null, val note: String? = null)
data class UpdateInviteRequest(val disabled: Boolean = false)
data class AdminUserUpdate(
    val enabled: Boolean? = null,
    val password: String? = null,
    val canAddSource: Boolean? = null,
)
data class AdminCreateUser(val username: String = "", val password: String = "")

/** 设置页显示用的服务器信息（只读，不保存）：是否设置了浏览器 */
private fun serverInfo() = mapOf(
    "browserAvailable" to (io.legado.app.help.engine.Engine.webViewProvider != null).toString(),
)

fun Route.authRoutes(
    userService: UserService,
    jwt: JwtService,
    settings: SettingService,
    captcha: CaptchaService,
    updates: io.legado.web.service.UpdateService,
) {
    route("/api/auth") {
        get("/config") {
            call.respond(
                PublicConfig(
                    siteName = settings.get(SettingService.SITE_NAME),
                    registrationMode = settings.get(SettingService.REGISTRATION_MODE),
                    hasUsers = userService.hasUsers(),
                    captcha = captcha.publicConfig(),
                )
            )
        }
        post("/login") {
            val req = call.receive<LoginRequest>()
            captcha.verify(req.captchaToken)
            val u = userService.login(req.username, req.password)
            call.respond(AuthResponse(jwt.createToken(u.id, u.username, u.role, u.tokenVersion), u))
        }
        post("/register") {
            val req = call.receive<RegisterRequest>()
            captcha.verify(req.captchaToken)
            val u = userService.register(req.username, req.password, req.inviteCode)
            call.respond(AuthResponse(jwt.createToken(u.id, u.username, u.role, u.tokenVersion), u))
        }
        authenticate(AUTH_JWT) {
            get("/me") {
                val u = userService.get(call.user.id)
                call.respond(
                    MeResponse(
                        user = u,
                        canAddSource = u.role == "admin" || u.canAddSource ||
                            settings.getBool(SettingService.ALLOW_USER_SOURCES),
                    )
                )
            }
            post("/password") {
                val req = call.receive<ChangePasswordRequest>()
                val u = userService.changePassword(call.user.id, req.oldPassword, req.newPassword)
                call.respond(AuthResponse(jwt.createToken(u.id, u.username, u.role, u.tokenVersion), u))
            }
        }
    }

    authenticate(AUTH_JWT) {
        // 邀请码：只有管理员可以生成和管理
        route("/api/invites") {
            get {
                call.requireAdmin()
                call.respond(userService.listInvites(null))
            }
            post {
                val admin = call.requireAdmin()
                val req = call.receive<CreateInviteRequest>()
                call.respond(userService.createInvites(admin.id, req.count, req.maxUses, req.expireDays, req.note))
            }
            patch("/{id}") {
                call.requireAdmin()
                userService.updateInvite(call.longParam("id"), call.receive<UpdateInviteRequest>().disabled)
                call.respond(OkResponse())
            }
            delete("/{id}") {
                call.requireAdmin()
                userService.deleteInvite(call.longParam("id"))
                call.respond(OkResponse())
            }
        }

        route("/api/admin") {
            get("/users") {
                call.requireAdmin()
                call.respond(userService.list())
            }
            post("/users") {
                call.requireAdmin()
                val req = call.receive<AdminCreateUser>()
                call.respond(userService.adminCreate(call.user.id, req.username, req.password))
            }
            patch("/users/{id}") {
                val admin = call.requireAdmin()
                val req = call.receive<AdminUserUpdate>()
                call.respond(userService.adminUpdate(admin.id, call.longParam("id"), req.enabled, req.password, req.canAddSource))
            }
            delete("/users/{id}") {
                val admin = call.requireAdmin()
                userService.delete(admin.id, call.longParam("id"))
                call.respond(OkResponse())
            }
            get("/settings") {
                call.requireAdmin()
                call.respond(settings.all() + serverInfo())
            }
            put("/settings") {
                call.requireAdmin()
                settings.update(call.receive<Map<String, String>>())
                call.respond(settings.all() + serverInfo())
            }
            /** 当前版本（构建时间）和检查更新的结果；?auto=true 时（管理员打开网页）按需检查一次 */
            get("/update") {
                call.requireAdmin()
                if (call.request.queryParameters["auto"] == "true") {
                    call.respond(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { updates.checkIfStale() })
                } else {
                    call.respond(updates.status())
                }
            }
            /** 立即检查更新 */
            post("/update/check") {
                call.requireAdmin()
                if (updates.buildTime == null) throw io.legado.web.auth.BadRequestException("开发版没有构建时间，无法检查更新")
                call.respond(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { updates.check() })
            }
            /** 测试浏览器调试地址：能连上时返回浏览器版本 */
            post("/browser/test") {
                call.requireAdmin()
                val cdp = call.receive<Map<String, String>>()["cdp"]?.trim().orEmpty()
                if (cdp.isEmpty()) throw io.legado.web.auth.BadRequestException("请填写浏览器调试地址")
                val info = try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io.legado.web.webview.RemoteCdp.version(cdp) }
                } catch (e: Exception) {
                    throw io.legado.web.auth.BadRequestException("连接失败：${e.message ?: e.javaClass.simpleName}")
                }
                call.respond(mapOf("browser" to (info["Browser"] ?: ""), "userAgent" to (info["User-Agent"] ?: "")))
            }
        }
    }
}
