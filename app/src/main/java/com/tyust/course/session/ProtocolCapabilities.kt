package com.tyust.course.session

import com.tyust.course.model.SchoolConfig

/**
 * Explicit closed gates for capabilities whose SCNU protocol evidence has not
 * yet been collected under user authorization.  These are deliberately based
 * on canonical scope rather than a mutable school id or display name.
 */
object ScnuProtocolCapabilities {
    const val LOGIN_ENABLED: Boolean = true
    // 成绩接口已用真实教务 Cookie 验证可用（2026-08-12）
    const val ACADEMIC_PROFILE_ENABLED: Boolean = true
    // 选课接口已确认存在且正常响应（非选课季返回"当前不属于选课"），协议本身可用
    const val COURSE_SELECTION_ENABLED: Boolean = true

    @JvmStatic
    fun isCanonicalScnu(school: SchoolConfig?): Boolean = SchoolSessionScope.isCanonicalScnu(school)

    @JvmStatic
    fun isCourseSelectionAllowed(school: SchoolConfig?): Boolean =
        !isCanonicalScnu(school) || COURSE_SELECTION_ENABLED

    @JvmStatic
    fun isAcademicProfileAvailable(school: SchoolConfig?): Boolean =
        !isCanonicalScnu(school) || ACADEMIC_PROFILE_ENABLED

    @JvmStatic
    fun unavailableMessage(): String = "SCNU 协议尚未验证，当前功能不可用"
}

sealed interface AcademicApiMode {
    data object Legacy : AcademicApiMode
    data object ProtocolNotVerified : AcademicApiMode
}

object AcademicApiModeResolver {
    @JvmStatic
    fun resolve(school: SchoolConfig): AcademicApiMode =
        if (ScnuProtocolCapabilities.isAcademicProfileAvailable(school)) {
            AcademicApiMode.Legacy
        } else {
            AcademicApiMode.ProtocolNotVerified
        }
}
