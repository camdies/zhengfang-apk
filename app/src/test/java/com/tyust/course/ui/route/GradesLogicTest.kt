package com.tyust.course.ui.route

import com.tyust.course.ui.screen.GradeItemUi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GradesLogicTest {
    @Test
    fun `overall grades keep recorded numeric and textual results`() {
        assertTrue(GradesLogic.hasRecordedOverallGrade(grade("86.5")))
        assertTrue(GradesLogic.hasRecordedOverallGrade(grade("优秀")))
        assertTrue(GradesLogic.hasRecordedOverallGrade(grade("合格")))
    }

    @Test
    fun `overall grades exclude blank and established missing-grade marker`() {
        assertFalse(GradesLogic.hasRecordedOverallGrade(grade("")))
        assertFalse(GradesLogic.hasRecordedOverallGrade(grade("  ")))
        assertFalse(GradesLogic.hasRecordedOverallGrade(grade("--")))
    }

    private fun grade(value: String) = GradeItemUi(
        courseName = "synthetic-course",
        grade = value,
        credits = "1",
        gpa = "0",
        courseType = ""
    )
}
