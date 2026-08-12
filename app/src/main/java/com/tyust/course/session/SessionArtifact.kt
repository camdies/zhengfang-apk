package com.tyust.course.session

import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class AudienceRole {
    SSO_TRANSIENT,
    SERVICE_REQUIRED,
    UNKNOWN
}

data class SessionAudience(
    val url: String,
    val role: AudienceRole
) {
    fun parsedUrl(): HttpUrl? = url.toHttpUrlOrNull()

    fun redactedDescription(): String =
        "SessionAudience(role=" + role + ", url=<redacted>)"

    override fun toString(): String = redactedDescription()
}

data class PersistedCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAt: Long,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
    val persistent: Boolean,
    val setByHost: String
) {
    fun toCookie(): Cookie? = try {
        val builder = Cookie.Builder()
            .name(name)
            .value(value)
            .path(path.ifBlank { "/" })
        if (hostOnly) {
            builder.hostOnlyDomain(domain)
        } else {
            builder.domain(domain)
        }
        if (persistent) builder.expiresAt(expiresAt)
        if (secure) builder.secure()
        if (httpOnly) builder.httpOnly()
        builder.build()
    } catch (_: IllegalArgumentException) {
        null
    }

    fun redactedDescription(): String =
        name + "=<redacted>; domain=" + domain + "; path=" + path +
            "; hostOnly=" + hostOnly + "; setByHost=" + setByHost

    override fun toString(): String = redactedDescription()

    companion object {
        @JvmStatic
        fun fromCookie(cookie: Cookie, setByHost: String): PersistedCookie = PersistedCookie(
            name = cookie.name,
            value = cookie.value,
            domain = cookie.domain,
            path = cookie.path,
            expiresAt = cookie.expiresAt,
            secure = cookie.secure,
            httpOnly = cookie.httpOnly,
            hostOnly = cookie.hostOnly,
            persistent = cookie.persistent,
            setByHost = SchoolSessionScope.normalizeHost(setByHost)
        )
    }
}

sealed interface SessionArtifact {
    val schemaVersion: Int
    val schoolScope: SchoolSessionScope
    val createdAtEpochMs: Long

    data class LegacyCookieHeader(
        override val schoolScope: SchoolSessionScope,
        val header: String,
        override val createdAtEpochMs: Long = System.currentTimeMillis(),
        override val schemaVersion: Int = CURRENT_SCHEMA_VERSION
    ) : SessionArtifact {
        fun redactedDescription(): String =
            "LegacyCookieHeader(scope=" + schoolScope + ", header=<redacted>)"

        override fun toString(): String = redactedDescription()
    }

    data class RfcCookieBundle(
        override val schoolScope: SchoolSessionScope,
        val cookies: List<PersistedCookie>,
        val serviceAudiences: List<SessionAudience>,
        override val createdAtEpochMs: Long = System.currentTimeMillis(),
        override val schemaVersion: Int = CURRENT_SCHEMA_VERSION
    ) : SessionArtifact {
        fun redactedDescription(): String =
            "RfcCookieBundle(scope=" + schoolScope + ", cookieCount=" + cookies.size + ")"

        override fun toString(): String = redactedDescription()

        fun matchesAnyServiceAudience(cookie: PersistedCookie): Boolean {
            val okhttpCookie = cookie.toCookie() ?: return false
            return serviceAudiences
                .asSequence()
                .filter { it.role == AudienceRole.SERVICE_REQUIRED }
                .mapNotNull { it.parsedUrl() }
                .any(okhttpCookie::matches)
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

sealed interface LegacyCookieHeaderResult {
    data class Available(val header: String) : LegacyCookieHeaderResult
    data object UnsupportedSessionMode : LegacyCookieHeaderResult
}

fun SessionArtifact.exportLegacyCookieHeader(): LegacyCookieHeaderResult = when (this) {
    is SessionArtifact.LegacyCookieHeader -> LegacyCookieHeaderResult.Available(header)
    is SessionArtifact.RfcCookieBundle -> LegacyCookieHeaderResult.UnsupportedSessionMode
}
