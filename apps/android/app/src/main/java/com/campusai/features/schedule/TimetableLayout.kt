package com.campusai.features.schedule

import com.campusai.core.model.CourseSchedule

internal data class CoursePlacement(val course: CourseSchedule, val lane: Int)

/** Use visual duration too, so short adjacent courses retain separate touch targets. */
internal fun placeDayCourses(courses: List<CourseSchedule>): List<CoursePlacement> {
    val laneEnds = mutableListOf<Int>()
    return courses.sortedWith(compareBy(CourseSchedule::startMinute, CourseSchedule::endMinute, CourseSchedule::name))
        .map { course ->
            val available = laneEnds.indexOfFirst { it <= course.startMinute }
            val lane = if (available < 0) laneEnds.size.also { laneEnds.add(0) } else available
            laneEnds[lane] = maxOf(course.endMinute, course.startMinute + 40)
            CoursePlacement(course, lane)
        }
}

internal fun CourseSchedule.hasTimeOverlap(courses: List<CourseSchedule>): Boolean = courses.any {
    it != this && it.weekday == weekday && startMinute < it.endMinute && it.startMinute < endMinute
}

internal fun courseClock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
