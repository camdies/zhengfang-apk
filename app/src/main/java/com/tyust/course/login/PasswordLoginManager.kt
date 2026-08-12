package com.tyust.course.login

import android.util.Log
import com.tyust.course.model.SchoolConfig
import com.tyust.course.network.CourseApiClient
import com.tyust.course.session.SchoolSessionScope
import com.tyust.course.session.SessionInstallTarget
import com.tyust.course.session.SessionRegistry
import com.tyust.course.session.SessionRequestContext
import com.tyust.course.session.SessionRequestOwner
import com.tyust.course.session.SessionRequestPurpose
import com.tyust.course.utils.RSAUtils
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.regex.Pattern

interface PasswordLoginCallback {
    fun onSuccess(cookie: String)
    fun onCaptchaRequired(imageBytes: ByteArray)
    fun onCaptchaInvalid()
    fun onInvalidCredentials()
    fun onError(message: String)
}

class PasswordLoginManager : PasswordLoginGateway, SessionTargetAwarePasswordLoginGateway {
    companion object {
        private const val TAG = "PasswordLogin"
    }

    private var csrftoken = ""
    private var hiddenFields = mutableMapOf<String, String>()
    private var modulus = ""
    private var exponent = ""
    private var encryptedPassword = ""
    private var currentSchool: SchoolConfig? = null
    private var currentUsername = ""
    private var currentPassword = ""
    private var pendingInstallTarget: SessionInstallTarget? = null

    private data class LoginBinding(
        val target: SessionInstallTarget,
        val requestContext: SessionRequestContext
    )

    @Volatile
    private var activeBinding: LoginBinding? = null

    fun getCurrentUsername(): String = currentUsername
    fun getCurrentPassword(): String = currentPassword

    override fun bindSessionInstallTarget(target: SessionInstallTarget) {
        pendingInstallTarget = target
    }

    override fun login(school: SchoolConfig, username: String, password: String, callback: PasswordLoginCallback) {
        val target = pendingInstallTarget
        if (target == null) {
            callback.onError("登录会话已失效，请重新登录")
            return
        }
        if (SchoolSessionScope.fromSchool(school) != target.schoolScope) {
            callback.onError("登录会话作用域无效，请重新登录")
            return
        }
        if (SessionRegistry.snapshot(target.accountStorageKey).generation != target.expectedGeneration) {
            callback.onError("账号已切换，本次登录已取消")
            return
        }

        clearSensitiveFields(clearInstallTarget = false)
        currentSchool = school
        currentUsername = username
        currentPassword = password
        val requestContext = SessionRequestContext.forSchool(
            school = school,
            accountStorageKey = target.accountStorageKey,
            purpose = SessionRequestPurpose.LOGIN_FLOW,
            owner = SessionRequestOwner.BACKGROUND
        )
        if (requestContext.sessionGeneration != target.expectedGeneration) {
            callback.onError("账号已切换，本次登录已取消")
            return
        }
        pendingInstallTarget = null
        val binding = LoginBinding(target, requestContext)
        activeBinding = binding
        // 清除旧Cookie（如 rememberMe=deleteMe 等脏数据），避免干扰 kaptcha 请求
        CourseApiClient.getInstance().clearCookies(binding.requestContext.normalizedAccountStorageKey)
        fetchLoginPage(binding, callback)
    }

    override fun submitCaptcha(captchaCode: String, callback: PasswordLoginCallback) {
        val binding = currentBinding() ?: return callback.onError("登录会话已失效，请重新登录")
        val school = currentSchool ?: return callback.onError("学校配置丢失")
        if (encryptedPassword.isEmpty()) {
            // 验证码在登录页就出现，需要先获取公钥再提交
            fetchPublicKeyAndPost(binding, school, captchaCode, callback)
        } else {
            postLogin(binding, school, captchaCode, callback)
        }
    }

    private fun currentBinding(): LoginBinding? = activeBinding?.takeIf(::isBindingCurrent)

