package com.tyust.course.login

import com.tyust.course.session.AudienceRole
import com.tyust.course.session.SchoolSessionScope
import com.tyust.course.session.SessionAudience
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvenanceCookieJarTest {
    @Test
    fun exportsOnlyCookiesThatRfcMatchServiceRequiredAudiences() {
        val jar = ProvenanceCookieJar()
        val ssoUrl = "https://sso.scnu.edu.cn/login".toHttpUrl()
        val teachingUrl = "https://jwxt.scnu.edu.cn/academic".toHttpUrl()

        jar.saveFromResponse(
            ssoUrl,
            listOf(
                cookie("SSO_ONLY", "sso", "sso.scnu.edu.cn", hostOnly = true),
                cookie("PARENT", "parent", "scnu.edu.cn", hostOnly = false)
            )
        )
        jar.saveFromResponse(
            teachingUrl,
            listOf(cookie("TEACHING", "teaching", "jwxt.scnu.edu.cn", hostOnly = true))
        )

        val bundle = jar.exportRfcBundle(
            scope = canonicalScope(),
            audiences = listOf(
                SessionAudience(ssoUrl.toString(), AudienceRole.SSO_TRANSIENT),
                SessionAudience(teachingUrl.toString(), AudienceRole.SERVICE_REQUIRED)
            )
        )

        assertEquals(setOf("PARENT", "TEACHING"), bundle.cookies.map { it.name }.toSet())
        assertTrue(bundle.cookies.none { it.name == "SSO_ONLY" })
        assertEquals(
            "jwxt.scnu.edu.cn",
            bundle.cookies.single { it.name == "TEACHING" }.setByHost
        )
    }

    @Test
    fun supportsMultipleExplicitServiceAudiencesAndNeverExportsTransientOnlyAudience() {
        val jar = ProvenanceCookieJar()
        val firstService = "https://jwxt.scnu.edu.cn/academic".toHttpUrl()
        val secondService = "https://portal.scnu.edu.cn/profile".toHttpUrl()
        jar.saveFromResponse(
            firstService,
            listOf(cookie("JWXT", "jwxt", "jwxt.scnu.edu.cn", hostOnly = true))
        )
        jar.saveFromResponse(
            secondService,
            listOf(cookie("PORTAL", "portal", "portal.scnu.edu.cn", hostOnly = true))
        )

        val transientOnly = jar.exportRfcBundle(
            canonicalScope(),
            listOf(SessionAudience(firstService.toString(), AudienceRole.SSO_TRANSIENT))
        )
        assertTrue(transientOnly.cookies.isEmpty())

        val bundle = jar.exportRfcBundle(
            canonicalScope(),
            listOf(
                SessionAudience(firstService.toString(), AudienceRole.SERVICE_REQUIRED),
                SessionAudience(secondService.toString(), AudienceRole.SERVICE_REQUIRED)
            )
        )
        assertEquals(setOf("JWXT", "PORTAL"), bundle.cookies.map { it.name }.toSet())
    }

    private fun canonicalScope() = SchoolSessionScope(
        schoolId = "scnu-test",
        scheme = "https",
        host = "jwxt.scnu.edu.cn",
        basePath = ""
    )

    private fun cookie(name: String, value: String, host: String, hostOnly: Boolean): Cookie {
        val builder = Cookie.Builder()
            .name(name)
            .value(value)
            .path("/")
            .secure()
        if (hostOnly) builder.hostOnlyDomain(host) else builder.domain(host)
        return builder.build()
    }
}
