package com.tyust.course.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionArtifactCodecTest {
    @Test
    fun roundTripsAllRfcCookieAttributesAndAudiences() {
        val artifact = SessionArtifact.RfcCookieBundle(
            schoolScope = testScope(),
            cookies = listOf(
                PersistedCookie(
                    name = "SESSION",
                    value = "test-cookie-value",
                    domain = "jwxt.scnu.edu.cn",
                    path = "/academic",
                    expiresAt = 1_900_000_000_000L,
                    secure = true,
                    httpOnly = true,
                    hostOnly = true,
                    persistent = true,
                    setByHost = "jwxt.scnu.edu.cn"
                )
            ),
            serviceAudiences = listOf(
                SessionAudience("https://jwxt.scnu.edu.cn/academic", AudienceRole.SERVICE_REQUIRED),
                SessionAudience("https://sso.scnu.edu.cn/login", AudienceRole.SSO_TRANSIENT)
            ),
            createdAtEpochMs = 1_700_000_000_000L
        )

        val restored = SessionArtifactCodec.decode(SessionArtifactCodec.encode(artifact))

        assertEquals(artifact, restored)
    }

    @Test
    fun rejectsUnknownSchemaAndMalformedPayloadWithoutThrowing() {
        assertNull(SessionArtifactCodec.decode(null))
        assertNull(SessionArtifactCodec.decode("not-json"))
        assertNull(
            SessionArtifactCodec.decode(
                """{"schemaVersion":99,"type":"legacy","header":"test-cookie-value"}"""
            )
        )
        assertNull(
            SessionArtifactCodec.decode(
                """{"schemaVersion":1,"type":"rfc","scope":{}}"""
            )
        )
    }

    @Test
    fun debugDescriptionsNeverExposeCookieValues() {
        val value = "test-cookie-value"
        val legacy = SessionArtifact.LegacyCookieHeader(testScope(), "SESSION=$value")
        val persisted = PersistedCookie(
            name = "SESSION",
            value = value,
            domain = "jwxt.scnu.edu.cn",
            path = "/",
            expiresAt = Long.MAX_VALUE,
            secure = true,
            httpOnly = true,
            hostOnly = true,
            persistent = false,
            setByHost = "jwxt.scnu.edu.cn"
        )
        val bundle = SessionArtifact.RfcCookieBundle(
            schoolScope = testScope(),
            cookies = listOf(persisted),
            serviceAudiences = listOf(
                SessionAudience(
                    "https://jwxt.scnu.edu.cn/academic?ticket=$value",
                    AudienceRole.SERVICE_REQUIRED
                )
            )
        )

        assertFalse(legacy.redactedDescription().contains(value))
        assertFalse(bundle.redactedDescription().contains(value))
        assertFalse(persisted.redactedDescription().contains(value))
        assertFalse(legacy.toString().contains(value))
        assertFalse(bundle.toString().contains(value))
        assertFalse(persisted.toString().contains(value))
        assertFalse(bundle.serviceAudiences.single().toString().contains(value))
        assertTrue(legacy.toString().contains("jwxt.scnu.edu.cn"))
        assertTrue(bundle.serviceAudiences.single().toString().contains(AudienceRole.SERVICE_REQUIRED.name))
    }

    private fun testScope() = SchoolSessionScope(
        schoolId = "scnu-test",
        scheme = "https",
        host = "jwxt.scnu.edu.cn",
        basePath = ""
    )
}
