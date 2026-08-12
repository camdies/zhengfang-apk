package com.tyust.course.manager

import com.tyust.course.model.SchoolConfig
import com.tyust.course.session.AudienceRole
import com.tyust.course.session.PersistedCookie
import com.tyust.course.session.SchoolSessionScope
import com.tyust.course.session.SessionArtifact
import com.tyust.course.session.SessionArtifactCodec
import com.tyust.course.session.SessionAudience
import com.tyust.course.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSessionPolicyTest {
    private val manager = UserManager.getInstance()

    @Test
    fun legacyArtifactRestoresOnlyForItsMatchingNonCanonicalScope() {
        val school = school("tyust", "newjwc.tyust.edu.cn", "https", "/jwglxt")
        val artifact = SessionArtifact.LegacyCookieHeader(
            schoolScope = SchoolSessionScope.fromSchool(school),
            header = "JSESSIONID=test-session"
        )
        val record = recordFor(school, artifact)

        assertTrue(manager.hasRestorableSession(school, record))
        assertFalse(
            manager.hasRestorableSession(
                school("tyust", "newjwc.tyust.edu.cn", "https", "/different"),
                record
            )
        )
    }

    @Test
    fun canonicalScnuNeverRestoresAFlatLegacyHeader() {
        val scnu = school("scnu", "jwxt.scnu.edu.cn", "https", "")
        val artifact = SessionArtifact.LegacyCookieHeader(
            schoolScope = SchoolSessionScope.fromSchool(scnu),
            header = "JSESSIONID=test-session"
        )
        val record = recordFor(scnu, artifact)

        assertFalse(manager.hasRestorableSession(scnu, record))
    }

    @Test
    fun rfcBundleRequiresCookieThatMatchesServiceRequiredAudience() {
        val scnu = school("scnu", "jwxt.scnu.edu.cn", "https", "")
        val matchingCookie = PersistedCookie(
            name = "JSESSIONID",
            value = "test-session",
            domain = "jwxt.scnu.edu.cn",
            path = "/",
            expiresAt = Long.MAX_VALUE,
            secure = true,
            httpOnly = true,
            hostOnly = true,
            persistent = false,
            setByHost = "jwxt.scnu.edu.cn"
        )
        val artifact = SessionArtifact.RfcCookieBundle(
            schoolScope = SchoolSessionScope.fromSchool(scnu),
            cookies = listOf(matchingCookie),
            serviceAudiences = listOf(
                SessionAudience("https://jwxt.scnu.edu.cn/academics", AudienceRole.SERVICE_REQUIRED)
            )
        )

        assertTrue(manager.hasRestorableSession(scnu, recordFor(scnu, artifact)))

        val noMatchingAudience = artifact.copy(
            serviceAudiences = listOf(
                SessionAudience("https://other.scnu.edu.cn/academics", AudienceRole.SERVICE_REQUIRED)
            )
        )
        assertFalse(manager.hasRestorableSession(scnu, recordFor(scnu, noMatchingAudience)))
    }

    @Test
    fun legacyMigrationIsIdempotentAndKeepsSeparateAccounts() {
        val tyust = school("tyust", "newjwc.tyust.edu.cn", "https", "/jwglxt")
        val zjut = school("zjut", "www.gdjw.zjut.edu.cn", "http", "/jwglxt")
        val first = UserManager.AccountRecord().apply {
            key = "tyust::first"
            schoolId = tyust.id
            cookie = "JSESSIONID=first-test"
        }
        val second = UserManager.AccountRecord().apply {
            key = "zjut::second"
            schoolId = zjut.id
            cookie = "JSESSIONID=second-test"
        }
        val records = mutableListOf(first, second)

        assertTrue(UserManager.migrateLegacyAccountRecord(first, tyust))
        assertTrue(UserManager.migrateLegacyAccountRecord(second, zjut))
        val firstGeneration = first.sessionGeneration
        val firstArtifact = first.sessionArtifactJson

        assertFalse(UserManager.migrateLegacyAccountRecord(first, tyust))
        assertEquals(2, records.size)
        assertEquals(firstGeneration, first.sessionGeneration)
        assertEquals(firstArtifact, first.sessionArtifactJson)
        assertTrue(manager.hasRestorableSession(tyust, first))
        assertTrue(manager.hasRestorableSession(zjut, second))
    }

    @Test
    fun canonicalScnuFlatCookieMigrationDoesNotConstructBundle() {
        val scnu = school("scnu", "jwxt.scnu.edu.cn", "https", "")
        val record = UserManager.AccountRecord().apply {
            key = "scnu::student"
            schoolId = scnu.id
            cookie = "JSESSIONID=old-flat-test"
        }

        assertFalse(UserManager.migrateLegacyAccountRecord(record, scnu))
        assertTrue(record.sessionArtifactJson.isEmpty())
        assertEquals(SessionState.EMPTY.name, record.sessionState)
        assertFalse(manager.hasRestorableSession(scnu, record))
    }

    private fun recordFor(
        school: SchoolConfig,
        artifact: SessionArtifact
    ): UserManager.AccountRecord = UserManager.AccountRecord().apply {
        key = "${school.id}::student"
        schoolId = school.id
        schoolName = school.name
        sessionArtifactJson = SessionArtifactCodec.encode(artifact)
        sessionGeneration = 1L
        sessionState = SessionState.INACTIVE.name
        artifactSchemaVersion = artifact.schemaVersion
    }

    private fun school(id: String, host: String, scheme: String, basePath: String): SchoolConfig =
        SchoolConfig(id, id, host, scheme).apply { this.basePath = basePath }
}
