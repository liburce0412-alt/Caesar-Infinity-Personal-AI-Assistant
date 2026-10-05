package com.campusai.features.schedule

import com.campusai.core.model.CourseSchedule

internal fun parseCourseClock(value: String): Int {
    if (value.isBlank()) return -1
    val match = Regex("(\\d{1,2})[:：](\\d{2})").matchEntire(value.trim())
        ?: error("时间请填写 HH:mm，例如 08:00")
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    require(hour in 0..23 && minute in 0..59 || hour == 24 && minute == 0) { "时间超出范围" }
    return hour * 60 + minute
}

internal fun validateCourseEdit(course: CourseSchedule) {
    require(course.name.isNotBlank()) { "请填写课程名称" }
    require(course.name.length <= 160) { "课程名称不能超过 160 字" }
    require(course.weekday in 1..7) { "星期应为 1 至 7" }
    require(course.hasPeriods() || course.periodStart == 0 && course.periodEnd == 0) { "节次应为 1 至 24，结束节次不能早于开始" }
    val validClock = course.startMinute in 0..1439 && course.endMinute in 1..1440 && course.endMinute > course.startMinute
    require(validClock || course.hasPeriods() && course.startMinute == -1 && course.endMinute == -1) { "请填写正确的起止时间；按节次上课可将两个时间都留空" }
    require(course.periodStartTimes.isBlank() || course.periodStartTimes.split(',').let { times ->
        times.size <= 24 && times.all { it.toIntOrNull() in 0..1439 || it == "-1" }
    }) { "各节上课时间格式不正确" }
}
