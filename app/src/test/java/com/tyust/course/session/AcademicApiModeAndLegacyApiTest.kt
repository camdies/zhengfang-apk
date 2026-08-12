package com.tyust.course.session

import com.tyust.course.model.SchoolConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class AcademicApiModeAndLegacyApiTest {
    @Test
    fun `only the canonical SCNU scope is held behind the academic protocol gate`() {
        assertEquals(
            AcademicApiMode.ProtocolNotVerified,
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
