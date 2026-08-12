package com.tyust.course.session

import com.tyust.course.utils.CourseParser
import okhttp3.Response

/** The only three outcomes allowed to influence session state. */
enum class SessionResponseState {
    VALID,
    CONFIRMED_EXPIRED,
    INDETERMINATE
}

/** Evidence is intentionally value-free so it is safe to place in telemetry. */
enum class SessionEvidenceType {
    NONE,
    MISSING_CONTEXT,
    SCOPE_MISMATCH,
    STALE_SESSION_CONTEXT,
    STALE_ACTIVE_CONTEXT,
    STALE_SERVICE_RUN,
    LOGIN_FLOW,
    LEGACY_LOGIN_REDIRECT,
    LEGACY_LOGIN_FORM,
    LEGACY_EXPIRED_JSON,
    LEGACY_AUTHENTICATED_PAGE,
    TRUNCATED_PREVIEW,
    UNKNOWN_RESPONSE,
    PROTOCOL_NOT_VERIFIED
}

data class SessionResponseClassification(
    val state: SessionResponseState,
    val evidenceType: SessionEvidenceType,
    val message: String = ""
)

interface SessionResponseClassifier {
    /**
     * First-stage, non-destructive classification.  [bodyPreview] must have
     * been obtained using Response.peekBody; this method must never read the
     * real response body.
     */
    fun classify(
        context: SessionRequestContext?,
        response: Response,
        bodyPreview: String,
        previewComplete: Boolean
    ): SessionResponseClassification
}

/**
 * Conservative classifiers for the two modes available in phase one.
 *
 * SCNU intentionally has no positive success/expiry schema here: runtime
 * protocol evidence has not been authorized or collected yet.  Treating its
 * JSON as a legacy HTML page is specifically forbidden.
 */
object SessionResponseClassifiers {
    const val DEFAULT_PEEK_BYTES: Long = 8L * 1024L

    @JvmStatic
    fun forScope(scope: SchoolSessionScope): SessionResponseClassifier =
        if (scope.isCanonicalScnu) ScnuSessionResponseClassifier else LegacySessionResponseClassifier

    @JvmStatic
    fun classifyFirstStage(
        response: Response,
        maxPeekBytes: Long = DEFAULT_PEEK_BYTES
    ): SessionResponseClassification {
        val context = response.request.tag(SessionRequestContext::class.java)
            ?: return SessionResponseClassification(
                SessionResponseState.INDETERMINATE,
                SessionEvidenceType.MISSING_CONTEXT,
                "Protected response has no session request context"
            )
        val body = response.body
        if (body == null) {
            return forScope(context.schoolScope).classify(context, response, "", true)
        }
        val preview = response.peekBody(maxPeekBytes)
        val text = preview.string()
        val declaredLength = body.contentLength()
        val previewComplete = when {
            declaredLength >= 0L -> declaredLength <= maxPeekBytes
            else -> preview.contentLength() < maxPeekBytes
        }
        return forScope(context.schoolScope).classify(context, response, text, previewComplete)
    }

    /**
     * Second-stage classification for the single component which already owns
     * and has consumed a complete body.  It is deliberately opt-in so an
     * interceptor can never drain a business response.
     */
    @JvmStatic
    fun classifyConsumedBody(
        context: SessionRequestContext?,
        response: Response,
        fullBody: String
    ): SessionResponseClassification {
        val first = forScope(context?.schoolScope ?: return SessionResponseClassification(
            SessionResponseState.INDETERMINATE,
            SessionEvidenceType.MISSING_CONTEXT
        )).classify(context, response, fullBody, true)
        if (first.state == SessionResponseState.CONFIRMED_EXPIRED ||
            context.schoolScope.isCanonicalScnu
        ) return first

        // The legacy student name marker is only a positive validity proof
        // after the caller has consumed a complete legacy HTML document.
        val name = CourseParser.parseStudentName(fullBody)
        return if (!name.isNullOrBlank()) {
            SessionResponseClassification(
                SessionResponseState.VALID,
                SessionEvidenceType.LEGACY_AUTHENTICATED_PAGE
            )
        } else {
            first
        }
    }

