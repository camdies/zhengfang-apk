package com.tyust.course.session

import com.tyust.course.model.SchoolConfig

/**
 * Immutable request identity captured when a request is built.
 *
 * A response must always be interpreted against this object rather than the
 * school or account selected when the response happens to arrive.  In
 * particular, callers must not derive a school from a host when this tag is
 * absent: an untagged protected response is deliberately indeterminate.
 */
data class SessionRequestContext @JvmOverloads constructor(
    val schoolScope: SchoolSessionScope,
    val accountStorageKey: String,
    val sessionGeneration: Long,
    val purpose: SessionRequestPurpose,
    val owner: SessionRequestOwner,
    val activeContextEpoch: Long? = null,
    val serviceRunId: Long? = null
) {
    val normalizedAccountStorageKey: String
        get() = SessionRegistry.normalizeAccountKey(accountStorageKey)

    fun isSnapshotCurrent(
        currentActiveContextEpoch: Long = SessionRegistry.activeContextEpoch(),
        currentServiceRunId: Long? = serviceRunId
    ): Boolean {
        if (!SessionRegistry.isCurrent(normalizedAccountStorageKey, sessionGeneration)) return false
        if (owner == SessionRequestOwner.UI && activeContextEpoch != null &&
            activeContextEpoch != currentActiveContextEpoch
        ) return false
        if (owner == SessionRequestOwner.SERVICE && serviceRunId != null &&
            currentServiceRunId != null && serviceRunId != currentServiceRunId
        ) return false
        return true
    }

    companion object {
        /**
         * Builds a context from one registry snapshot.  This is the only
         * supported construction path for protected API requests.
         */
        @JvmStatic
        @JvmOverloads
        fun forSchool(
            school: SchoolConfig,
            accountStorageKey: String,
            purpose: SessionRequestPurpose,
            owner: SessionRequestOwner = SessionRequestOwner.BACKGROUND,
            activeContextEpoch: Long? = if (owner == SessionRequestOwner.UI) {
                SessionRegistry.activeContextEpoch()
            } else {
                null
            },
            serviceRunId: Long? = null
        ): SessionRequestContext {
            val snapshot = SessionRegistry.snapshot(accountStorageKey)
            return SessionRequestContext(
                schoolScope = SchoolSessionScope.fromSchool(school),
                accountStorageKey = snapshot.accountStorageKey,
                sessionGeneration = snapshot.generation,
                purpose = purpose,
                owner = owner,
                activeContextEpoch = activeContextEpoch,
                serviceRunId = serviceRunId
            )
        }
    }
}

enum class SessionRequestPurpose {
    LOGIN_FLOW,
    SESSION_PROBE,
    ACADEMIC_QUERY,
    WATCHDOG,
    GRAB_SERVICE,
    PUBLIC
}

enum class SessionRequestOwner {
    UI,
    SERVICE,
    BACKGROUND
}
