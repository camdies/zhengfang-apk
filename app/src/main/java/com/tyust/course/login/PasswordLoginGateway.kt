package com.tyust.course.login

import com.tyust.course.model.SchoolConfig
import com.tyust.course.session.SchoolSessionScope
import com.tyust.course.session.SessionInstallTarget

interface PasswordLoginGateway {
    fun login(
        school: SchoolConfig,
        username: String,
        password: String,
        callback: PasswordLoginCallback
    )

    fun submitCaptcha(captchaCode: String, callback: PasswordLoginCallback)

    fun refreshCaptcha(callback: (ByteArray?) -> Unit)

    fun clearSensitiveState()
}

/**
 * Optional bridge for legacy gateways which use CourseApiClient's shared
 * account-scoped runtime.  The typed coordinator supplies the target before
 * login so every network request and CookieJar operation can remain bound to
 * that account even if the foreground account changes during the attempt.
 */
interface SessionTargetAwarePasswordLoginGateway {
    fun bindSessionInstallTarget(target: SessionInstallTarget)
}

object PasswordLoginGatewayFactory {
    fun create(school: SchoolConfig): PasswordLoginGateway =
        when {
            school.id == TYUST_SCHOOL_ID -> TyustSsoLoginManager()
            else -> PasswordLoginManager()
        }

    private const val TYUST_SCHOOL_ID = "tyust"
}
