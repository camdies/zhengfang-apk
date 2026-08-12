package com.tyust.course.login

import com.tyust.course.model.SchoolConfig
import com.tyust.course.session.AudienceRole
import com.tyust.course.session.ScnuProtocolCapabilities
import com.tyust.course.session.SessionArtifact
import com.tyust.course.session.SessionAudience
import com.tyust.course.session.SessionInstallTarget
import com.tyust.course.login.ScnuSsoProtocol.Transition
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SCNU SSO login gateway.
 *
 * State machine driven by [ScnuSsoProtocol] and the evidence profile from
 * [ScnuEvidenceProfile].  Success exports an RFC CookieBundle containing only
 * cookies that match the teaching service audience — never SSO transient
 * cookies, never a flattened String.
 */
class ScnuSsoLoginManager internal constructor(
    private val evidenceProfile: ScnuSsoProtocol.EvidenceProfile = ScnuEvidenceProfile.profile,
    private val clientFactory: (ProvenanceCookieJar) -> OkHttpClient = { jar ->
        OkHttpClient.Builder()
            .cookieJar(jar)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
) : SessionLoginGateway {
    private val stateLock = Any()
    private var activeAttempt: Attempt? = null

    override fun login(
        school: SchoolConfig,
        username: String,
        password: String,
        target: SessionInstallTarget,
        callback: SessionLoginCallback
    ) {
        clearSensitiveState()
        if (!ScnuProtocolCapabilities.isCanonicalScnu(school)) {
            callback.onError("该登录方式仅适用于 canonical SCNU 配置")
            return
        }
        if (!ScnuProtocolCapabilities.LOGIN_ENABLED) {
            callback.onError("SCNU 登录协议尚未验证，当前不可用")
            return
        }
        if (username.isBlank() || password.isEmpty()) {
            callback.onError("账号或密码不能为空")
            return
        }
        if (ScnuSsoProtocol.initial(evidenceProfile) is Transition.Rejected) {
            callback.onError("SCNU 协议档案无效，无法登录")
            return
        }

        val jar = ProvenanceCookieJar()
        val attempt = Attempt(
            username = username,
            password = password,
            jar = jar,
            target = target,
            callback = callback,
            client = clientFactory(jar)
        )
        synchronized(stateLock) { activeAttempt = attempt }

        // 第 1 段：初始化授权会话
        fetchEntry(attempt)
    }

    override fun submitCaptcha(captchaCode: String, callback: SessionLoginCallback) {
        val attempt = synchronized(stateLock) { activeAttempt }
        if (attempt == null || attempt.completed.get()) {
            callback.onError("登录会话已失效，请重新登录")
            return
        }
        // SCNU SSO 无验证码；任何验证码需求都视为协议未验证。
        fail(attempt, "SCNU 登录需要额外验证，请使用 Cookie/WebView 方式")
    }

    override fun refreshCaptcha(callback: (ByteArray?) -> Unit) {
        callback(null)
    }

    override fun clearSensitiveState() {
        val attempt = synchronized(stateLock) {
            val current = activeAttempt
            activeAttempt = null
            current
        } ?: return
        attempt.completed.set(true)
        attempt.currentCall?.cancel()
        attempt.close()
    }

    // ------------------------------------------------------------------
    // 状态机步骤
    // ------------------------------------------------------------------

    private fun fetchEntry(attempt: Attempt) {
        val entryUrl = evidenceProfile.entryUrl
        if (entryUrl == null || !evidenceProfile.allows(entryUrl)) {
            fail(attempt, "SCNU 协议档案入口无效")
            return
        }
        execute(attempt, Request.Builder().url(entryUrl).header("User-Agent", USER_AGENT).get().build()) { response ->
            response.use {
                if (!it.isSuccessful && it.code != 302) {
                    fail(attempt, "SCNU 统一认证入口返回错误 (${it.code})")
                    return@use
                }
                submitCredentials(attempt)
            }
        }
    }

    private fun submitCredentials(attempt: Attempt) {
        val loginUrl = "https://sso.scnu.edu.cn/AccountService/user/login.html".toHttpUrl()
        if (!evidenceProfile.allows(loginUrl)) {
            fail(attempt, "SCNU 登录地址未通过安全校验")
            return
        }
        val body = FormBody.Builder()
            .add("account", attempt.username)
            .add("password", attempt.password)
            .build()
        val request = Request.Builder()
            .url(loginUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", evidenceProfile.entryUrl?.toString() ?: "https://sso.scnu.edu.cn/")
            .header("Origin", "https://sso.scnu.edu.cn")
            .post(body)
            .build()
        execute(attempt, request, connectionFailureMessage = "连接统一认证服务失败") { response ->
            response.use {
                if (!it.isSuccessful && it.code != 302) {
                    fail(attempt, "SCNU 统一认证提交失败 (${it.code})")
                    return@use
                }
                // 第 3 段：确认页面
                fetchConfirm(attempt)
            }
        }
    }

    private fun fetchConfirm(attempt: Attempt) {
        val confirmUrl = "https://sso.scnu.edu.cn/AccountService/openapi/auth.html".toHttpUrl()
            .newBuilder()
            .setQueryParameter("sysname", ScnuEvidenceProfile.SSO_CONFIRM_SYSNAME)
            .build()
        if (!evidenceProfile.allows(confirmUrl)) {
            fail(attempt, "SCNU 确认地址未通过安全校验")
            return
        }
        execute(attempt, Request.Builder().url(confirmUrl).header("User-Agent", USER_AGENT).get().build()) { response ->
            response.use {
                if (!it.isSuccessful && it.code != 302) {
                    fail(attempt, "SCNU 确认页面返回错误 (${it.code})")
                    return@use
                }
                fastLogin(attempt)
            }
        }
    }

    private fun fastLogin(attempt: Attempt) {
        val fastUrl = "https://sso.scnu.edu.cn/AccountService/openapi/fastlogin.html".toHttpUrl()
            .newBuilder()
            .setQueryParameter("app_id", ScnuEvidenceProfile.SSO_APP_ID)
            .setQueryParameter("redirect_url", evidenceProfile.teachingCallbackUrl?.toString() ?: ScnuEvidenceProfile.teachingAudienceUrl)
            .build()
        if (!evidenceProfile.allows(fastUrl)) {
            fail(attempt, "SCNU 快速登录地址未通过安全校验")
            return
        }
        execute(attempt, Request.Builder().url(fastUrl).header("User-Agent", USER_AGENT).get().build()) { response ->
            response.use {
                if (response.code in REDIRECT_CODES) {
                    val location = response.header("Location")
                    val next = location?.let(response.request.url::resolve)
                    if (next == null || !evidenceProfile.allows(next)) {
                        fail(attempt, "SCNU 快速登录跳转地址未通过安全校验")
                        return@use
                    }
                    followTeachingRedirect(attempt, next, redirectCount = 1)
                } else if (it.isSuccessful) {
                    followTeachingRedirect(attempt, it.request.url, redirectCount = 0)
                } else {
                    fail(attempt, "SCNU 快速登录失败 (${it.code})")
                }
            }
        }
    }

    private fun followTeachingRedirect(attempt: Attempt, url: HttpUrl, redirectCount: Int) {
        if (redirectCount > MAX_REDIRECTS) {
            fail(attempt, "SCNU 教务跳转次数过多")
            return
        }
        if (!evidenceProfile.allows(url)) {
            fail(attempt, "SCNU 教务跳转地址未通过安全校验")
            return
        }
        val teachingCallback = evidenceProfile.teachingCallbackUrl
        if (teachingCallback != null && url == teachingCallback) {
            // 到达教务回调，但回调本身只是拿到授权码后的中转，继续跟随
            execute(attempt, Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()) { response ->
                response.use {
                    if (response.code in REDIRECT_CODES) {
                        val location = response.header("Location")
                        val next = location?.let(response.request.url::resolve)
                        if (next == null || !evidenceProfile.allows(next)) {
                            fail(attempt, "SCNU 教务回调跳转地址未通过安全校验")
                            return@use
                        }
                        followTeachingRedirect(attempt, next, redirectCount + 1)
                    } else {
                        completeWhenTeachingSession(attempt, it)
                    }
                }
            }
            return
        }
        execute(attempt, Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()) { response ->
            response.use {
                if (response.code in REDIRECT_CODES) {
                    val location = response.header("Location")
                    val next = location?.let(response.request.url::resolve)
                    if (next == null || !evidenceProfile.allows(next)) {
                        fail(attempt, "SCNU 教务跳转地址未通过安全校验")
                        return@use
                    }
                    followTeachingRedirect(attempt, next, redirectCount + 1)
                } else {
                    completeWhenTeachingSession(attempt, it)
                }
            }
        }
    }

    private fun completeWhenTeachingSession(attempt: Attempt, response: Response) {
        val url = response.request.url
        if (url.host != "jwxt.scnu.edu.cn") {
            fail(attempt, "SCNU 未到达教务系统 (${url.host})")
            return
        }
        val body = response.body?.string().orEmpty()
        // 成功判定：教务系统入口页，且不包含登录特征
        val isTeachingIndex = url.encodedPath.contains("index_initMenu") ||
            url.encodedPath.contains("sso/oauthLogin") ||
            (!body.contains("用户登录") && !body.contains("login-page-flowkey"))
        if (!isTeachingIndex) {
            fail(attempt, "SCNU 教务会话未建立，可能被重定向到登录页")
            return
        }

        // 导出教务 Cookie：只导出能 RFC 匹配教务 audience 的 Cookie
        val artifact = attempt.jar.exportRfcBundle(
            scope = attempt.target.schoolScope,
            audiences = listOf(
                SessionAudience(ScnuEvidenceProfile.teachingAudienceUrl, AudienceRole.SERVICE_REQUIRED)
            )
        )
        if (artifact.cookies.isEmpty()) {
            fail(attempt, "SCNU 教务系统未返回有效会话 Cookie")
            return
        }
        succeed(attempt, artifact)
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun execute(
        attempt: Attempt,
        request: Request,
        connectionFailureMessage: String = "网络连接失败，请检查网络后重试",
        onResponse: (Response) -> Unit
    ) {
        if (attempt.completed.get()) return
        val call = attempt.client.newCall(request)
        attempt.currentCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (attempt.completed.get()) return
                fail(attempt, connectionFailureMessage)
            }

            override fun onResponse(call: Call, response: Response) {
                if (attempt.completed.get()) {
                    response.close()
                    return
                }
                try {
                    onResponse(response)
                } catch (e: Exception) {
                    response.close()
                    fail(attempt, "处理 SCNU 响应失败: ${e.message}")
                }
            }
        })
    }

    private fun succeed(attempt: Attempt, artifact: SessionArtifact) {
        if (!attempt.completed.compareAndSet(false, true)) return
        detach(attempt)
        attempt.close()
        attempt.callback.onSuccess(artifact)
    }

    private fun fail(attempt: Attempt, message: String) {
        if (!attempt.completed.compareAndSet(false, true)) return
        detach(attempt)
        attempt.close()
        attempt.callback.onError(message)
    }

    private fun detach(attempt: Attempt) {
        synchronized(stateLock) {
            if (activeAttempt === attempt) activeAttempt = null
        }
    }

    private class Attempt(
        var username: String,
        var password: String,
        val jar: ProvenanceCookieJar,
        val target: SessionInstallTarget,
        val callback: SessionLoginCallback,
        val client: OkHttpClient
    ) {
        val completed = AtomicBoolean(false)
        @Volatile var currentCall: Call? = null

        fun close() {
            username = ""
            password = ""
            jar.clear()
            currentCall = null
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 12
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/139.0.0.0 Mobile Safari/537.36"
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
