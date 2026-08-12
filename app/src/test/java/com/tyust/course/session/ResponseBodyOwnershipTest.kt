package com.tyust.course.session

import com.tyust.course.model.SchoolConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Locks the response-body ownership contract: interceptors may inspect a
 * preview, while the business callback remains the one component allowed to
 * consume the original response body.
 */
class ResponseBodyOwnershipTest {
    @Before
    fun resetRegistry() {
        SessionRegistry.clearForTests()
    }

    @After
    fun clearRegistry() {
        SessionRegistry.clearForTests()
    }

    @Test
    fun `first stage classifier peeks without consuming the business body`() {
        val context = legacyContext()
        val html = "<form><input name=\"yhm\"><input name=\"mm\"><input id=\"pwd\"></form>"
        response(context, html).use { value ->
            val classification = SessionResponseClassifiers.classifyFirstStage(value)

            assertEquals(SessionResponseState.CONFIRMED_EXPIRED, classification.state)
            assertEquals(SessionEvidenceType.LEGACY_LOGIN_FORM, classification.evidenceType)
            assertEquals(html, value.body!!.string())
        }
    }

    @Test
    fun `complete body owner can classify evidence outside a truncated interceptor preview`() {
        val context = legacyContext()
        val html = "x".repeat(SessionResponseClassifiers.DEFAULT_PEEK_BYTES.toInt()) +
            "<form><input name=\"yhm\"><input name=\"mm\"><input id=\"pwd\"></form>"
        response(context, html).use { value ->
            val firstStage = SessionResponseClassifiers.classifyFirstStage(value)
            val consumed = value.body!!.string()
            val completeBody = SessionResponseClassifiers.classifyConsumedBody(context, value, consumed)

            assertEquals(SessionResponseState.INDETERMINATE, firstStage.state)
            assertEquals(SessionEvidenceType.TRUNCATED_PREVIEW, firstStage.evidenceType)
            assertEquals(SessionResponseState.CONFIRMED_EXPIRED, completeBody.state)
            assertEquals(SessionEvidenceType.LEGACY_LOGIN_FORM, completeBody.evidenceType)
        }
    }

    @Test
    fun `canonical SCNU full body is not reclassified using legacy validity evidence`() {
        val context = canonicalScnuContext()
        val html = "<html><input name=\"xm\" value=\"Student\"></html>"
        response(context, html).use { value ->
            SessionResponseClassifiers.classifyFirstStage(value)
            val completeBody = SessionResponseClassifiers.classifyConsumedBody(
                context,
                value,
                value.body!!.string()
            )

            assertEquals(SessionResponseState.INDETERMINATE, completeBody.state)
            assertEquals(SessionEvidenceType.PROTOCOL_NOT_VERIFIED, completeBody.evidenceType)
        }
    }

    @Test
    fun `same confirmed expiry is accepted for one generation only`() {
        val school = legacySchool()
        val account = "response-body-account"
        val scope = SchoolSessionScope.fromSchool(school)
        SessionRegistry.restore(
            account,
            SessionArtifact.LegacyCookieHeader(scope, "opaque-header"),
            generation = 7L,
            active = true
        )
        val context = SessionRequestContext.forSchool(
            school,
            account,
            SessionRequestPurpose.ACADEMIC_QUERY
        )
        val html = "<form><input name=\"yhm\"><input name=\"mm\"><input id=\"pwd\"></form>"

        response(context, html).use { value ->
            val interceptorResult = SessionResponseClassifiers.classifyFirstStage(value)
            val callbackResult = SessionResponseClassifiers.classifyConsumedBody(
                context,
                value,
                value.body!!.string()
            )

            assertEquals(SessionResponseState.CONFIRMED_EXPIRED, interceptorResult.state)
            assertEquals(SessionResponseState.CONFIRMED_EXPIRED, callbackResult.state)

            val firstTransition = SessionRegistry.markConfirmedExpired(
                context.normalizedAccountStorageKey,
                context.sessionGeneration
            )
            val duplicateTransition = SessionRegistry.markConfirmedExpired(
                context.normalizedAccountStorageKey,
                context.sessionGeneration
            )

            assertNotNull(firstTransition)
            assertNull(duplicateTransition)
            assertTrue(SessionRegistry.snapshot(account).state == SessionState.EXPIRED)
            assertEquals(8L, SessionRegistry.snapshot(account).generation)
        }
    }

    private fun legacyContext(): SessionRequestContext = SessionRequestContext.forSchool(
        legacySchool(),
        "response-body-legacy",
        SessionRequestPurpose.ACADEMIC_QUERY
    )

    private fun canonicalScnuContext(): SessionRequestContext = SessionRequestContext.forSchool(
        canonicalScnuSchool(),
        "response-body-scnu",
        SessionRequestPurpose.ACADEMIC_QUERY
    )

    private fun response(context: SessionRequestContext, body: String): Response = Response.Builder()
        .request(
            Request.Builder()
                .url(context.schoolScope.origin + context.schoolScope.basePath + "/protected")
                .tag(SessionRequestContext::class.java, context)
                .build()
        )
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody("text/html".toMediaType()))
        .build()

    private fun legacySchool(): SchoolConfig = SchoolConfig(
        "legacy",
        "Legacy",
        "legacy.example.edu",
        "https"
    ).apply {
        basePath = "/jwglxt"
    }

    private fun canonicalScnuSchool(): SchoolConfig = SchoolConfig(
        "scnu",
        "SCNU",
        SchoolSessionScope.CANONICAL_SCNU_HOST,
        "https"
    ).apply {
        basePath = ""
    }
}
