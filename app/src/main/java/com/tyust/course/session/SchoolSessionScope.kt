package com.tyust.course.session

import com.tyust.course.model.SchoolConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Immutable, normalized identity of the school origin a session belongs to.
 *
 * This deliberately keeps basePath independent. A root path ("/") is not
 * silently treated as an empty basePath, because canonical SCNU requires "".
 */
data class SchoolSessionScope(
    val schoolId: String,
    val scheme: String,
    val host: String,
    val basePath: String
) {
    val origin: String
        get() = scheme + "://" + host

    fun matches(url: HttpUrl): Boolean {
        if (!url.isHttps && scheme != "http") return false
        val pathMatches = when {
            basePath.isEmpty() -> true
            basePath == "/" -> true
            else -> url.encodedPath == basePath || url.encodedPath.startsWith("$basePath/")
        }
        return url.scheme.equals(scheme, ignoreCase = true) &&
            url.host.equals(host, ignoreCase = true) && pathMatches
    }

    fun matchesSchool(school: SchoolConfig?): Boolean =
        school != null && this == fromSchool(school)

    val isCanonicalScnu: Boolean
        get() = scheme == "https" && host == CANONICAL_SCNU_HOST && basePath.isEmpty()

    companion object {
        const val CANONICAL_SCNU_HOST = "jwxt.scnu.edu.cn"

        @JvmStatic
        fun fromSchool(school: SchoolConfig): SchoolSessionScope = SchoolSessionScope(
            schoolId = school.id.orEmpty(),
            scheme = school.protocol.orEmpty().trim().lowercase().ifEmpty { "https" },
            host = normalizeHost(school.domain),
            basePath = normalizeBasePath(school.basePath)
        )

        @JvmStatic
        fun isCanonicalScnu(school: SchoolConfig?): Boolean =
            school != null && fromSchool(school).isCanonicalScnu

        @JvmStatic
        fun fromUrl(schoolId: String, url: String, basePath: String = ""): SchoolSessionScope? {
            val parsed = url.toHttpUrlOrNull() ?: return null
            return SchoolSessionScope(
                schoolId = schoolId,
                scheme = parsed.scheme.lowercase(),
                host = normalizeHost(parsed.host),
                basePath = normalizeBasePath(basePath)
            )
        }

        @JvmStatic
        fun normalizeHost(value: String?): String =
            value.orEmpty().trim().trimEnd('.').lowercase()

        @JvmStatic
        fun normalizeBasePath(value: String?): String {
            val trimmed = value.orEmpty().trim()
            if (trimmed.isEmpty()) return ""
            val rooted = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
            return rooted.trimEnd('/').ifEmpty { "/" }
        }
    }
}
