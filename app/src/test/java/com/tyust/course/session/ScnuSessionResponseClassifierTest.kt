package com.tyust.course.session

import com.tyust.course.model.SchoolConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ScnuSessionResponseClassifierTest {
    @Before
    fun resetRegistry() {
        SessionRegistry.clearForTests()
    }

    @After
    fun clearRegistry() {
        SessionRegistry.clearForTests()
    }

    @Test
    fun `canonical SCNU does not apply legacy json or html expiry heuristics`() {
        val context = context()
        response(
            request(context),
            "{\"code\":401,\"sessionExpired\":true,\"html\":\"<input id='pwd'>\"}"
        ).use { value ->
            val result = SessionResponseClassifiers.classifyFirstStage(value)

            assertEquals(SessionResponseState.INDETERMINATE, result.state)
            assertEquals(SessionEvidenceType.PROTOCOL_NOT_VERIFIED, result.evidenceType)
        }
    }

    @Test
    fun `canonical SCNU unknown json and 403 remain indeterminate without a verified schema`() {
        val context = context()
        response(request(context), "{\"message\":\"unknown\"}").use { value ->
            val result = SessionResponseClassifiers.classifyFirstStage(value)

            assertEquals(SessionResponseState.INDETERMINATE, result.state)
            assertEquals(SessionEvidenceType.PROTOCOL_NOT_VERIFIED, result.evidenceType)
        }
        response(request(context), "", code = 403).use { value ->
            val result = SessionResponseClassifiers.classifyFirstStage(value)

            assertEquals(SessionResponseState.INDETERMINATE, result.state)
            assertEquals(SessionEvidenceType.PROTOCOL_NOT_VERIFIED, result.evidenceType)
        }
    }

    @Test
    fun `canonical SCNU truncated preview remains indeterminate`() {
        val context = context()
        response(request(context), "{\"code\":401}").use { value ->
            val result = ScnuSessionResponseClassifier.classify(
                context = context,
                response = value,
                bodyPreview = "{\"code\":401}",
                previewComplete = false
            )

            assertEquals(SessionResponseState.INDETERMINATE, result.state)
            assertEquals(SessionEvidenceType.PROTOCOL_NOT_VERIFIED, result.evidenceType)
        }
    }

    private fun context(): SessionRequestContext = SessionRequestContext.forSchool(
        school = canonicalScnu(),
        accountStorageKey = "scnu-classifier-account",
        purpose = SessionRequestPurpose.ACADEMIC_QUERY
    )

    private fun request(context: SessionRequestContext): Request = Request.Builder()
        .url("https://jwxt.scnu.edu.cn/academic/query")
        .tag(SessionRequestContext::class.java, context)
        .build()

    private fun response(request: Request, body: String, code: Int = 200): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private fun canonicalScnu(): SchoolConfig = SchoolConfig(
        "scnu",
        "SCNU",
        "jwxt.scnu.edu.cn",
        "https"
    ).apply {
        basePath = ""
    }
}
