package com.campusai.features.schedule

import com.campusai.core.model.CourseSchedule
import org.junit.Assert.*
import org.junit.Test

class CourseEditTest {
    private val course = CourseSchedule(name = "Java", weekday = 3, startMinute = -1, endMinute = -1,
        sourceHash = "original", periodStart = 1, periodEnd = 2)
    @Test fun `period course supports filling a room without inventing clock times`() {
        validateCourseEdit(course.copy(location = "麦庐园 · 三教 3206", teacher = "张老师", weeks = "第5周"))
        assertEquals(480, parseCourseClock("08:00"))
        assertEquals(1440, parseCourseClock("24:00"))
        assertEquals(-1, parseCourseClock(""))
    }
    @Test fun `rejects invalid edits instead of losing course from grid`() {
        listOf(course.copy(name = ""), course.copy(weekday = 8), course.copy(periodEnd = 0),
            course.copy(startMinute = 600, endMinute = 500), course.copy(periodStartTimes = "480,bad")
        ).forEach { assertTrue(runCatching { validateCourseEdit(it) }.isFailure) }
        assertTrue(runCatching { parseCourseClock("08:90") }.isFailure)
    }
}
