package com.campusai.features.schedule

import com.campusai.core.model.CourseSchedule

/** Time-slot overlap only. Teaching dates/weeks require review before calling it a conflict. */
internal fun potentialCourseOverlaps(courses: List<CourseSchedule>): List<Pair<CourseSchedule, CourseSchedule>> = buildList {
    courses.forEachIndexed { index, first ->
        courses.drop(index + 1).forEach { second ->
            if (courseSlotsOverlap(first, second))
                add(first to second)
        }
    }
}

/** Missing clock times are unknown, never midnight or an inferred lesson duration. */
internal fun courseSlotsOverlap(first: CourseSchedule, second: CourseSchedule): Boolean {
    if (first.weekday != second.weekday) return false
    if (first.hasPeriods() && second.hasPeriods())
        return first.periodStart <= second.periodEnd && second.periodStart <= first.periodEnd
    return first.startMinute >= 0 && second.startMinute >= 0 &&
        first.endMinute > first.startMinute && second.endMinute > second.startMinute &&
        first.startMinute < second.endMinute && second.startMinute < first.endMinute
}
