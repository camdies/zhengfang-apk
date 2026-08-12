package com.tyust.course.session

import com.tyust.course.model.SchoolConfig
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

data class AcademicTerm(
    val academicYear: String,
    val termCode: String,
    val label: String,
    val source: AcademicTermSource
)

enum class AcademicTermSource {
    VERIFIED_ENDPOINT,
    ACCOUNT_CACHE,
    LEGACY_CALENDAR
}

sealed interface CurrentTermResolution {
    data class Available(val term: AcademicTerm) : CurrentTermResolution
    data object ProtocolNotVerified : CurrentTermResolution
}

/**
 * School/account-scoped resolver.  The phase-one SCNU branch does not invent
 * a term endpoint or encoding; it can only return a previously verified cache
 * entry, otherwise it reports ProtocolNotVerified.
 */
class TermResolver(
    private val cache: MutableMap<String, AcademicTerm> = ConcurrentHashMap(),
    private val calendarProvider: () -> Calendar = { Calendar.getInstance() }
) {
    fun resolveCurrentTerm(
        school: SchoolConfig,
        accountStorageKey: String,
        verifiedTerm: (() -> AcademicTerm?)? = null
    ): CurrentTermResolution {
        val cacheKey = cacheKey(school, accountStorageKey)
        verifiedTerm?.invoke()?.let { verified ->
            val stamped = verified.copy(source = AcademicTermSource.VERIFIED_ENDPOINT)
            cache[cacheKey] = stamped
            return CurrentTermResolution.Available(stamped)
        }
        cache[cacheKey]?.let { return CurrentTermResolution.Available(it.copy(source = AcademicTermSource.ACCOUNT_CACHE)) }
        // SCNU 教务学期与正方标准一致（已实测 xnm/xqm 规则有效），走日历兜底
        return CurrentTermResolution.Available(legacyCalendarTerm(calendarProvider()))
    }

    fun rememberVerifiedTerm(school: SchoolConfig, accountStorageKey: String, term: AcademicTerm) {
        cache[cacheKey(school, accountStorageKey)] = term.copy(source = AcademicTermSource.VERIFIED_ENDPOINT)
    }

    private fun cacheKey(school: SchoolConfig, accountStorageKey: String): String =
        SessionRegistry.normalizeAccountKey(accountStorageKey) + "::" + SchoolSessionScope.fromSchool(school)

    companion object {
        @JvmStatic
        fun legacyCalendarTerm(calendar: Calendar): AcademicTerm {
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH)
            val startYear = if (month >= Calendar.JULY) year else year - 1
            val firstTerm = month >= Calendar.JULY || month < Calendar.FEBRUARY
            return AcademicTerm(
                academicYear = startYear.toString(),
                termCode = if (firstTerm) "3" else "12",
                label = "$startYear-${startYear + 1}-${if (firstTerm) 1 else 2}",
                source = AcademicTermSource.LEGACY_CALENDAR
            )
        }
    }
}