    internal fun isContextCurrent(context: SessionRequestContext): SessionResponseClassification? {
        if (!SessionRegistry.isCurrent(context.normalizedAccountStorageKey, context.sessionGeneration)) {
            return SessionResponseClassification(
                SessionResponseState.INDETERMINATE,
                SessionEvidenceType.STALE_SESSION_CONTEXT
            )
        }
        if (context.owner == SessionRequestOwner.UI && context.activeContextEpoch != null &&
            context.activeContextEpoch != SessionRegistry.activeContextEpoch()
        ) {
            return SessionResponseClassification(
                SessionResponseState.INDETERMINATE,
                SessionEvidenceType.STALE_ACTIVE_CONTEXT
            )
        }
        return null
    }

    internal fun requestOriginMatchesScope(context: SessionRequestContext, response: Response): Boolean {
        var current: Response? = response
        while (current != null) {
            if (context.schoolScope.matches(current.request.url)) return true
            current = current.priorResponse
        }
        return false
    }
}

object LegacySessionResponseClassifier : SessionResponseClassifier {
    // These are the already-supported legacy positive markers, deliberately
    // narrower than a generic "login" or "not logged in" text search.
    private val explicitExpiredJson = Regex(
        "\\\"(?:notLogin|sessionExpired)\\\"\\s*:\\s*(?:true|\\\"true\\\")|" +
            "\\\"code\\\"\\s*:\\s*\\\"?401\\\"?",
        RegexOption.IGNORE_CASE
    )

    override fun classify(
        context: SessionRequestContext?,
        response: Response,
        bodyPreview: String,
        previewComplete: Boolean
    ): SessionResponseClassification {
        if (context == null) return SessionResponseClassification(
            SessionResponseState.INDETERMINATE,
            SessionEvidenceType.MISSING_CONTEXT
        )
        SessionResponseClassifiers.isContextCurrent(context)?.let { return it }
        if (context.purpose == SessionRequestPurpose.LOGIN_FLOW) {
            return SessionResponseClassification(
                SessionResponseState.INDETERMINATE,
                SessionEvidenceType.LOGIN_FLOW
            )
        }
        if (!SessionResponseClassifiers.requestOriginMatchesScope(context, response)) {
            return SessionResponseClassification(
                SessionResponseState.INDETERMINATE,
                SessionEvidenceType.SCOPE_MISMATCH
            )
        }

        val finalPath = response.request.url.encodedPath.lowercase()
        val originalPath = response.request.url.toString().lowercase()
        val isRedirectedToLogin = response.priorResponse != null &&
            (finalPath.contains("login") || finalPath.contains("slogin") ||
                originalPath.contains("/cas/") || originalPath.contains("/oauth"))
        if (isRedirectedToLogin) {
            return SessionResponseClassification(
                SessionResponseState.CONFIRMED_EXPIRED,
                SessionEvidenceType.LEGACY_LOGIN_REDIRECT
            )
        }

        if (!previewComplete) {
            return SessionResponseClassification(
                SessionResponseState.INDETERMINATE,
                SessionEvidenceType.TRUNCATED_PREVIEW
            )
        }
        val normalized = bodyPreview.lowercase()
        val hasLoginForm = normalized.contains("id=\"pwd\"") ||
            (normalized.contains("name=\"mm\"") && normalized.contains("name=\"yhm\"")) ||
            (normalized.contains("id='pwd'") ||
                (normalized.contains("name='mm'") && normalized.contains("name='yhm'")))
        if (hasLoginForm) {
            return SessionResponseClassification(
                SessionResponseState.CONFIRMED_EXPIRED,
                SessionEvidenceType.LEGACY_LOGIN_FORM
            )
        }
        if (explicitExpiredJson.containsMatchIn(bodyPreview)) {
            return SessionResponseClassification(
                SessionResponseState.CONFIRMED_EXPIRED,
                SessionEvidenceType.LEGACY_EXPIRED_JSON
            )
        }
        return SessionResponseClassification(
            SessionResponseState.INDETERMINATE,
            SessionEvidenceType.UNKNOWN_RESPONSE
        )
    }
}

object ScnuSessionResponseClassifier : SessionResponseClassifier {
    override fun classify(
        context: SessionRequestContext?,
        response: Response,
        bodyPreview: String,
        previewComplete: Boolean
    ): SessionResponseClassification = SessionResponseClassification(
        SessionResponseState.INDETERMINATE,
        SessionEvidenceType.PROTOCOL_NOT_VERIFIED,
        "SCNU response schema has not been verified"
    )
}
