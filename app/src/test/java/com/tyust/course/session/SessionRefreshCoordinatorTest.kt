package com.tyust.course.session

import com.tyust.course.login.SessionLoginCallback
import com.tyust.course.login.SessionLoginGateway
import com.tyust.course.model.SchoolConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionRefreshCoordinatorTest {
    private val school = SchoolConfig("tyust", "TYUST", "newjwc.tyust.edu.cn", "https").apply {
        basePath = "/jwglxt"
    }
    private val scope = SchoolSessionScope.fromSchool(school)

    @Before
    fun setUp() {
        SessionRegistry.clearForTests()
    }

    @After
    fun tearDown() {
        SessionRegistry.clearForTests()
    }

    @Test
    fun sameAccountJoinsTheExistingFlightAndInstallsOnce() {
        val gateway = FakeGateway()
        val coordinator = coordinator(gateway)
        val first = RecordingCallback()
        val joined = RecordingCallback()

        val target = coordinator.beginLogin(school, "tyust::student", "student", "secret", first)
        val joinedTarget = coordinator.beginLogin(school, "tyust::student", "student", "secret", joined)

        assertEquals(1, gateway.loginCalls)
        assertEquals(target, joinedTarget)
        assertEquals(target, joined.joinedTarget)

        gateway.succeed(legacyArtifact())

        assertTrue(first.result is SessionInstallResult.InstalledActive)
        assertEquals(1L, SessionRegistry.snapshot(target.accountStorageKey).generation)
    }

    @Test
    fun awaitingJoinReceivesTheExistingFlightResultWithoutStartingAnotherLogin() {
        val gateway = FakeGateway()
        val coordinator = coordinator(gateway)
        val starter = RecordingCallback()
        val waiter = RecordingCallback()

        val target = coordinator.beginLogin(school, "tyust::student", "student", "secret", starter)
        val joinedTarget = coordinator.beginLoginAndAwait(
            school,
            "tyust::student",
            "student",
            "secret",
            waiter
        )

        assertEquals(target, joinedTarget)
        assertEquals(target, waiter.joinedTarget)
        assertEquals(1, gateway.loginCalls)

        gateway.succeed(legacyArtifact())

        assertTrue(starter.result is SessionInstallResult.InstalledActive)
        assertTrue(waiter.result is SessionInstallResult.InstalledActive)
        assertEquals(1L, SessionRegistry.snapshot(target.accountStorageKey).generation)
    }

    @Test
    fun awaitingJoinReceivesTheExistingFlightFailure() {
        val gateway = FakeGateway()
        val coordinator = coordinator(gateway)
        val starter = RecordingCallback()
        val waiter = RecordingCallback()

        val target = coordinator.beginLogin(school, "tyust::student", "student", "secret", starter)
        coordinator.beginLoginAndAwait(school, "tyust::student", "student", "secret", waiter)

        gateway.failError("network unavailable")

        assertEquals(target, starter.errorTarget)
        assertEquals(target, waiter.errorTarget)
        assertEquals("network unavailable", waiter.errorMessage)
    }

    @Test
    fun captchaSubmissionKeepsTheOriginalAttemptAndFailureReleasesFlight() {
        val gateway = FakeGateway()
        val coordinator = coordinator(gateway)
        val first = RecordingCallback()

        val original = coordinator.beginLogin(school, "tyust::student", "student", "secret", first)
        gateway.requireCaptcha(byteArrayOf(1, 2, 3))
        assertEquals(original, first.captchaTarget)

        coordinator.submitCaptcha(original.accountStorageKey, "ABCD", first)
        assertEquals(listOf("ABCD"), gateway.submittedCodes)
        gateway.failCredentials()

        assertEquals(original, first.invalidTarget)
        coordinator.beginLogin(school, "tyust::student", "student", "secret", RecordingCallback())
        assertEquals(2, gateway.loginCalls)
    }

    @Test
    fun differentAccountsDoNotBlockEachOther() {
        val gateways = mutableListOf<FakeGateway>()
        val coordinator = SessionRefreshCoordinator(
            gatewayFactory = {
                FakeGateway().also(gateways::add)
            },
            installSession = { target, artifact -> SessionRegistry.install(target, artifact, active = true) }
        )

        coordinator.beginLogin(school, "tyust::one", "one", "secret", RecordingCallback())
        coordinator.beginLogin(school, "tyust::two", "two", "secret", RecordingCallback())

        assertEquals(2, gateways.size)
        assertEquals(1, gateways[0].loginCalls)
        assertEquals(1, gateways[1].loginCalls)
    }

    private fun coordinator(gateway: FakeGateway) = SessionRefreshCoordinator(
        gatewayFactory = { gateway },
        installSession = { target, artifact -> SessionRegistry.install(target, artifact, active = true) }
    )

    private fun legacyArtifact() = SessionArtifact.LegacyCookieHeader(
        schoolScope = scope,
        header = "JSESSIONID=test-session"
    )

    private class RecordingCallback : CoordinatorCallback {
        var result: SessionInstallResult? = null
        var joinedTarget: SessionInstallTarget? = null
        var captchaTarget: SessionInstallTarget? = null
        var invalidTarget: SessionInstallTarget? = null
        var errorTarget: SessionInstallTarget? = null
        var errorMessage: String? = null

        override fun onInstalled(target: SessionInstallTarget, result: SessionInstallResult) {
            this.result = result
        }

        override fun onJoinInFlight(target: SessionInstallTarget) {
            joinedTarget = target
        }

        override fun onCaptchaRequired(target: SessionInstallTarget, imageBytes: ByteArray) {
            captchaTarget = target
        }

        override fun onCaptchaInvalid(target: SessionInstallTarget) = Unit

        override fun onInvalidCredentials(target: SessionInstallTarget) {
            invalidTarget = target
        }

        override fun onError(target: SessionInstallTarget?, message: String) {
            errorTarget = target
            errorMessage = message
        }
    }

    private class FakeGateway : SessionLoginGateway {
        var loginCalls = 0
        val submittedCodes = mutableListOf<String>()
        private var callback: SessionLoginCallback? = null

        override fun login(
            school: SchoolConfig,
            username: String,
            password: String,
            target: SessionInstallTarget,
            callback: SessionLoginCallback
        ) {
            loginCalls += 1
            this.callback = callback
        }

        override fun submitCaptcha(captchaCode: String, callback: SessionLoginCallback) {
            submittedCodes += captchaCode
            this.callback = callback
        }

        override fun refreshCaptcha(callback: (ByteArray?) -> Unit) = callback(null)

        override fun clearSensitiveState() = Unit

        fun succeed(artifact: SessionArtifact) {
            requireNotNull(callback).onSuccess(artifact)
        }

        fun requireCaptcha(bytes: ByteArray) {
            requireNotNull(callback).onCaptchaRequired(bytes)
        }

        fun failCredentials() {
            requireNotNull(callback).onInvalidCredentials()
        }

        fun failError(message: String) {
            requireNotNull(callback).onError(message)
        }
    }
}
