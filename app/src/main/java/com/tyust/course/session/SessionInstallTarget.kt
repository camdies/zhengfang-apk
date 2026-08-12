package com.tyust.course.session

import java.util.UUID

data class SessionInstallTarget(
    val loginAttemptId: String,
    val accountStorageKey: String,
    val schoolScope: SchoolSessionScope,
    val expectedGeneration: Long
) {
    companion object {
        @JvmStatic
        fun create(
            accountStorageKey: String,
            schoolScope: SchoolSessionScope,
            expectedGeneration: Long
        ): SessionInstallTarget = SessionInstallTarget(
            loginAttemptId = UUID.randomUUID().toString(),
            accountStorageKey = accountStorageKey,
            schoolScope = schoolScope,
            expectedGeneration = expectedGeneration
        )
    }
}

sealed interface SessionInstallResult {
    data class InstalledActive(val snapshot: SessionSnapshot) : SessionInstallResult
    data class InstalledInactive(val snapshot: SessionSnapshot) : SessionInstallResult
    data object InvalidScope : SessionInstallResult
    data object StaleTarget : SessionInstallResult
    data object UnsupportedArtifact : SessionInstallResult
}
