package com.tyust.course.session

import com.tyust.course.login.SessionLoginCallback
import com.tyust.course.login.SessionLoginGateway
import com.tyust.course.login.SessionLoginGatewayFactory
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-account single-flight login coordinator.
 *
 * UI may use it for an interactive retry; side-effecting GrabService is not a
 * waiter and must stop rather than automatically resume after a refresh.
 */
class SessionRefreshCoordinator(
    private val gatewayFactory: (SchoolConfig) -> SessionLoginGateway = SessionLoginGatewayFactory::create,
    private val installSession: (SessionInstallTarget, SessionArtifact) -> SessionInstallResult = { target, artifact ->
        // UserManager owns account-record existence, target scope validation,
        // active/inactive selection, persistence, and active runtime install.
        UserManager.getInstance().installSession(target, artifact)
    }
) {
    private data class Flight(
        val target: SessionInstallTarget,
        val gateway: SessionLoginGateway,
        val completed: AtomicBoolean = AtomicBoolean(false),
        /**
         * Interactive readers may wait for a login that was initiated by a
         * different UI surface.  Keep those callbacks separate from the
         * legacy [beginLogin] join notification: LoginActivity and
         * SettingsRoute intentionally retain their existing "already
         * logging in" behavior, while read-only operations may await the
         * completed result and retry once.
         */
        val completionWaiters: MutableList<CoordinatorCallback> = mutableListOf()
    )

    private val flights = ConcurrentHashMap<String, Flight>()
    private val flightLock = Any()

    /**
     * Starts at most one login for an account.  A concurrent caller gets the
     * current in-flight target rather than starting a competing attempt.
     */
    fun beginLogin(
        school: SchoolConfig,
        accountStorageKey: String,
        username: String,
        password: String,
        callback: CoordinatorCallback
    ): SessionInstallTarget = beginLoginInternal(
        school = school,
        accountStorageKey = accountStorageKey,
        username = username,
        password = password,
        callback = callback,
        awaitExistingFlight = false
    )

    /**
     * Starts a login when needed, or waits for the same account's existing
     * flight.  This is deliberately opt-in so old interactive login screens
     * continue to receive only [CoordinatorCallback.onJoinInFlight].
     */
    fun beginLoginAndAwait(
        school: SchoolConfig,
        accountStorageKey: String,
        username: String,
        password: String,
        callback: CoordinatorCallback
    ): SessionInstallTarget = beginLoginInternal(
        school = school,
        accountStorageKey = accountStorageKey,
        username = username,
        password = password,
        callback = callback,
        awaitExistingFlight = true
    )

    private fun beginLoginInternal(
        school: SchoolConfig,
        accountStorageKey: String,
        username: String,
        password: String,
        callback: CoordinatorCallback,
        awaitExistingFlight: Boolean
    ): SessionInstallTarget {
        val key = SessionRegistry.normalizeAccountKey(accountStorageKey)
        var joined: Flight? = null
        var created: Flight? = null
        synchronized(flightLock) {
            val existing = flights[key]
            if (existing != null) {
                joined = existing
                if (awaitExistingFlight) {
                    existing.completionWaiters += callback
                }
            } else {
                val target = SessionRegistry.beginLogin(key, SchoolSessionScope.fromSchool(school))
                created = Flight(target, gatewayFactory(school))
                flights[key] = created
            }
        }
        joined?.let {
            callback.onJoinInFlight(it.target)
            return it.target
        }
        val flight = requireNotNull(created)

        // The closure captures this exact target; no callback needs to trust a
        // bare attempt id supplied by an asynchronous network layer.
        flight.gateway.login(school, username, password, flight.target, object : SessionLoginCallback {
            override fun onSuccess(artifact: SessionArtifact) {
                completeInstall(flight, artifact, callback)
            }

            override fun onCaptchaRequired(imageBytes: ByteArray) {
                callback.onCaptchaRequired(flight.target, imageBytes)
                notifyWaiters(flight) { it.onCaptchaRequired(flight.target, imageBytes) }
            }

            override fun onCaptchaInvalid() {
                callback.onCaptchaInvalid(flight.target)
                notifyWaiters(flight) { it.onCaptchaInvalid(flight.target) }
            }

            override fun onInvalidCredentials() = completeFailure(
                flight = flight,
                primary = { callback.onInvalidCredentials(flight.target) },
                waiter = { it.onInvalidCredentials(flight.target) }
            )

            override fun onError(message: String) = completeFailure(
                flight = flight,
                primary = { callback.onError(flight.target, message) },
                waiter = { it.onError(flight.target, message) }
            )
        })
        return flight.target
    }

    fun submitCaptcha(accountStorageKey: String, captchaCode: String, callback: CoordinatorCallback) {
        val key = SessionRegistry.normalizeAccountKey(accountStorageKey)
        val flight = flights[key] ?: run {
            callback.onError(null, "登录会话已失效，请重新登录")
            return
        }
        flight.gateway.submitCaptcha(captchaCode, object : SessionLoginCallback {
            override fun onSuccess(artifact: SessionArtifact) = completeInstall(flight, artifact, callback)
            override fun onCaptchaRequired(imageBytes: ByteArray) {
                callback.onCaptchaRequired(flight.target, imageBytes)
                notifyWaiters(flight) { it.onCaptchaRequired(flight.target, imageBytes) }
            }

            override fun onCaptchaInvalid() {
                callback.onCaptchaInvalid(flight.target)
                notifyWaiters(flight) { it.onCaptchaInvalid(flight.target) }
            }

            override fun onInvalidCredentials() = completeFailure(
                flight = flight,
                primary = { callback.onInvalidCredentials(flight.target) },
                waiter = { it.onInvalidCredentials(flight.target) }
            )

            override fun onError(message: String) = completeFailure(
                flight = flight,
                primary = { callback.onError(flight.target, message) },
                waiter = { it.onError(flight.target, message) }
            )
        })
    }

    fun refreshCaptcha(accountStorageKey: String, callback: (ByteArray?) -> Unit) {
        flights[SessionRegistry.normalizeAccountKey(accountStorageKey)]?.gateway?.refreshCaptcha(callback)
            ?: callback(null)
    }

    fun cancel(accountStorageKey: String) {
        val key = SessionRegistry.normalizeAccountKey(accountStorageKey)
        val flight = synchronized(flightLock) { flights.remove(key) }
        flight?.let {
            if (!it.completed.compareAndSet(false, true)) return
            SessionRegistry.cancelLogin(it.target)
            it.gateway.clearSensitiveState()
            drainWaiters(it).forEach { waiter ->
                waiter.onError(it.target, "登录会话已取消，请重新登录")
            }
        }
    }

    private fun completeInstall(flight: Flight, artifact: SessionArtifact, callback: CoordinatorCallback) {
        if (!flight.completed.compareAndSet(false, true)) return
        val result = installSession(flight.target, artifact)
        val waiters = drainWaiters(flight)
        flight.gateway.clearSensitiveState()
        callback.onInstalled(flight.target, result)
        waiters.forEach { it.onInstalled(flight.target, result) }
    }

    private inline fun completeFailure(
        flight: Flight,
        primary: () -> Unit,
        waiter: (CoordinatorCallback) -> Unit
    ) {
        if (!flight.completed.compareAndSet(false, true)) return
        val waiters = drainWaiters(flight)
        SessionRegistry.cancelLogin(flight.target)
        flight.gateway.clearSensitiveState()
        primary()
        waiters.forEach(waiter)
    }

    private fun notifyWaiters(flight: Flight, notify: (CoordinatorCallback) -> Unit) {
        if (flight.completed.get()) return
        val waiters = synchronized(flightLock) { flight.completionWaiters.toList() }
        waiters.forEach(notify)
    }

    private fun drainWaiters(flight: Flight): List<CoordinatorCallback> = synchronized(flightLock) {
        flights.remove(flight.target.accountStorageKey, flight)
        flight.completionWaiters.toList().also { flight.completionWaiters.clear() }
    }
}

interface CoordinatorCallback {
    fun onInstalled(target: SessionInstallTarget, result: SessionInstallResult)
    fun onJoinInFlight(target: SessionInstallTarget)
    fun onCaptchaRequired(target: SessionInstallTarget, imageBytes: ByteArray)
    fun onCaptchaInvalid(target: SessionInstallTarget)
    fun onInvalidCredentials(target: SessionInstallTarget)
    fun onError(target: SessionInstallTarget?, message: String)
}
