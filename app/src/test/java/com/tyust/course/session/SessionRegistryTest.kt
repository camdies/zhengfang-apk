package com.tyust.course.session

import com.tyust.course.model.SchoolConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionRegistryTest {
    private val scope = SchoolSessionScope.fromSchool(
        SchoolConfig("tyust", "TYUST", "newjwc.tyust.edu.cn", "https").apply {
            basePath = "/jwglxt"
        }
    )
    private val account = "tyust__student"

    @Before
    fun reset() {
        SessionRegistry.clearForTests()
    }

    @After
    fun clear() {
        SessionRegistry.clearForTests()
    }

    @Test
    fun installReplaceExpireAndClearAdvanceGeneration() {
        val first = install(SessionRegistry.beginLogin(account, scope), active = true)
        assertEquals(1L, first.generation)
        assertEquals(SessionState.ACTIVE, first.state)

        val replacement = install(SessionRegistry.beginLogin(account, scope), active = true)
        assertEquals(2L, replacement.generation)

        val expired = SessionRegistry.markConfirmedExpired(account, replacement.generation)
        requireNotNull(expired)
        assertEquals(3L, expired.generation)
        assertEquals(SessionState.EXPIRED, expired.state)

        val cleared = SessionRegistry.clear(account)
        assertEquals(4L, cleared.generation)
        assertEquals(SessionState.EMPTY, cleared.state)
    }

    @Test
    fun switchingContextDoesNotAdvanceAccountGeneration() {
        val artifact = legacyArtifact()
        SessionRegistry.restore(account, artifact, generation = 7L, active = false)

        val epochBefore = SessionRegistry.activeContextEpoch()
        SessionRegistry.bumpActiveContextEpoch()

        assertEquals(7L, SessionRegistry.snapshot(account).generation)
        assertEquals(epochBefore + 1L, SessionRegistry.activeContextEpoch())
    }

    @Test
    fun lateAttemptCannotReplaceNewerAttempt() {
        val oldTarget = SessionRegistry.beginLogin(account, scope)
        val currentTarget = SessionRegistry.beginLogin(account, scope)

        assertTrue(SessionRegistry.install(oldTarget, legacyArtifact(), active = true)
            is SessionInstallResult.StaleTarget)
        val current = install(currentTarget, active = true)
        assertEquals(1L, current.generation)
    }

    @Test
    fun inactiveInstallCanBecomeActiveWithoutGenerationChange() {
        val inactive = install(SessionRegistry.beginLogin(account, scope), active = false)
        assertEquals(SessionState.INACTIVE, inactive.state)

        val restored = SessionRegistry.restore(
            account,
            inactive.artifact,
            inactive.generation,
            active = true
        )
        assertEquals(inactive.generation, restored.generation)
        assertEquals(SessionState.ACTIVE, restored.state)
        assertTrue(SessionRegistry.isCurrent(account, restored.generation))
    }

    @Test
    fun expiredGenerationIsNotCurrent() {
        val active = install(SessionRegistry.beginLogin(account, scope), active = true)
        val expired = requireNotNull(SessionRegistry.markConfirmedExpired(account, active.generation))

        assertFalse(SessionRegistry.isCurrent(account, expired.generation))
    }

    @Test
    fun rfcBundleWithoutServiceAudienceCannotBeInstalled() {
        val target = SessionRegistry.beginLogin(account, scope)
        val bundle = SessionArtifact.RfcCookieBundle(
            schoolScope = scope,
            cookies = emptyList(),
            serviceAudiences = emptyList()
        )

        assertTrue(
            SessionRegistry.install(target, bundle, active = true)
                is SessionInstallResult.UnsupportedArtifact
        )
        assertEquals(SessionState.EMPTY, SessionRegistry.snapshot(account).state)
    }

    private fun install(target: SessionInstallTarget, active: Boolean): SessionSnapshot {
        return when (val result = SessionRegistry.install(target, legacyArtifact(), active)) {
            is SessionInstallResult.InstalledActive -> result.snapshot
            is SessionInstallResult.InstalledInactive -> result.snapshot
            else -> error("unexpected install result: $result")
        }
    }

    private fun legacyArtifact() = SessionArtifact.LegacyCookieHeader(
        schoolScope = scope,
        header = "JSESSIONID=test-session"
    )
}
