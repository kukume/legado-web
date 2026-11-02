package io.legado.web.auth

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.legado.web.service.SettingService
import java.util.concurrent.ConcurrentHashMap

/**
 * 登录、注册失败次数限制，防止暴力猜密码、猜邀请码。
 *
 * - 同一个 IP 对同一个用户名连续输错若干次，锁定这个用户名（只对这个 IP），期间密码正确也不能登录；
 *   登录成功清零。按“用户名 + IP”计数：别人输错不会锁住你的账号；用户名不存在也计数，提示与密码错误相同
 * - 同一个 IP 登录、注册累计失败若干次（换着用户名试、猜邀请码），整个 IP 锁定；登录成功不清零
 * - 30 分钟内没有再失败，重新计数
 * - 部署在 CDN / 反代后面时，从 CF-Connecting-IP、X-Real-IP、X-Forwarded-For 取客户端 IP
 * - 次数和锁定时长在“系统设置”里修改（[SettingService.LOGIN_MAX_FAILS] 等），次数为 0 表示不限制
 */
class LoginGuard(private val settings: SettingService) {

    private val maxFails get() = settings.getInt(SettingService.LOGIN_MAX_FAILS)
    private val ipMaxFails get() = settings.getInt(SettingService.LOGIN_IP_MAX_FAILS)
    private val lockSeconds get() = settings.getInt(SettingService.LOGIN_LOCK_SECONDS).coerceAtLeast(1).toLong()

    private class Entry(var fails: Int, var lastFail: Long, var lockedUntil: Long)

    /** key：“u:用户名|IP” 或 “ip:IP” */
    private val entries = ConcurrentHashMap<String, Entry>()

    private fun userKey(call: ApplicationCall, username: String) = "u:" + username.trim().lowercase() + "|" + clientIp(call)

    private fun ipKey(call: ApplicationCall) = "ip:" + clientIp(call)

    private fun clientIp(call: ApplicationCall): String {
        val h = call.request.headers
        return h["CF-Connecting-IP"]?.trim()?.ifEmpty { null }
            ?: h["X-Real-IP"]?.trim()?.ifEmpty { null }
            ?: h["X-Forwarded-For"]?.substringBefore(',')?.trim()?.ifEmpty { null }
            ?: call.request.origin.remoteHost
    }

    /** 登录前检查，锁定中抛出 [TooManyRequestsException] */
    fun checkLogin(call: ApplicationCall, username: String) {
        checkKey(ipKey(call))
        checkKey(userKey(call, username))
    }

    /** 注册前检查 */
    fun checkRegister(call: ApplicationCall) = checkKey(ipKey(call))

    fun onLoginFailure(call: ApplicationCall, username: String) {
        fail(ipKey(call), ipMaxFails)
        fail(userKey(call, username), maxFails)
    }

    fun onRegisterFailure(call: ApplicationCall) = fail(ipKey(call), ipMaxFails)

    fun onLoginSuccess(call: ApplicationCall, username: String) {
        entries.remove(userKey(call, username))
    }

    private fun checkKey(key: String) {
        val e = entries[key] ?: return
        val left = (e.lockedUntil - System.currentTimeMillis() + 999) / 1000
        if (left > 0) throw TooManyRequestsException("失败次数过多，请 ${left} 秒后再试")
    }

    private fun fail(key: String, max: Int) {
        if (max <= 0) return
        val now = System.currentTimeMillis()
        cleanup(now)
        entries.compute(key) { _, old ->
            val e = old?.takeIf { now - it.lastFail < RESET_MS } ?: Entry(0, now, 0)
            e.fails++
            e.lastFail = now
            if (e.fails >= max) {
                e.lockedUntil = now + lockSeconds * 1000
                e.fails = 0
            }
            e
        }
    }

    private fun cleanup(now: Long) {
        if (entries.size < 1000) return
        entries.entries.removeIf { now - it.value.lastFail > RESET_MS && now > it.value.lockedUntil }
    }

    private companion object {
        /** 连续失败超过这么久没有再试，重新计数 */
        const val RESET_MS = 30 * 60 * 1000L
    }
}
