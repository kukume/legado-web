package io.legado.web.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.legado.web.config.AppSettings
import io.legado.web.service.UserService
import java.util.Date

object Passwords {
    fun hash(password: String): String =
        BCrypt.withDefaults().hashToString(10, password.toCharArray())

    fun verify(password: String, hash: String): Boolean =
        BCrypt.verifyer().verify(password.toCharArray(), hash).verified
}

data class UserPrincipal(
    val id: Long,
    val username: String,
    val role: String,
) {
    val isAdmin get() = role == "admin"
}

class JwtService(private val settings: AppSettings) {
    val algorithm: Algorithm = Algorithm.HMAC256(settings.jwtSecret)
    val issuer get() = settings.jwtIssuer

    fun createToken(userId: Long, username: String, role: String, tokenVersion: Int): String {
        return JWT.create()
            .withIssuer(settings.jwtIssuer)
            .withSubject(userId.toString())
            .withClaim("name", username)
            .withClaim("role", role)
            .withClaim("ver", tokenVersion)
            .withExpiresAt(Date(System.currentTimeMillis() + settings.jwtExpireDays * 86400_000L))
            .sign(algorithm)
    }
}

const val AUTH_JWT = "jwt"

fun Application.configureSecurity(jwtService: JwtService, userService: UserService) {
    install(Authentication) {
        jwt(AUTH_JWT) {
            realm = "legado-web"
            // 支持 Authorization 头，以及 ?token= 参数（图片代理、SSE 等无法设置请求头的场景）
            authHeader { call ->
                call.request.headers["Authorization"]?.let {
                    return@authHeader io.ktor.http.auth.parseAuthorizationHeader(it)
                }
                call.request.queryParameters["token"]?.let {
                    HttpAuthHeader.Single("Bearer", it)
                }
            }
            verifier(
                JWT.require(jwtService.algorithm)
                    .withIssuer(jwtService.issuer)
                    .build()
            )
            validate { credential ->
                val userId = credential.payload.subject?.toLongOrNull() ?: return@validate null
                val ver = credential.payload.getClaim("ver").asInt() ?: 0
                val user = userService.findActive(userId) ?: return@validate null
                if (user.tokenVersion != ver) return@validate null
                JWTPrincipal(credential.payload)
            }
        }
    }
}

val ApplicationCall.user: UserPrincipal
    get() {
        val p = principal<JWTPrincipal>() ?: throw UnauthorizedException()
        return UserPrincipal(
            id = p.payload.subject.toLong(),
            username = p.payload.getClaim("name").asString() ?: "",
            role = p.payload.getClaim("role").asString() ?: "user",
        )
    }

class UnauthorizedException(msg: String = "未登录或登录已过期") : RuntimeException(msg)
class ForbiddenException(msg: String = "没有权限") : RuntimeException(msg)
class BadRequestException(msg: String) : RuntimeException(msg)
class NotFoundException(msg: String = "资源不存在") : RuntimeException(msg)

fun ApplicationCall.requireAdmin(): UserPrincipal {
    val u = user
    if (!u.isAdmin) throw ForbiddenException("需要管理员权限")
    return u
}