    private fun isBindingCurrent(binding: LoginBinding): Boolean =
        activeBinding === binding && binding.requestContext.isSnapshotCurrent()

    private fun clearSensitiveFields(clearInstallTarget: Boolean) {
        csrftoken = ""
        hiddenFields.clear()
        modulus = ""
        exponent = ""
        encryptedPassword = ""
        currentSchool = null
        currentUsername = ""
        currentPassword = ""
        activeBinding = null
        if (clearInstallTarget) pendingInstallTarget = null
    }

    private fun fetchPublicKeyAndPost(
        binding: LoginBinding,
        school: SchoolConfig,
        captchaCode: String,
        callback: PasswordLoginCallback
    ) {
        CourseApiClient.getInstance().getPublicKey(school, binding.requestContext, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!isBindingCurrent(binding)) return
                callback.onError("获取公钥失败: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                if (!isBindingCurrent(binding)) {
                    response.close()
                    return
                }
                try {
                    val json = JSONObject(response.body?.string() ?: "")
                    if (!isBindingCurrent(binding)) return
                    modulus = json.optString("modulus", "")
                    exponent = json.optString("exponent", "")
                    if (modulus.isEmpty() || exponent.isEmpty()) {
                        callback.onError("该学校密码加密方式不支持，请使用 Cookie 模式")
                        return
                    }
                    encryptedPassword = RSAUtils.encrypt(modulus, exponent, currentPassword)
                    postLogin(binding, school, captchaCode, callback)
                } catch (e: Exception) {
                    if (isBindingCurrent(binding)) {
                        callback.onError("加密密码失败: ${e.message}")
                    }
                } finally {
                    response.close()
                }
            }
        })
    }

    override fun refreshCaptcha(callback: (ByteArray?) -> Unit) {
        val binding = currentBinding() ?: return callback(null)
        val school = currentSchool ?: return callback(null)
        CourseApiClient.getInstance().getCaptchaImage(school, binding.requestContext, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (isBindingCurrent(binding)) callback(null)
            }
            override fun onResponse(call: Call, response: Response) {
                if (!isBindingCurrent(binding)) {
                    response.close()
                    return
                }
                try {
                    callback(response.body?.bytes())
                } finally {
                    response.close()
                }
            }
        })
    }

    override fun clearSensitiveState() {
        clearSensitiveFields(clearInstallTarget = true)
    }

    private fun fetchCaptchaImage(
        binding: LoginBinding,
        school: SchoolConfig,
        callback: PasswordLoginCallback,
        retryCount: Int = 0
    ) {
        // 首次请求前短暂延迟，确保服务端会话状态同步
        val delay = if (retryCount == 0) 500L else 1000L * retryCount
        Thread.sleep(delay)
        if (!isBindingCurrent(binding)) return

        CourseApiClient.getInstance().getCaptchaImage(school, binding.requestContext, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!isBindingCurrent(binding)) return
                Log.e(TAG, "Captcha fetch failed (attempt ${retryCount + 1}): ${e.message}")
                callback.onError("获取验证码失败: ${e.message}")
            }
            override fun onResponse(call: Call, resp: Response) {
                if (!isBindingCurrent(binding)) {
                    resp.close()
                    return
                }
                try {
                    val bytes = resp.body?.bytes()
                    if (!isBindingCurrent(binding)) return
                    Log.d(TAG, "Captcha image: code=${resp.code}, size=${bytes?.size ?: 0} bytes (attempt ${retryCount + 1})")
                    if (bytes != null && bytes.isNotEmpty()) {
                        callback.onCaptchaRequired(bytes)
                    } else if (retryCount < 2) {
                        // 502 或空响应：清除 route cookie 后重试（解决负载均衡路由不一致问题）
                        Log.w(TAG, "Captcha empty/502, clearing route cookie and retrying...")
                        clearRouteCookie(school, binding)
                        fetchCaptchaImage(binding, school, callback, retryCount + 1)
                    } else {
                        Log.w(TAG, "Captcha unavailable after ${retryCount + 1} attempts, proceeding without captcha")
                        // 验证码不可用，直接进行登录（不带验证码）
                        fetchPublicKey(binding, callback)
                    }
                } catch (e: Exception) {
                    if (isBindingCurrent(binding)) {
                        Log.e(TAG, "Captcha response error: ${e.message}", e)
                        callback.onError("处理验证码响应失败: ${e.message}")
                    }
                } finally {
                    resp.close()
                }
            }
        })
    }

    private fun clearRouteCookie(school: SchoolConfig, binding: LoginBinding) {
        // 清除 route cookie，让负载均衡器重新分配后端
        try {
            val url = school.getBaseUrl().toHttpUrlOrNull() ?: return
            val jar = CourseApiClient.getInstance().getCookieJar()
            val accountStorageKey = binding.requestContext.normalizedAccountStorageKey
            val cookies = jar.loadForRequest(url, accountStorageKey)
            CourseApiClient.getInstance().clearCookies(accountStorageKey)
            for (c in cookies) {
                if (c.name == "JSESSIONID") {
                    jar.saveFromResponse(url, listOf(c), accountStorageKey)
                }
            }
            Log.d(TAG, "Cleared route cookie, kept JSESSIONID only")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear route cookie: ${e.message}")
        }
    }

    private fun activateSession(
        binding: LoginBinding,
        school: SchoolConfig,
        cookie: String,
        callback: PasswordLoginCallback
    ) {
        // GET index_initMenu.html 激活Session（技术文档2.5节）
        // 使用CookieJar自动管理Cookie，确保同一会话
        val url = school.getFullBasePath() + "/xtgl/index_initMenu.html"
        Log.d(TAG, "Activating session: $url")
        // 先确保CookieJar中有正确的cookie
        if (!CourseApiClient.getInstance().setLegacyCookie(
                school,
                cookie,
                binding.requestContext.normalizedAccountStorageKey
            )) {
            callback.onError("当前学校不支持扁平 Cookie 会话")
            return
        }
        val request = CourseApiClient.getInstance().newTaggedRequestBuilder(binding.requestContext)
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36")
            .get()
            .build()
        CourseApiClient.getInstance().getClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!isBindingCurrent(binding)) return
                Log.w(TAG, "Session activation failed (non-critical): ${e.message}")
                callback.onSuccess(cookie)
            }
            override fun onResponse(call: Call, resp: Response) {
                try {
                    if (!isBindingCurrent(binding)) return
                    Log.d(TAG, "Session activation: code=${resp.code}, url=${resp.request.url}")
                    callback.onSuccess(cookie)
                } finally {
                    resp.close()
                }
            }
        })
    }

    private fun fetchLoginPage(binding: LoginBinding, callback: PasswordLoginCallback) {
        val school = currentSchool ?: return callback.onError("学校配置丢失")
        Log.d(TAG, "Fetching login page...")
        CourseApiClient.getInstance().getLoginPage(school, binding.requestContext, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!isBindingCurrent(binding)) return
                Log.e(TAG, "Login page fetch failed: ${e.message}")
                callback.onError("网络错误，无法连接教务系统: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!isBindingCurrent(binding)) {
                    response.close()
                    return
                }
                try {
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Login page HTTP error: ${response.code}")
                        callback.onError("教务系统返回错误 (${response.code})，请检查学校配置或网络")
                        return
                    }
                    val html = response.body?.string() ?: ""
                    Log.d(TAG, "Login page HTML length: ${html.length}")
                    if (html.length < 100) {
                        callback.onError("登录页内容异常，请检查学校域名配置")
                        return
                    }
                    parseHiddenFields(html)
                    if (csrftoken.isEmpty()) {
                        callback.onError("无法提取登录令牌，该学校可能不支持密码登录，请使用 Cookie 模式")
                        return
                    }
                    // 先尝试无验证码登录，服务器会在需要时通过返回HTML提示
                    Log.d(TAG, "Fetching public key and attempting login without captcha...")
                    fetchPublicKey(binding, callback)
                } catch (e: Exception) {
                    if (isBindingCurrent(binding)) {
                        Log.e(TAG, "Login page parse error: ${e.message}")
                        callback.onError("解析登录页失败: ${e.message}")
                    }
                } finally {
                    response.close()
                }
            }
        })
    }

    private fun parseHiddenFields(html: String) {
        hiddenFields.clear()
        val pattern = Pattern.compile(
            """<input[^>]+type\s*=\s*["']hidden["'][^>]*>""",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = pattern.matcher(html)
        while (matcher.find()) {
            val tag = matcher.group()
            val name = extractAttr(tag, "name")
            val value = extractAttr(tag, "value")
            if (name.isNotEmpty()) {
                hiddenFields[name] = value
            }
        }
        csrftoken = hiddenFields["csrftoken"] ?: ""
        // CSRF tokens are credentials; keep only presence/count diagnostics.
        Log.d(TAG, "Parsed ${hiddenFields.size} hidden fields, csrftokenPresent=${csrftoken.isNotEmpty()}")
    }

    private fun extractAttr(tag: String, attr: String): String {
        val pattern = Pattern.compile("""$attr\s*=\s*["']([^"']*)["']""", Pattern.CASE_INSENSITIVE)
        val m = pattern.matcher(tag)
        return if (m.find()) m.group(1) ?: "" else ""
    }

    private fun fetchPublicKey(binding: LoginBinding, callback: PasswordLoginCallback) {
        val school = currentSchool ?: return callback.onError("学校配置丢失")
        CourseApiClient.getInstance().getPublicKey(school, binding.requestContext, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!isBindingCurrent(binding)) return
                callback.onError("获取公钥失败，该学校可能不支持密码登录: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!isBindingCurrent(binding)) {
                    response.close()
                    return
                }
                try {
                    if (!response.isSuccessful) {
                        callback.onError("该学校教务系统不支持密码登录（公钥接口返回 ${response.code}），请使用 Cookie 模式")
                        return
                    }
                    val body = response.body?.string() ?: ""
                    if (body.contains("<html") || body.contains("<!DOCTYPE")) {
                        callback.onError("该学校教务系统不支持密码登录，请使用 Cookie 模式")
                        return
                    }
                    val json = JSONObject(body)
                    modulus = json.optString("modulus", "")
                    exponent = json.optString("exponent", "")
                    if (modulus.isEmpty() || exponent.isEmpty()) {
                        callback.onError("该学校密码加密方式不支持，请使用 Cookie 模式")
                        return
                    }
                    encryptedPassword = RSAUtils.encrypt(modulus, exponent, currentPassword)
                    postLogin(binding, school, "", callback)
                } catch (e: Exception) {
                    if (isBindingCurrent(binding)) {
                        callback.onError("密码加密失败: ${e.message}")
                    }
                } finally {
                    response.close()
                }
            }
        })
    }

    private fun postLogin(
        binding: LoginBinding,
        school: SchoolConfig,
        captcha: String,
        callback: PasswordLoginCallback
    ) {
        val formBuilder = FormBody.Builder()
            .add("csrftoken", csrftoken)
            .add("yhm", currentUsername)
            .add("mm", encryptedPassword)

        // 添加所有 hidden fields
        for ((k, v) in hiddenFields) {
            if (k != "csrftoken" && k != "yhm" && k != "mm") {
                formBuilder.add(k, v)
            }
        }

        if (captcha.isNotEmpty()) {
            formBuilder.add("yzm", captcha)
        }

        CourseApiClient.getInstance().submitLogin(school, formBuilder.build(), binding.requestContext, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!isBindingCurrent(binding)) return
                callback.onError("登录请求失败: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!isBindingCurrent(binding)) {
                    response.close()
                    return
                }
                try {
                    val code = response.code
                    // 记录响应信息用于调试
                    Log.d(TAG, "Login response: code=$code, url=${response.request.url}")
                    val setCookies = response.headers("Set-Cookie")
                    if (setCookies.isNotEmpty()) {
                        for (sc in setCookies) {
                            Log.d(TAG, "Login Set-Cookie: ${sc.substringBefore('=')}=<redacted>")
                        }
                    }

                    if (code == 302) {
                        // 登录成功 - 从响应头和CookieJar合并提取完整Cookie
                        val cookieJar = CourseApiClient.getInstance().getCookieString(
                            school,
                            binding.requestContext.normalizedAccountStorageKey
                        )
                        val headerCookies = setCookies.joinToString("; ") { it.split(";")[0].trim() }
                        // 合并：优先使用响应头中的新Cookie，补充CookieJar中的
                        val allCookies = mutableMapOf<String, String>()
                        // 先从CookieJar加载
                        for (part in cookieJar.split(";")) {
                            val pair = part.trim().split("=", limit = 2)
                            if (pair.size == 2 && pair[0].isNotEmpty()) {
                                allCookies[pair[0].trim()] = pair[1].trim()
                            }
                        }
                        // 再从302响应头覆盖（这些是最新的）
                        for (part in headerCookies.split(";")) {
                            val pair = part.trim().split("=", limit = 2)
                            if (pair.size == 2 && pair[0].isNotEmpty()) {
                                allCookies[pair[0].trim()] = pair[1].trim()
                            }
                        }
                        // 移除 rememberMe=deleteMe（Shiro过期标记，会导致服务器问题）
                        allCookies.remove("rememberMe")
                        val cookie = allCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                        Log.d(TAG, "Login success, cookie keys: ${allCookies.keys}")
                        // 将Cookie写入CookieJar，确保后续验证请求使用同一会话
                        if (!CourseApiClient.getInstance().setLegacyCookie(
                                school,
                                cookie,
                                binding.requestContext.normalizedAccountStorageKey
                            )) {
                            callback.onError("当前学校不支持扁平 Cookie 会话")
                            return
                        }
                        if (isBindingCurrent(binding)) callback.onSuccess(cookie)
                    } else {
                        val body = response.body?.string() ?: ""
                        Log.d(TAG, "Login failed: code=$code, body length=${body.length}, captchaProvided=${captcha.isNotEmpty()}")
                        // 提取 tips 区域的具体错误信息
                        val errPattern = Pattern.compile("""id\s*=\s*["']tips["'][^>]*>([^<]+)""")
                        val errMatcher = errPattern.matcher(body)
                        val tipsMsg = if (errMatcher.find()) errMatcher.group(1)?.trim() ?: "" else ""
                        Log.d(TAG, "Tips message: '$tipsMsg'")

                        when {
                            // 密码错误（优先级最高，避免被"验证码"关键字误匹配）
                            tipsMsg.contains("用户名或密码不正确") ||
                            body.contains("用户名或密码不正确") -> {
                                callback.onInvalidCredentials()
                            }
                            // 账号锁定
                            tipsMsg.contains("被锁定") || body.contains("账户被锁定") ||
                            body.contains("账号已锁定") -> {
                                callback.onError("账号已被锁定，请稍后再试或联系教务处")
                            }
                            // 验证码错误（已提交验证码但验证码不对）
                            captcha.isNotEmpty() && (
                                tipsMsg.contains("验证码") ||
                                body.contains("验证码错误") ||
                                body.contains("验证码不正确")
                            ) -> {
                                Log.d(TAG, "Captcha invalid (captchaProvided=true)")
                                callback.onCaptchaInvalid()
                            }
                            // 服务器要求验证码（首次登录未提交验证码时）
                            captcha.isEmpty() && body.contains("验证码") -> {
                                Log.d(TAG, "Server requires captcha, fetching image...")
                            fetchCaptchaImage(binding, school, callback)
                            }
                            // 其他错误
                            else -> {
                                val msg = if (tipsMsg.isNotEmpty()) tipsMsg
                                    else "登录失败，请检查账号密码或稍后重试"
                                callback.onError(msg)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isBindingCurrent(binding)) {
                        callback.onError("处理登录响应异常: ${e.message}")
                    }
                } finally {
                    response.close()
                }
            }
        })
    }
}
