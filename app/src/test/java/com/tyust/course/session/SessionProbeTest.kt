package com.tyust.course.session

import com.tyust.course.model.SchoolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionProbeTest {

    private fun legacySchool() = SchoolConfig(
        "tyust",
        "太原科技大学",
        "newjwc.tyust.edu.cn",
        "https"
    )

    private fun canonicalScnu() = SchoolConfig(
        "scnu",
        "华南师范大学",
        "jwxt.scnu.edu.cn",
        "https"
    ).apply {
        basePath = ""
    }

    @Test
    fun `legacy explicit login form is confirmed expired`() {
        val result = SessionProbe.classify(
            SessionProbeInput(
                school = legacySchool(),
                responseUrl = "https://newjwc.tyust.edu.cn/jwglxt/xtgl/index_cxYhxxIndex.html",
                statusCode = 200,
                contentType = "text/html",
                body = "<form><input id=\"pwd\"><input name=\"yhm\"></form>",
                parsedStudentName = null
            )
        )

        assertEquals(SessionProbeResult.CONFIRMED_EXPIRED, result)
    }

    @Test
    fun `legacy unknown html without a student name is indeterminate`() {
        val result = SessionProbe.classify(
            SessionProbeInput(
                school = legacySchool(),
                responseUrl = "https://newjwc.tyust.edu.cn/jwglxt/xtgl/index_cxYhxxIndex.html",
                statusCode = 200,
                contentType = "text/html",
                body = "<html><body>页面结构发生变化</body></html>",
                parsedStudentName = null
            )
        )

        assertEquals(SessionProbeResult.INDETERMINATE, result)
    }

    @Test
    fun `network failure remains indeterminate and is never expiry evidence`() {
        val result = SessionProbe.classify(
            SessionProbeInput(
                school = legacySchool(),
                responseUrl = null,
                statusCode = null,
                contentType = null,
                body = null,
                parsedStudentName = null,
                networkFailure = true
            )
        )

        assertEquals(SessionProbeResult.INDETERMINATE, result)
    }

    @Test
    fun `canonical scnu json without verified schema is indeterminate`() {
        val result = SessionProbe.classify(
            SessionProbeInput(
                school = canonicalScnu(),
                responseUrl = "https://jwxt.scnu.edu.cn/unknown",
                statusCode = 200,
                contentType = "application/json",
                body = "{\"message\":\"unknown\"}",
                parsedStudentName = null
            )
        )

        assertEquals(SessionProbeResult.INDETERMINATE, result)
    }

    @Test
    fun `canonical scnu stays indeterminate even when a legacy name parser returns a value`() {
        val result = SessionProbe.classify(
            SessionProbeInput(
                school = canonicalScnu(),
                responseUrl = "https://jwxt.scnu.edu.cn/unknown",
                statusCode = 200,
                contentType = "application/json",
                body = "{\"message\":\"unknown\"}",
                parsedStudentName = "legacy-parser-output"
            )
        )

        assertEquals(SessionProbeResult.INDETERMINATE, result)
    }

    @Test
    fun `legacy json flag text alone is not confirmed expiry evidence`() {
        val result = SessionProbe.classify(
            SessionProbeInput(
                school = legacySchool(),
                responseUrl = "https://newjwc.tyust.edu.cn/jwglxt/xtgl/index_cxYhxxIndex.html",
                statusCode = 200,
                contentType = "application/json",
                body = "{\"notLogin\":false}",
                parsedStudentName = null
            )
        )

        assertEquals(SessionProbeResult.INDETERMINATE, result)
    }

    @Test
    fun `a parsed student name is valid`() {
        val result = SessionProbe.classify(
            SessionProbeInput(
                school = legacySchool(),
                responseUrl = "https://newjwc.tyust.edu.cn/jwglxt/xtgl/index_cxYhxxIndex.html",
                statusCode = 200,
                contentType = "text/html",
                body = "<html></html>",
                parsedStudentName = "测试同学"
            )
        )

        assertEquals(SessionProbeResult.VALID, result)
    }

    @Test
    fun `stale probe context is discarded`() {
        val context = SessionProbeContext(
            accountStorageKey = "account_a",
            sessionGeneration = 7L,
            activeContextEpoch = 3L,
            serviceRunId = 11L
        )

        assertTrue(SessionProbe.isCurrent(context, "account_a", 7L, 3L, 11L))
        assertFalse(SessionProbe.isCurrent(context, "account_a", 8L, 3L, 11L))
        assertFalse(SessionProbe.isCurrent(context, "account_a", 7L, 4L, 11L))
        assertFalse(SessionProbe.isCurrent(context, "account_a", 7L, 3L, 12L))
    }

    @Test
    fun `canonical scnu course selection is enabled once verified`() {
        assertTrue(CourseSelectionCapability.isSupported(canonicalScnu()))
        assertTrue(CourseSelectionCapability.isSupported(legacySchool()))
    }

    @Test
    fun `pure evidence policy retains the three probe outcomes`() {
        assertEquals(
            SessionProbeResult.VALID,
            SessionProbe.fromEvidence(
                hasValidEvidence = true,
                hasConfirmedExpiryEvidence = false
            )
        )
        assertEquals(
            SessionProbeResult.CONFIRMED_EXPIRED,
            SessionProbe.fromEvidence(
                hasValidEvidence = true,
                hasConfirmedExpiryEvidence = true
            )
        )
        assertEquals(
            SessionProbeResult.INDETERMINATE,
            SessionProbe.fromEvidence(
                hasValidEvidence = false,
                hasConfirmedExpiryEvidence = false
            )
        )
    }
}
