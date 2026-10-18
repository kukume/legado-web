package io.legado.web.service

import com.google.gson.JsonObject
import io.legado.app.utils.GSON
import io.legado.web.auth.BadRequestException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * 登录、注册的人机验证，对接 Cap（https://capjs.js.org）Standalone 实例。
 * 管理员在“系统设置”里填写实例地址、Site Key 和 Secret；
 * 部署时设置环境变量 LEGADO_CAPTCHA_DISABLED=true 可以临时关闭（Cap 服务不可用、管理员无法登录时使用）。
 */
class CaptchaService(private val settings: SettingService, private val forceDisabled: Boolean) {

    /** 前端 cap-widget 的 data-cap-api-endpoint：<实例地址>/<Site Key>/ */
    data class PublicCaptcha(val provider: String, val endpoint: String)

    private val log = LoggerFactory.getLogger(CaptchaService::class.java)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun instance() = settings.get(SettingService.CAP_ENDPOINT).trim().trimEnd('/')
    private fun siteKey() = settings.get(SettingService.CAP_SITE_KEY).trim().trim('/')
    private fun secret() = settings.get(SettingService.CAP_SECRET).trim()

    /** 已开启且三项配置都填写了才生效，避免配置不全时谁都登录不了 */
    fun enabled(): Boolean = !forceDisabled && settings.getBool(SettingService.CAPTCHA_ENABLED) &&
        instance().isNotEmpty() && siteKey().isNotEmpty() && secret().isNotEmpty()

    fun publicConfig(): PublicCaptcha? =
        if (enabled()) PublicCaptcha("cap", "${instance()}/${siteKey()}/") else null

    /** 校验前端提交的验证码 token；未开启时直接通过 */
    suspend fun verify(token: String?) {
        if (!enabled()) return
        if (token.isNullOrBlank()) throw BadRequestException("请先完成人机验证")
        val ok = withContext(Dispatchers.IO) {
            val body = GSON.toJson(mapOf("secret" to secret(), "response" to token))
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("${instance()}/${siteKey()}/siteverify").post(body).build()
            try {
                client.newCall(req).execute().use { res ->
                    val text = res.body.string()
                    val json = runCatching { GSON.fromJson(text, JsonObject::class.java) }.getOrNull()
                    if (json == null) log.warn("Cap 校验返回异常：HTTP {} {}", res.code, text.take(200))
                    json?.get("success")?.asBoolean == true
                }
            } catch (e: Exception) {
                log.warn("Cap 校验请求失败：{}", e.message)
                throw BadRequestException("人机验证服务暂时不可用，请稍后再试")
            }
        }
        if (!ok) throw BadRequestException("人机验证未通过或已过期，请重新验证")
    }
}
