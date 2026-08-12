package com.tyust.course.session

import com.tyust.course.model.SchoolConfig
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class SessionRequestContextTest {
    @Before
    fun resetRegistry() {
        SessionRegistry.clearForTests()
    }

    @After
    fun clearRegistry() {
        SessionRegistry.clearForTests()
    }

    @Test
    fun `request tag survives an OkHttp request rebuild`() {
        val context = SessionRequestContext.forSchool(
            school = legacySchool(),
            accountStorageKey = "request-context-account",
            purpose = SessionRequestPurpose.ACADEMIC_QUERY
        )
        val original = Request.Builder()
            .url("https://newjwc.tyust.edu.cn/jwglxt/cjcx/cjcx_cxDgXscj.html")
            .tag(SessionRequestContext::class.java, context)
            .build()

        val rebuilt = original.newBuilder()
            .header("X-Test", "rebuilt")
            .build()

        assertSame(context, rebuilt.tag(SessionRequestContext::class.java))
        assertEquals(SessionRequestPurpose.ACADEMIC_QUERY, rebuilt.tag(SessionRequestContext::class.java)?.purpose)
    }

    @Test
    fun `login request context keeps login purpose and bypasses expiry classification`() {
        val context = SessionRequestContext.forSchool(
            school = legacySchool(),
            accountStorageKey = "login-context-account",
            purpose = SessionRequestPurpose.LOGIN_FLOW
        )
        val request = Request.Builder()
            .url("https://newjwc.tyust.edu.cn/jwglxt/xtgl/login_slogin.html")
            .tag(SessionRequestContext::class.java, context)
            .build()

        response(request, "<input id=\"pwd\">").use { value ->
            val classification = SessionResponseClassifiers.classifyFirstStage(value)

            assertEquals(SessionResponseState.INDETERMINATE, classification.state)
            assertEquals(SessionEvidenceType.LOGIN_FLOW, classification.evidenceType)
        }
    }

    @Test
    fun `untagged protected response is indeterminate instead of guessing its school`() {
        val request = Request.Builder()
            .url("https://newjwc.tyust.edu.cn/jwglxt/cjcx/cjcx_cxDgXscj.html")
            .build()

        response(request, "{\"code\":401}").use { value ->
            val classification = SessionResponseClassifiers.classifyFirstStage(value)

            assertEquals(SessionResponseState.INDETERMINATE, classification.state)
            assertEquals(SessionEvidenceType.MISSING_CONTEXT, classification.evidenceType)
        }
    }

    @Test
    fun `tagged response outside its school scope is indeterminate`() {
        val context = SessionRequestContext.forSchool(
            school = legacySchool(),
            accountStorageKey = "scope-mismatch-account",
            purpose = SessionRequestPurpose.ACADEMIC_QUERY
        )
        val request = Request.Builder()
            .url("https://other-school.example.edu/jwglxt/cjcx/cjcx_cxDgXscj.html")
            .tag(SessionRequestContext::class.java, context)
            .build()

        response(request, "<input id=\"pwd\">").use { value ->
            val classification = SessionResponseClassifiers.classifyFirstStage(value)

            assertEquals(SessionResponseState.INDETERMINATE, classification.state)
            assertEquals(SessionEvidenceType.SCOPE_MISMATCH, classification.evidenceType)
        }
    }

    private fun response(request: Request, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody("text/html".toMediaType()))
        .build()

    private fun legacySchool(): SchoolConfig = SchoolConfig(
        "tyust",
        "TYUST",
        "newjwc.tyust.edu.cn",
        "https"
    ).apply {
        basePath = "/jwglxt"
    }
}
