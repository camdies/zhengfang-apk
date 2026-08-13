package com.tyust.course.session

import com.tyust.course.model.SchoolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicApiModeAndLegacyApiTest {
    @Test
    fun `canonical SCNU resolves to legacy academic api mode once verified`() {
        assertEquals(
            AcademicApiMode.Legacy,
            AcademicApiModeResolver.resolve(canonicalScnuSchool())
        )
        assertEquals(
            AcademicApiMode.Legacy,
            AcademicApiModeResolver.resolve(nonCanonicalSameHostSchool())
        )
        assertEquals(
            AcademicApiMode.Legacy,
            AcademicApiModeResolver.resolve(legacySchool())
        )
    }

    @Test
    fun `verified login and academic capabilities stay enabled while SCNU selection stays closed`() {
        assertTrue(ScnuProtocolCapabilities.LOGIN_ENABLED)
        assertTrue(ScnuProtocolCapabilities.ACADEMIC_PROFILE_ENABLED)
        assertTrue(ScnuProtocolCapabilities.isAcademicProfileAvailable(canonicalScnuSchool()))
        assertFalse(ScnuProtocolCapabilities.isCourseSelectionAllowed(canonicalScnuSchool()))
        assertTrue(ScnuProtocolCapabilities.isCourseSelectionAllowed(legacySchool()))
    }

    @Test
    fun `legacy header export remains available only for a legacy artifact`() {
        val scope = SchoolSessionScope.fromSchool(legacySchool())
        val legacy = SessionArtifact.LegacyCookieHeader(scope, "opaque-legacy-header")
        val rfcBundle = SessionArtifact.RfcCookieBundle(
            schoolScope = scope,
            cookies = emptyList(),
            serviceAudiences = emptyList()
        )

        assertEquals(
            LegacyCookieHeaderResult.Available("opaque-legacy-header"),
            legacy.exportLegacyCookieHeader()
        )
        assertEquals(
            LegacyCookieHeaderResult.UnsupportedSessionMode,
            rfcBundle.exportLegacyCookieHeader()
        )
    }

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

    private fun nonCanonicalSameHostSchool(): SchoolConfig = SchoolConfig(
        "scnu-other-path",
        "SCNU noncanonical path",
        SchoolSessionScope.CANONICAL_SCNU_HOST,
        "https"
    ).apply {
        basePath = "/legacy-path"
    }
}
