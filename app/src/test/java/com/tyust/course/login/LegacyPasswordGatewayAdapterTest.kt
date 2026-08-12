package com.tyust.course.login

import com.tyust.course.model.SchoolConfig
import com.tyust.course.session.SchoolSessionScope
import com.tyust.course.session.SessionArtifact
import com.tyust.course.session.SessionInstallTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPasswordGatewayAdapterTest {
    @Test
    fun bindsTargetBeforeStartingTheLegacyPasswordFlow() {
        val school = SchoolConfig("other", "Other", "jw.example.edu.cn", "https")
        val target = SessionInstallTarget.create(
            accountStorageKey = "other:student",
            schoolScope = SchoolSessionScope.fromSchool(school),
            expectedGeneration = 7L
        )
        val delegate = TargetAwareFakeGateway()
        var received: SessionArtifact? = null

        LegacyPasswordGatewayAdapter(delegate).login(
            school = school,
            username = "student",
            password = "secret",
            target = target,
            callback = object : SessionLoginCallback {
                override fun onSuccess(artifact: SessionArtifact) {
                    received = artifact
                }

                override fun onCaptchaRequired(imageBytes: ByteArray) = Unit
                override fun onCaptchaInvalid() = Unit
                override fun onInvalidCredentials() = Unit
                override fun onError(message: String) = throw AssertionError(message)
            }
        )

        assertSame(target, delegate.targetSeenByLogin)
        val artifact = received as? SessionArtifact.LegacyCookieHeader
        assertTrue(artifact != null)
        assertEquals(target.schoolScope, artifact?.schoolScope)
        assertEquals("JSESSIONID=opaque", artifact?.header)
    }

    private class TargetAwareFakeGateway : PasswordLoginGateway, SessionTargetAwarePasswordLoginGateway {
        var boundTarget: SessionInstallTarget? = null
        var targetSeenByLogin: SessionInstallTarget? = null

        override fun bindSessionInstallTarget(target: SessionInstallTarget) {
            boundTarget = target
        }

        override fun login(
            school: SchoolConfig,
            username: String,
            password: String,
            callback: PasswordLoginCallback
        ) {
            targetSeenByLogin = boundTarget
            callback.onSuccess("JSESSIONID=opaque")
        }

        override fun submitCaptcha(captchaCode: String, callback: PasswordLoginCallback) = Unit
        override fun refreshCaptcha(callback: (ByteArray?) -> Unit) = callback(null)
        override fun clearSensitiveState() = Unit
    }
}
