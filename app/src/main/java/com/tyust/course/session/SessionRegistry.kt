package com.tyust.course.session

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class SessionState {
    EMPTY,
    ACTIVE,
    INACTIVE,
    EXPIRED
}

data class SessionSnapshot(
    val accountStorageKey: String,
    val artifact: SessionArtifact?,
    val generation: Long,
    val state: SessionState
)

/**
 * Process-local concurrency boundary for account sessions.
 *
 * SessionArtifact and generation are always read as one immutable snapshot.
 * Persisted storage is written by UserManager after a successful transition.
 */
object SessionRegistry {
    private val lock = Any()
    private val snapshots = ConcurrentHashMap<String, SessionSnapshot>()
    private val activeAttempts = ConcurrentHashMap<String, String>()
    private val activeContextEpoch = AtomicLong(0L)

    @JvmStatic
    fun normalizeAccountKey(value: String?): String =
        value.orEmpty().trim().ifBlank { "default" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    @JvmStatic
    fun snapshot(accountStorageKey: String?): SessionSnapshot {
        val key = normalizeAccountKey(accountStorageKey)
        return snapshots[key] ?: SessionSnapshot(key, null, 0L, SessionState.EMPTY)
    }

    @JvmStatic
    fun activeContextEpoch(): Long = activeContextEpoch.get()

    @JvmStatic
    fun bumpActiveContextEpoch(): Long = activeContextEpoch.incrementAndGet()

    @JvmStatic
    fun restore(
        accountStorageKey: String,
        artifact: SessionArtifact?,
        generation: Long,
        active: Boolean
    ): SessionSnapshot = synchronized(lock) {
        val key = normalizeAccountKey(accountStorageKey)
        val snapshot = SessionSnapshot(
            accountStorageKey = key,
            artifact = artifact,
            generation = generation.coerceAtLeast(0L),
            state = when {
                artifact == null -> SessionState.EMPTY
                active -> SessionState.ACTIVE
                else -> SessionState.INACTIVE
            }
        )
        snapshots[key] = snapshot
        snapshot
    }

    @JvmStatic
    fun beginLogin(accountStorageKey: String, scope: SchoolSessionScope): SessionInstallTarget = synchronized(lock) {
        val snapshot = snapshot(accountStorageKey)
        val target = SessionInstallTarget.create(
            accountStorageKey = snapshot.accountStorageKey,
            schoolScope = scope,
            expectedGeneration = snapshot.generation
        )
        activeAttempts[snapshot.accountStorageKey] = target.loginAttemptId
        target
    }

    @JvmStatic
    fun cancelLogin(target: SessionInstallTarget) {
        activeAttempts.remove(normalizeAccountKey(target.accountStorageKey), target.loginAttemptId)
    }

    @JvmStatic
    fun install(
        target: SessionInstallTarget,
        artifact: SessionArtifact,
        active: Boolean
    ): SessionInstallResult = synchronized(lock) {
        val key = normalizeAccountKey(target.accountStorageKey)
        val current = snapshot(key)
        if (activeAttempts[key] != target.loginAttemptId || current.generation != target.expectedGeneration) {
            return SessionInstallResult.StaleTarget
        }
        if (artifact.schoolScope != target.schoolScope) {
            return SessionInstallResult.InvalidScope
        }
        if (artifact is SessionArtifact.RfcCookieBundle &&
            artifact.serviceAudiences.none {
                it.role == AudienceRole.SERVICE_REQUIRED && it.parsedUrl() != null
            }
        ) {
            return SessionInstallResult.UnsupportedArtifact
        }

        val installed = SessionSnapshot(
            accountStorageKey = key,
            artifact = artifact,
            generation = current.generation + 1L,
            state = if (active) SessionState.ACTIVE else SessionState.INACTIVE
        )
        snapshots[key] = installed
        activeAttempts.remove(key, target.loginAttemptId)
        return if (active) {
            SessionInstallResult.InstalledActive(installed)
        } else {
            SessionInstallResult.InstalledInactive(installed)
        }
    }

    @JvmStatic
    fun markConfirmedExpired(accountStorageKey: String, expectedGeneration: Long): SessionSnapshot? =
        synchronized(lock) {
            val key = normalizeAccountKey(accountStorageKey)
            val current = snapshot(key)
            if (current.generation != expectedGeneration || current.artifact == null ||
                current.state == SessionState.EXPIRED
            ) {
                return null
            }
            val expired = current.copy(
                generation = current.generation + 1L,
                state = SessionState.EXPIRED
            )
            snapshots[key] = expired
            activeAttempts.remove(key)
            expired
        }

    @JvmStatic
    fun clear(accountStorageKey: String): SessionSnapshot = synchronized(lock) {
        val key = normalizeAccountKey(accountStorageKey)
        val current = snapshot(key)
        val cleared = SessionSnapshot(key, null, current.generation + 1L, SessionState.EMPTY)
        snapshots[key] = cleared
        activeAttempts.remove(key)
        cleared
    }

    @JvmStatic
    fun isCurrent(accountStorageKey: String, generation: Long): Boolean {
        val current = snapshot(accountStorageKey)
        return current.generation == generation && current.state != SessionState.EXPIRED
    }

    @JvmStatic
    fun clearForTests() = synchronized(lock) {
        snapshots.clear()
        activeAttempts.clear()
        activeContextEpoch.set(0L)
    }
}
