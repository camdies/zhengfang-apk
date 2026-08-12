package com.tyust.course.session

import org.json.JSONArray
import org.json.JSONObject

/**
 * Codec for a complete account artifact. Callers must never log the encoded
 * payload because it contains Cookie values.
 */
object SessionArtifactCodec {
    @JvmStatic
    fun encode(artifact: SessionArtifact): String {
        val root = JSONObject()
        root.put("schemaVersion", artifact.schemaVersion)
        root.put("createdAtEpochMs", artifact.createdAtEpochMs)
        root.put("scope", encodeScope(artifact.schoolScope))
        when (artifact) {
            is SessionArtifact.LegacyCookieHeader -> {
                root.put("type", "legacy")
                root.put("header", artifact.header)
            }
            is SessionArtifact.RfcCookieBundle -> {
                root.put("type", "rfc")
                val cookies = JSONArray()
                artifact.cookies.forEach { cookies.put(encodeCookie(it)) }
                root.put("cookies", cookies)
                val audiences = JSONArray()
                artifact.serviceAudiences.forEach {
                    audiences.put(JSONObject().apply {
                        put("url", it.url)
                        put("role", it.role.name)
                    })
                }
                root.put("audiences", audiences)
            }
        }
        return root.toString()
    }

    @JvmStatic
    fun decode(payload: String?): SessionArtifact? {
        if (payload.isNullOrBlank()) return null
        return try {
            val root = JSONObject(payload)
            val schema = root.optInt("schemaVersion", -1)
            if (schema != SessionArtifact.CURRENT_SCHEMA_VERSION) return null
            val scope = decodeScope(root.optJSONObject("scope") ?: return null) ?: return null
            val created = root.optLong("createdAtEpochMs", 0L)
            when (root.optString("type")) {
                "legacy" -> {
                    val header = root.optString("header", "")
                    if (header.isBlank()) null else SessionArtifact.LegacyCookieHeader(
                        schoolScope = scope,
                        header = header,
                        createdAtEpochMs = created,
                        schemaVersion = schema
                    )
                }
                "rfc" -> {
                    val cookies = root.optJSONArray("cookies") ?: return null
                    val parsedCookies = buildList {
                        for (index in 0 until cookies.length()) {
                            decodeCookie(cookies.optJSONObject(index))?.let(::add)
                        }
                    }
                    val audiences = root.optJSONArray("audiences") ?: JSONArray()
                    val parsedAudiences = buildList {
                        for (index in 0 until audiences.length()) {
                            val item = audiences.optJSONObject(index) ?: continue
                            val url = item.optString("url", "")
                            val role = runCatching {
                                AudienceRole.valueOf(item.optString("role", AudienceRole.UNKNOWN.name))
                            }.getOrDefault(AudienceRole.UNKNOWN)
                            if (url.isNotBlank()) add(SessionAudience(url, role))
                        }
                    }
                    if (parsedCookies.isEmpty()) null else SessionArtifact.RfcCookieBundle(
                        schoolScope = scope,
                        cookies = parsedCookies,
                        serviceAudiences = parsedAudiences,
                        createdAtEpochMs = created,
                        schemaVersion = schema
                    )
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeScope(scope: SchoolSessionScope) = JSONObject().apply {
        put("schoolId", scope.schoolId)
        put("scheme", scope.scheme)
        put("host", scope.host)
        put("basePath", scope.basePath)
    }

    private fun decodeScope(value: JSONObject): SchoolSessionScope? {
        val schoolId = value.optString("schoolId", "")
        val scheme = value.optString("scheme", "")
        val host = SchoolSessionScope.normalizeHost(value.optString("host", ""))
        val basePath = SchoolSessionScope.normalizeBasePath(value.optString("basePath", ""))
        return if (schoolId.isBlank() || scheme.isBlank() || host.isBlank()) null
        else SchoolSessionScope(schoolId, scheme.lowercase(), host, basePath)
    }

    private fun encodeCookie(cookie: PersistedCookie) = JSONObject().apply {
        put("name", cookie.name)
        put("value", cookie.value)
        put("domain", cookie.domain)
        put("path", cookie.path)
        put("expiresAt", cookie.expiresAt)
        put("secure", cookie.secure)
        put("httpOnly", cookie.httpOnly)
        put("hostOnly", cookie.hostOnly)
        put("persistent", cookie.persistent)
        put("setByHost", cookie.setByHost)
    }

    private fun decodeCookie(value: JSONObject?): PersistedCookie? {
        if (value == null) return null
        val name = value.optString("name", "")
        val raw = value.optString("value", "")
        val domain = SchoolSessionScope.normalizeHost(value.optString("domain", ""))
        if (name.isBlank() || raw.isBlank() || domain.isBlank()) return null
        return PersistedCookie(
            name = name,
            value = raw,
            domain = domain,
            path = value.optString("path", "/").ifBlank { "/" },
            expiresAt = value.optLong("expiresAt", Long.MAX_VALUE),
            secure = value.optBoolean("secure", false),
            httpOnly = value.optBoolean("httpOnly", false),
            hostOnly = value.optBoolean("hostOnly", false),
            persistent = value.optBoolean("persistent", false),
            setByHost = SchoolSessionScope.normalizeHost(value.optString("setByHost", domain))
        )
    }
}
