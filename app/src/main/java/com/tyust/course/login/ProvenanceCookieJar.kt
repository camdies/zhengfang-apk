package com.tyust.course.login

import com.tyust.course.session.AudienceRole
import com.tyust.course.session.PersistedCookie
import com.tyust.course.session.SchoolSessionScope
import com.tyust.course.session.SessionArtifact
import com.tyust.course.session.SessionAudience
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * One-login-attempt CookieJar retaining the host which set each Cookie.
 *
 * It is intentionally not an account runtime jar. It exports only cookies
 * that RFC-match an explicitly approved SERVICE_REQUIRED audience.
 */
class ProvenanceCookieJar : CookieJar {
    private data class RecordedCookie(val cookie: Cookie, val setByHost: String)

    private val lock = Any()
    private val cookies = mutableListOf<RecordedCookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            cookies.forEach { incoming ->
                this.cookies.removeAll {
                    it.cookie.name == incoming.name &&
                        it.cookie.domain == incoming.domain &&
                        it.cookie.path == incoming.path &&
                        it.cookie.hostOnly == incoming.hostOnly
                }
                if (incoming.expiresAt > System.currentTimeMillis()) {
                    this.cookies += RecordedCookie(incoming, SchoolSessionScope.normalizeHost(url.host))
                }
            }
            purgeExpired()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        purgeExpired()
        cookies.map(RecordedCookie::cookie).filter { it.matches(url) }
    }

    fun exportRfcBundle(
        scope: SchoolSessionScope,
        audiences: List<SessionAudience>,
        createdAtEpochMs: Long = System.currentTimeMillis()
    ): SessionArtifact.RfcCookieBundle {
        val approvedUrls = audiences
            .filter { it.role == AudienceRole.SERVICE_REQUIRED }
            .mapNotNull(SessionAudience::parsedUrl)
        val exported = synchronized(lock) {
            purgeExpired()
            cookies
                .filter { recorded -> approvedUrls.any(recorded.cookie::matches) }
                .map { PersistedCookie.fromCookie(it.cookie, it.setByHost) }
        }
        return SessionArtifact.RfcCookieBundle(
            schoolScope = scope,
            cookies = exported,
            serviceAudiences = audiences,
            createdAtEpochMs = createdAtEpochMs
        )
    }

    fun snapshot(): List<PersistedCookie> = synchronized(lock) {
        purgeExpired()
        cookies.map { PersistedCookie.fromCookie(it.cookie, it.setByHost) }
    }

    fun clear() = synchronized(lock) {
        cookies.clear()
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.cookie.expiresAt <= now }
    }
}
