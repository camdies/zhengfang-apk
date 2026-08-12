package com.tyust.course.session

import com.tyust.course.model.SchoolConfig

/**
 * A deliberately small, side-effect-free interpretation of a session probe.
 *
 * A probe is evidence, not an instruction to log the user out.  Callers must
 * only publish an expiry event after [CONFIRMED_EXPIRED].  In particular, a
 * missing student name is not expiry evidence by itself: the page may have
 * changed, be incomplete, or be a response format that this app does not yet
 * understand.
 */
enum class SessionProbeResult {
    VALID,
    CONFIRMED_EXPIRED,
    INDETERMINATE
}

/**
 * Immutable context captured before an asynchronous probe begins.
 *
 * Staleness is intentionally represented separately from [SessionProbeResult]
 * so a stale response cannot be mistaken for an unknown server response.
 */
data class SessionProbeContext(
    val accountStorageKey: String,
    val sessionGeneration: Long,
    val activeContextEpoch: Long,
    val serviceRunId: Long? = null
)

/** Input that can be assembled by a Watchdog or a service without consuming a response twice. */
data class SessionProbeInput(
    val school: SchoolConfig?,
    val responseUrl: String?,
    val statusCode: Int?,
    val contentType: String?,
    val body: String?,
    val parsedStudentName: String?,
    val networkFailure: Boolean = false
)

object SessionProbe {
    /**
     * Collapses evidence into the three side-effect policy outcomes without
     * knowing where that evidence came from.  Network clients adapt their
     * own response classifiers at the boundary; this policy remains usable
     * for any future probe source.
     */
    @JvmStatic
    fun fromEvidence(
        hasValidEvidence: Boolean,
        hasConfirmedExpiryEvidence: Boolean
    ): SessionProbeResult = when {
        hasConfirmedExpiryEvidence -> SessionProbeResult.CONFIRMED_EXPIRED
        hasValidEvidence -> SessionProbeResult.VALID
        else -> SessionProbeResult.INDETERMINATE
    }

    /**
     * Classifies only evidence already available to the caller.  It contains
     * no SCNU endpoint, JSON schema, or redirect assumptions: canonical SCNU
     * remains indeterminate until an authorized protocol profile supplies
     * verified evidence through a caller-owned classifier.
     */
    @JvmStatic
    fun classify(input: SessionProbeInput): SessionProbeResult {
        if (input.networkFailure || input.body == null) {
            return SessionProbeResult.INDETERMINATE
        }

        // Do not re-interpret legacy page markers as SCNU protocol evidence.
        if (SchoolSessionScope.isCanonicalScnu(input.school)) {
            return SessionProbeResult.INDETERMINATE
        }

        return fromEvidence(
            hasValidEvidence = !input.parsedStudentName.isNullOrBlank(),
            hasConfirmedExpiryEvidence = hasConfirmedLegacyLoginEvidence(input)
        )
    }

    /**
     * A stale callback must be discarded rather than converted to a third
     * server-state outcome.  `currentServiceRunId` is nullable for callers
     * that are not service-owned (for example CookieWatchdog).
     */
    @JvmStatic
    fun isCurrent(
        context: SessionProbeContext,
        currentAccountStorageKey: String,
        currentSessionGeneration: Long,
        currentActiveContextEpoch: Long,
        currentServiceRunId: Long? = null
    ): Boolean {
        if (context.accountStorageKey != currentAccountStorageKey) return false
        if (context.sessionGeneration != currentSessionGeneration) return false
        if (context.activeContextEpoch != currentActiveContextEpoch) return false
        return context.serviceRunId == null || context.serviceRunId == currentServiceRunId
    }

    private fun hasConfirmedLegacyLoginEvidence(input: SessionProbeInput): Boolean {
        val body = input.body.orEmpty()
        val normalizedUrl = input.responseUrl.orEmpty().lowercase()
        val hasLoginForm = body.contains("id=\"pwd\"") ||
            (body.contains("name=\"mm\"") && body.contains("name=\"yhm\""))
        if (hasLoginForm) return true

        val isKnownLegacyLoginUrl = normalizedUrl.contains("login_slogin") ||
            normalizedUrl.contains("slogin.html")
        return isKnownLegacyLoginUrl && body.contains("用户登录")
    }
}

/**
 * Course-selection capability boundary shared by the grab/queue UI and the
 * background grab service.  Delegates to the single SCNU switch so the list,
 * selected-courses, and grab paths all honor the same gate.
 */
object CourseSelectionCapability {
    const val PROTOCOL_NOT_VERIFIED = "ProtocolNotVerified"

    @JvmStatic
    fun isSupported(school: SchoolConfig?): Boolean =
        school != null && ScnuProtocolCapabilities.isCourseSelectionAllowed(school)

    @JvmStatic
    fun unavailableMessage(school: SchoolConfig?): String? =
        if (school != null && !isSupported(school)) {
            "该学校的选课协议尚未验证（$PROTOCOL_NOT_VERIFIED），已阻止启动。"
        } else {
            null
        }
}
