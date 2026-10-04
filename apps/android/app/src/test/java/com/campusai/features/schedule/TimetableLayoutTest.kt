package com.campusai.features.schedule

import com.campusai.core.model.CourseSchedule
import org.junit.Assert.*
import org.junit.Test

class TimetableLayoutTest {
    private fun course(name: String, start: Int, end: Int, day: Int = 1) =
        CourseSchedule(name = name, weekday = day, startMinute = start, endMinute = end, sourceHash = name)

    @Test fun `period overlaps work without fabricated end clocks`() {
        val a = course("A", 840, -1).copy(periodStart = 6, periodEnd = 7)
        val b = course("B", -1, -1).copy(periodStart = 7, periodEnd = 8)
        val c = course("C", 955, -1).copy(periodStart = 8, periodEnd = 9)
        assertTrue(courseSlotsOverlap(a, b))
        assertFalse(courseSlotsOverlap(a, c))
        assertFalse(courseSlotsOverlap(a, b.copy(weekday = 2)))
        assertEquals("第6–7节 · 14:00起", courseTimeLabel(a))
        assertEquals("第7–8节", courseTimeLabel(b))
    }

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
    @Test fun `connected overlaps group without widening or dropping courses`() {
        val input = listOf(course("A", 480, 540), course("B", 530, 600), course("C", 590, 630), course("D", 650, 710))
        assertEquals(listOf(input.take(3), input.takeLast(1)), groupDayCourses(input.reversed()))
    }

    @Test fun `short adjacent courses share accessible group but distant courses do not`() {
        val input = listOf(course("A", 480, 485), course("B", 490, 495), course("C", 540, 550))
        assertEquals(listOf(input.take(2), input.takeLast(1)), groupDayCourses(input))
    }
}
