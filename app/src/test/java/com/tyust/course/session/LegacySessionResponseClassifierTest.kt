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

class LegacySessionResponseClassifierTest {
    @Before
    fun resetRegistry() {
        SessionRegistry.clearForTests()
    }

    @After
    fun clearRegistry() {
        SessionRegistry.clearForTests()
    }

    @Test
    fun `redirect landing on an explicit legacy login endpoint is confirmed expired`() {
        val context = context()
        val initialRequest = request(context, "/jwglxt/cjcx/cjcx_cxDgXscj.html")
        val redirect = Response.Builder()
            .request(initialRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(302)
            .message("Found")
            .build()
        val loginResponse = response(
            request(context, "/jwglxt/xtgl/login_slogin.html"),
            "<html>login</html>",
            priorResponse = redirect
        )

        loginResponse.use { value ->
            assertClassification(
                SessionResponseClassifiers.classifyFirstStage(value),
                SessionResponseState.CONFIRMED_EXPIRED,
                SessionEvidenceType.LEGACY_LOGIN_REDIRECT
            )
        }
    }

    @Test
    fun `explicit legacy login form is confirmed expired`() {
        response(
            request(context(), "/jwglxt/cjcx/cjcx_cxDgXscj.html"),
            "<form><input name=\"yhm\"><input name=\"mm\"><input id=\"pwd\"></form>"
        ).use { value ->
            assertClassification(
                SessionResponseClassifiers.classifyFirstStage(value),
                SessionResponseState.CONFIRMED_EXPIRED,
                SessionEvidenceType.LEGACY_LOGIN_FORM
            )
        }
    }

    @Test
    fun `explicit legacy expired json is confirmed expired`() {
        response(
            request(context(), "/jwglxt/cjcx/cjcx_cxDgXscj.html"),
            "{\"sessionExpired\":true}"
        ).use { value ->
            assertClassification(
                SessionResponseClassifiers.classifyFirstStage(value),
                SessionResponseState.CONFIRMED_EXPIRED,
                SessionEvidenceType.LEGACY_EXPIRED_JSON
            )
        }
    }

    @Test
    fun `unknown legacy html and bare 403 remain indeterminate`() {
        val context = context()
        response(
            request(context, "/jwglxt/cjcx/cjcx_cxDgXscj.html"),
            "<html><body>unrecognized server page</body></html>"
        ).use { value ->
            assertClassification(
                SessionResponseClassifiers.classifyFirstStage(value),
                SessionResponseState.INDETERMINATE,
                SessionEvidenceType.UNKNOWN_RESPONSE
            )
        }
        response(
            request(context, "/jwglxt/cjcx/cjcx_cxDgXscj.html"),
            "",
            code = 403
        ).use { value ->
            assertClassification(
                SessionResponseClassifiers.classifyFirstStage(value),
                SessionResponseState.INDETERMINATE,
                SessionEvidenceType.UNKNOWN_RESPONSE
            )
        }
    }

    @Test
    fun `truncated preview never turns a partial login marker into expiry evidence`() {
        val context = context()
        response(
            request(context, "/jwglxt/cjcx/cjcx_cxDgXscj.html"),
            "<input id=\"pwd\">"
        ).use { value ->
            assertClassification(
                LegacySessionResponseClassifier.classify(
                    context = context,
                    response = value,
                    bodyPreview = "<input id=\"pwd\">",
                    previewComplete = false
                ),
                SessionResponseState.INDETERMINATE,
                SessionEvidenceType.TRUNCATED_PREVIEW
            )
        }
    }

    private fun context(): SessionRequestContext = SessionRequestContext.forSchool(
        school = legacySchool(),
        accountStorageKey = "legacy-classifier-account",
        purpose = SessionRequestPurpose.ACADEMIC_QUERY
    )

    private fun request(context: SessionRequestContext, path: String): Request = Request.Builder()
        .url("https://newjwc.tyust.edu.cn$path")
        .tag(SessionRequestContext::class.java, context)
        .build()

    private fun response(
        request: Request,
        body: String,
        code: Int = 200,
        priorResponse: Response? = null
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("text/html".toMediaType()))
        if (priorResponse != null) {
            builder.priorResponse(priorResponse)
        }
        return builder.build()
    }

    private fun assertClassification(
        value: SessionResponseClassification,
        expectedState: SessionResponseState,
        expectedEvidence: SessionEvidenceType
    ) {
        assertEquals(expectedState, value.state)
        assertEquals(expectedEvidence, value.evidenceType)
    }

    private fun legacySchool(): SchoolConfig = SchoolConfig(
        "tyust",
        "TYUST",
        "newjwc.tyust.edu.cn",
        "https"
    ).apply {
        basePath = "/jwglxt"
    }
}
