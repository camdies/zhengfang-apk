package com.tyust.course.session

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountCookieJarTest {
    @Test
    fun hostOnlyCookieOnlyMatchesItsExactHost() {
        val jar = AccountCookieJar()
        val hostUrl = "https://jwxt.scnu.edu.cn/academic".toHttpUrl()
        jar.saveFromResponse(
            hostUrl,
            listOf(cookie("HOST_ONLY", "host-only", host = "jwxt.scnu.edu.cn", hostOnly = true)),
            "account-a"
        )

        assertEquals(setOf("HOST_ONLY"), names(jar.loadForRequest(hostUrl, "account-a")))
        assertTrue(
            jar.loadForRequest("https://sub.jwxt.scnu.edu.cn/academic".toHttpUrl(), "account-a").isEmpty()
        )
        assertTrue(
            jar.loadForRequest("https://portal.scnu.edu.cn/academic".toHttpUrl(), "account-a").isEmpty()
        )
    }

    @Test
    fun parentDomainCookieMatchesLegalSubdomainsButNotSuffixLookalikes() {
        val jar = AccountCookieJar()
        val origin = "https://sso.scnu.edu.cn/login".toHttpUrl()
        jar.saveFromResponse(
            origin,
            listOf(cookie("PARENT_DOMAIN", "parent", host = "scnu.edu.cn", hostOnly = false)),
            "account-a"
        )

        assertEquals(
            setOf("PARENT_DOMAIN"),
            names(jar.loadForRequest("https://jwxt.scnu.edu.cn/academic".toHttpUrl(), "account-a"))
        )
        assertEquals(
            setOf("PARENT_DOMAIN"),
            names(jar.loadForRequest("https://portal.scnu.edu.cn/academic".toHttpUrl(), "account-a"))
        )
        assertTrue(
            jar.loadForRequest("https://not-scnu.edu.cn/academic".toHttpUrl(), "account-a").isEmpty()
        )
        assertTrue(
            jar.loadForRequest("https://scnu.edu.cn.example.invalid/academic".toHttpUrl(), "account-a").isEmpty()
        )
    }

    @Test
    fun honorsPathAndSecureAttributesAndKeepsAccountsIsolated() {
        val jar = AccountCookieJar()
        val securePathUrl = "https://jwxt.scnu.edu.cn/academic/grades".toHttpUrl()
        jar.saveFromResponse(
            securePathUrl,
            listOf(cookie("SCOPED", "scoped", host = "jwxt.scnu.edu.cn", path = "/academic", secure = true)),
            "account-a"
        )
        jar.saveFromResponse(
            securePathUrl,
            listOf(cookie("SCOPED", "other-account", host = "jwxt.scnu.edu.cn", path = "/academic", secure = true)),
            "account-b"
        )

        assertEquals(setOf("SCOPED"), names(jar.loadForRequest(securePathUrl, "account-a")))
        assertTrue(
            jar.loadForRequest("https://jwxt.scnu.edu.cn/academics".toHttpUrl(), "account-a").isEmpty()
        )
        assertTrue(
            jar.loadForRequest("http://jwxt.scnu.edu.cn/academic/grades".toHttpUrl(), "account-a").isEmpty()
        )
        assertEquals(
            "other-account",
            jar.loadForRequest(securePathUrl, "account-b").single().value
        )
    }

    private fun names(cookies: List<Cookie>): Set<String> = cookies.map { it.name }.toSet()

    private fun cookie(
        name: String,
        value: String,
        host: String,
        hostOnly: Boolean = true,
        path: String = "/",
        secure: Boolean = true
    ): Cookie {
        val builder = Cookie.Builder()
            .name(name)
            .value(value)
            .path(path)
        if (hostOnly) builder.hostOnlyDomain(host) else builder.domain(host)
        if (secure) builder.secure()
        return builder.build()
    }
}
