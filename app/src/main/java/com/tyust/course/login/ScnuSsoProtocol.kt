package com.tyust.course.login

import okhttp3.HttpUrl

/**
 * Protocol-neutral SCNU SSO state machine boundary.
 *
 * It intentionally contains no production URL, form field name, response
 * marker, or Cookie audience.  Those values may only be supplied by a
 * separately reviewed evidence profile after authorized protocol discovery.
 */
object ScnuSsoProtocol {
    enum class State {
        FETCH_ENTRY,
        PARSE_LOGIN_FORM,
        SUBMIT_CREDENTIALS,
        FOLLOW_AUTHORIZED_REDIRECTS,
        REACH_TEACHING_CALLBACK,
        VERIFY_AUTHENTICATED_EVIDENCE,
        EXPORT_RFC_BUNDLE,
        COMPLETED,
        FAILED
    }

    data class EvidenceProfile(
        val allowedOrigins: Set<String>,
        val entryUrl: HttpUrl?,
        val teachingCallbackUrl: HttpUrl?
    ) {
        fun allows(url: HttpUrl): Boolean = originOf(url) in allowedOrigins && url.isHttps
    }

    sealed class Transition {
        data class Next(val state: State) : Transition()
        data class Rejected(val reason: String) : Transition()
    }

    @JvmStatic
    fun initial(profile: EvidenceProfile?): Transition =
        if (profile?.entryUrl == null || profile.allowedOrigins.isEmpty()) {
            Transition.Rejected("ProtocolNotVerified")
        } else if (!profile.allows(profile.entryUrl)) {
            Transition.Rejected("Entry origin is not allowlisted")
        } else {
            Transition.Next(State.FETCH_ENTRY)
        }

    @JvmStatic
    fun followRedirect(profile: EvidenceProfile, redirect: HttpUrl): Transition =
        if (!profile.allows(redirect)) {
            Transition.Rejected("Redirect origin is not allowlisted")
        } else {
            Transition.Next(
                if (redirect == profile.teachingCallbackUrl) {
                    State.REACH_TEACHING_CALLBACK
                } else {
                    State.FOLLOW_AUTHORIZED_REDIRECTS
                }
            )
        }

    @JvmStatic
    fun rejectNonStandardCookieHandoff(): Transition =
        Transition.Rejected("UNSUPPORTED_NON_STANDARD_COOKIE_HANDOFF")

    private fun originOf(url: HttpUrl): String {
        val defaultPort = if (url.scheme == "https") 443 else 80
        val port = if (url.port == defaultPort) "" else ":${url.port}"
        return "${url.scheme}://${url.host}$port"
    }
}
