package com.campusai.features.schedule

import com.campusai.core.model.CourseSchedule

/** Time-slot overlap only. Teaching dates/weeks require review before calling it a conflict. */
internal fun potentialCourseOverlaps(courses: List<CourseSchedule>): List<Pair<CourseSchedule, CourseSchedule>> = buildList {
    courses.forEachIndexed { index, first ->
        courses.drop(index + 1).forEach { second ->
            if (first.weekday == second.weekday && first.startMinute < second.endMinute && second.startMinute < first.endMinute)
                add(first to second)
        }
    }
}
