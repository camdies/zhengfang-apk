package com.tyust.course.login

import com.tyust.course.model.SchoolConfig
import com.tyust.course.session.SessionArtifact
import com.tyust.course.session.SessionInstallTarget

/** Login abstraction that never flattens an RFC bundle into a String. */
interface SessionLoginGateway {
    fun login(
        school: SchoolConfig,
        username: String,
        password: String,
        target: SessionInstallTarget,
        callback: SessionLoginCallback
    )

    fun submitCaptcha(captchaCode: String, callback: SessionLoginCallback)

    fun refreshCaptcha(callback: (ByteArray?) -> Unit)

    fun clearSensitiveState()
}

interface SessionLoginCallback {
    fun onSuccess(artifact: SessionArtifact)
    fun onCaptchaRequired(imageBytes: ByteArray)
    fun onCaptchaInvalid()
    fun onInvalidCredentials()
    fun onError(message: String)
}

/**
 * Compatibility adapter for Zhengfang/TYUST password managers.  A successful
 * legacy String is wrapped while still inside its explicit school scope.
 */
class LegacyPasswordGatewayAdapter(
    private val delegate: PasswordLoginGateway
) : SessionLoginGateway {
    private var target: SessionInstallTarget? = null

    override fun login(
        school: SchoolConfig,
        username: String,
        password: String,
        target: SessionInstallTarget,
        callback: SessionLoginCallback
    ) {
        this.target = target
        (delegate as? SessionTargetAwarePasswordLoginGateway)?.bindSessionInstallTarget(target)
        delegate.login(school, username, password, callback.asLegacy(target))
    }

    override fun submitCaptcha(captchaCode: String, callback: SessionLoginCallback) {
        val currentTarget = target
        if (currentTarget == null) {
            callback.onError("登录会话已失效，请重新登录")
            return
        }
        delegate.submitCaptcha(captchaCode, callback.asLegacy(currentTarget))
    }

    override fun refreshCaptcha(callback: (ByteArray?) -> Unit) = delegate.refreshCaptcha(callback)

    override fun clearSensitiveState() {
        target = null
        delegate.clearSensitiveState()
    }

    private fun SessionLoginCallback.asLegacy(target: SessionInstallTarget): PasswordLoginCallback =
        object : PasswordLoginCallback {
            override fun onSuccess(cookie: String) {
                this@asLegacy.onSuccess(
                    SessionArtifact.LegacyCookieHeader(
                        schoolScope = target.schoolScope,
                        header = cookie
                    )
                )
            }

            override fun onCaptchaRequired(imageBytes: ByteArray) = this@asLegacy.onCaptchaRequired(imageBytes)
            override fun onCaptchaInvalid() = this@asLegacy.onCaptchaInvalid()
            override fun onInvalidCredentials() = this@asLegacy.onInvalidCredentials()
            override fun onError(message: String) = this@asLegacy.onError(message)
        }
}

object SessionLoginGatewayFactory {
    @JvmStatic
    fun create(school: SchoolConfig): SessionLoginGateway =
        if (com.tyust.course.session.SchoolSessionScope.isCanonicalScnu(school)) {
            ScnuSsoLoginManager()
        } else {
            LegacyPasswordGatewayAdapter(PasswordLoginGatewayFactory.create(school))
        }
}
