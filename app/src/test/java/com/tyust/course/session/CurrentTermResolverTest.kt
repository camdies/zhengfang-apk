package com.tyust.course.session

import com.tyust.course.model.SchoolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

class CurrentTermResolverTest {
    @Test
    fun `canonical SCNU without a verified term or account cache reports protocol not verified`() {
        val resolver = TermResolver(calendarProvider = { fixedCalendar(2026, Calendar.MARCH) })

        val result = resolver.resolveCurrentTerm(canonicalScnu(), "scnu-account")

        assertEquals(CurrentTermResolution.ProtocolNotVerified, result)
    }

    @Test
    fun `legacy school falls back to its existing calendar term`() {
        val resolver = TermResolver(calendarProvider = { fixedCalendar(2026, Calendar.MARCH) })

        val result = resolver.resolveCurrentTerm(legacySchool(), "legacy-account")

        val available = assertAvailable(result)
        assertEquals("2025", available.term.academicYear)
        assertEquals("12", available.term.termCode)
        assertEquals(AcademicTermSource.LEGACY_CALENDAR, available.term.source)
    }

    @Test
    fun `verified term cache is restricted to the same account and school`() {
        val resolver = TermResolver(calendarProvider = { fixedCalendar(2026, Calendar.AUGUST) })
        val cached = AcademicTerm(
            academicYear = "2099",
            termCode = "7",
            label = "verified-cache",
            source = AcademicTermSource.VERIFIED_ENDPOINT
        )
        val school = legacySchool()
        resolver.rememberVerifiedTerm(school, "account-a", cached)

        val sameAccount = assertAvailable(resolver.resolveCurrentTerm(school, "account-a"))
        assertEquals("2099", sameAccount.term.academicYear)
        assertEquals("7", sameAccount.term.termCode)
        assertEquals(AcademicTermSource.ACCOUNT_CACHE, sameAccount.term.source)

        val otherAccount = assertAvailable(resolver.resolveCurrentTerm(school, "account-b"))
        assertEquals("2026", otherAccount.term.academicYear)
        assertEquals("3", otherAccount.term.termCode)
        assertEquals(AcademicTermSource.LEGACY_CALENDAR, otherAccount.term.source)

        val otherSchool = legacySchool().apply { id = "another-school" }
        val otherScope = assertAvailable(resolver.resolveCurrentTerm(otherSchool, "account-a"))
        assertEquals("2026", otherScope.term.academicYear)
        assertEquals("3", otherScope.term.termCode)
        assertEquals(AcademicTermSource.LEGACY_CALENDAR, otherScope.term.source)
    }

    private fun assertAvailable(result: CurrentTermResolution): CurrentTermResolution.Available {
        assertTrue("expected an available term but got $result", result is CurrentTermResolution.Available)
        return result as CurrentTermResolution.Available
    }

    private fun fixedCalendar(year: Int, month: Int): Calendar = GregorianCalendar(year, month, 1)

    private fun legacySchool(): SchoolConfig = SchoolConfig(
        "tyust",
        "TYUST",
        "newjwc.tyust.edu.cn",
        "https"
    ).apply {
        basePath = "/jwglxt"
    }

    private fun canonicalScnu(): SchoolConfig = SchoolConfig(
        "scnu",
        "SCNU",
        "jwxt.scnu.edu.cn",
        "https"
    ).apply {
        basePath = ""
    }
}
