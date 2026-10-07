package com.campusai.features.schedule

import com.campusai.core.model.CourseSchedule
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CourseReminderPlanTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val config = CourseReminderConfig(true, "2026-09-07")
    private val course = CourseSchedule(1, "Java程序设计", 3, 480, 580, "S402", weeks = "2-17周", sourceHash = "a")

    @Test fun `fifth teaching week dates match verified Xique timetable`() {
        assertEquals(LocalDate.of(2026, 10, 5), timetableWeekMonday(config.semesterMonday, "第5周（截图）"))
        assertEquals(LocalDate.of(2026, 9, 28), timetableWeekMonday(config.semesterMonday, "第4周"))
        assertNull(timetableWeekMonday(config.semesterMonday, "全部"))
        assertNull(timetableWeekMonday(config.semesterMonday, "2-17周"))
    }
    @Test fun `odd even and explicit weeks resolve without guessing`() {
        assertEquals(setOf(1, 3, 5, 7), (courseDates("1–8周（单）", 20) as CourseDates.Weeks).values)
        assertEquals(setOf(2, 4, 6, 8), (courseDates("1-8周(双)", 20) as CourseDates.Weeks).values)
        assertEquals(setOf(1, 3, 5, 6), (courseDates("1,3,5-6周", 20) as CourseDates.Weeks).values)
        assertEquals(setOf(5), (courseDates("第5周（截图）", 20) as CourseDates.Weeks).values)
    }
    @Test fun `unknown recurrence and invalid clocks remain ineligible`() {
        listOf("", "待定", "8-2周", "0周", "1-99周", "1-8周单双", "日期 2026-10-07\nDTSTART:20261007T080000\nRRULE:FREQ=WEEKLY")
            .forEach { assertTrue(it, courseDates(it, 20) is CourseDates.Invalid) }
        assertNotNull(reminderIssue(course.copy(startMinute = -1, endMinute = -1, periodStart = 1, periodEnd = 2), config))
        assertNotNull(reminderIssue(course, config.copy(semesterMonday = "")))
        assertNotNull(reminderIssue(course, config.copy(semesterMonday = "2026-09-08")))
    }
    @Test fun `occurrences use semester date not selected overview and respect parity`() {
        val occurrences = courseOccurrences(listOf(course), config, LocalDate.of(2026, 10, 5), zone, 7)
        assertEquals(1, occurrences.size)
        assertEquals(LocalDate.of(2026, 10, 7), occurrences.single().date)
        assertEquals(40 + 60, ((occurrences.single().end - occurrences.single().start) / 60000).toInt())
        assertTrue(courseOccurrences(listOf(course.copy(weeks = "2-17周(双)")), config, LocalDate.of(2026, 10, 5), zone, 7).isEmpty())
        assertTrue(courseOccurrences(listOf(course), config, LocalDate.of(2026, 9, 1), zone, 7).isEmpty())
    }
    @Test fun `one off holiday reschedule never turns into weekly recurrence`() {
        val once = course.copy(weeks = "2026-10-07")
        assertEquals(1, courseOccurrences(listOf(once), config.copy(semesterMonday = ""), LocalDate.of(2026, 10, 5), zone, 20).size)
        assertNotNull(reminderIssue(once.copy(weekday = 4), config))
    }
    @Test fun `semester rollover midnight and overlapping lessons preserve identity`() {
        val overlap = course.copy(id = 2)
        val all = courseOccurrences(listOf(course, overlap), config, LocalDate.of(2026, 10, 7), zone, 1)
        assertEquals(2, all.map { it.key }.distinct().size)
        assertTrue(courseOccurrences(listOf(course), config, LocalDate.of(2027, 3, 1), zone).isEmpty())
        val midnight = course.copy(startMinute = 1380, endMinute = 1440)
        assertEquals(60, ((courseOccurrences(listOf(midnight), config, LocalDate.of(2026, 10, 7), zone, 1).single().end -
            courseOccurrences(listOf(midnight), config, LocalDate.of(2026, 10, 7), zone, 1).single().start) / 60000).toInt())
    }
}
