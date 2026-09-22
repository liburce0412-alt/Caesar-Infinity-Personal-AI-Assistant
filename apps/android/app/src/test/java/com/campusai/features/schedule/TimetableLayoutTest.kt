package com.campusai.features.schedule

import com.campusai.core.model.CourseSchedule
import org.junit.Assert.*
import org.junit.Test

class TimetableLayoutTest {
    private fun course(name: String, start: Int, end: Int, day: Int = 1) =
        CourseSchedule(name = name, weekday = day, startMinute = start, endMinute = end, sourceHash = name)

    @Test fun `overlapping courses occupy different lanes and touching courses reuse a lane`() {
        val first = course("数学", 480, 580)
        val second = course("英语", 530, 590)
        val third = course("物理", 580, 680)
        val placed = placeDayCourses(listOf(third, second, first))
        assertEquals(listOf(first, second, third), placed.map { it.course })
        assertEquals(listOf(0, 1, 0), placed.map { it.lane })
        assertTrue(first.hasTimeOverlap(listOf(second)))
        assertFalse(first.hasTimeOverlap(listOf(third)))
        assertFalse(first.hasTimeOverlap(listOf(second.copy(weekday = 2))))
    }

    @Test fun `short adjacent classes cannot cover each other's touch targets`() {
        val placed = placeDayCourses(listOf(course("A", 480, 485), course("B", 490, 495), course("C", 520, 525)))
        assertEquals(listOf(0, 1, 0), placed.map { it.lane })
    }
}
