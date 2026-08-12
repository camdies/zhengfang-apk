package com.tyust.course.login

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScnuSsoProtocolTest {
    @Test
    fun initialRejectsMissingEvidenceProfileWithoutStartingNetworkProtocol() {
        val transition = ScnuSsoProtocol.initial(null)

        assertTrue(transition is ScnuSsoProtocol.Transition.Rejected)
        assertEquals(
            "ProtocolNotVerified",
            (transition as ScnuSsoProtocol.Transition.Rejected).reason
        )
    }

    @Test
    fun redirectsMustUseAnExplicitHttpsOriginAllowlist() {
        val entry = "https://sso.scnu.edu.cn/entry".toHttpUrl()
        val callback = "https://jwxt.scnu.edu.cn/callback".toHttpUrl()
        val profile = ScnuSsoProtocol.EvidenceProfile(
            allowedOrigins = setOf("https://sso.scnu.edu.cn", "https://jwxt.scnu.edu.cn"),
            entryUrl = entry,
            teachingCallbackUrl = callback
        )

        assertTrue(ScnuSsoProtocol.initial(profile) is ScnuSsoProtocol.Transition.Next)
        assertEquals(
            ScnuSsoProtocol.State.REACH_TEACHING_CALLBACK,
            (ScnuSsoProtocol.followRedirect(profile, callback) as ScnuSsoProtocol.Transition.Next).state
        )
        assertTrue(
            ScnuSsoProtocol.followRedirect(
                profile,
                "http://jwxt.scnu.edu.cn/callback".toHttpUrl()
            ) is ScnuSsoProtocol.Transition.Rejected
        )
    }

    @Test
    fun nonStandardCookieHandoffIsAlwaysRejected() {
        val transition = ScnuSsoProtocol.rejectNonStandardCookieHandoff()

        assertEquals(
            "UNSUPPORTED_NON_STANDARD_COOKIE_HANDOFF",
            (transition as ScnuSsoProtocol.Transition.Rejected).reason
        )
    }
}
