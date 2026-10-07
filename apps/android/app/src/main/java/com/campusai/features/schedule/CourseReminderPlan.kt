package com.campusai.features.schedule

import com.campusai.core.model.CourseSchedule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal fun timetableWeekMonday(semesterMonday: String, selectedWeek: String): LocalDate? {
    val monday = runCatching { LocalDate.parse(semesterMonday) }.getOrNull() ?: return null
    if (monday.dayOfWeek != DayOfWeek.MONDAY) return null
    val week = Regex("第?(\\d{1,2})周(?:（截图）)?").matchEntire(selectedWeek)?.groupValues?.get(1)?.toLongOrNull() ?: return null
    if (week !in 1..30) return null
    return monday.plusWeeks(week - 1)
}

internal fun coursesForTeachingWeek(courses: List<CourseSchedule>, semesterMonday: LocalDate, weekMonday: LocalDate, semesterWeeks: Int): List<CourseSchedule> {
    val week = (ChronoUnit.DAYS.between(semesterMonday, weekMonday) / 7 + 1).toInt()
    return courses.filter { course ->
        when (val rule = courseDates(course.weeks, semesterWeeks)) {
            is CourseDates.Weeks -> week in rule.values
            is CourseDates.Once -> !rule.date.isBefore(weekMonday) && rule.date.isBefore(weekMonday.plusDays(7))
            is CourseDates.Invalid -> false
        }
    }
}

/** A reminder needs a real date, not the currently selected overview week. */
internal enum class CourseIslandAppearance { AURORA, COLORFUL }

internal data class CourseReminderConfig(
    val enabled: Boolean = false,
    val semesterMonday: String = "",
    val semesterWeeks: Int = 20,
    val leadMinutes: Int = 15,
    val live: Boolean = false,
    val xiaomi: Boolean = false,
    val islandAppearance: CourseIslandAppearance = CourseIslandAppearance.AURORA,
)

internal data class CourseOccurrence(
    val course: CourseSchedule,
    val date: LocalDate,
    val start: Long,
    val end: Long,
) {
    val key: String get() = "${course.id}:$date:$start"
}

internal sealed interface CourseDates {
    data class Weeks(val values: Set<Int>) : CourseDates
    data class Once(val date: LocalDate) : CourseDates
    data class Invalid(val reason: String) : CourseDates
}

internal fun courseDates(value: String, semesterWeeks: Int): CourseDates {
    val raw = value.trim()
    if (raw.isBlank()) return CourseDates.Invalid("请填写周次或具体日期")
    // Imported calendars retain raw recurrence/exception fields. Never flatten them to weekly.
    if (raw.contains(Regex("(?i)(DTSTART|DTEND|RRULE|RDATE|EXDATE|RECURRENCE-ID|DURATION):|(?i)DTSTART;"))) {
        return CourseDates.Invalid("日历日期规则需先核对，填写明确周次或单次日期")
    }
    val date = raw.removePrefix("日期").trim()
    if (Regex("\\d{4}-\\d{2}-\\d{2}").matches(date)) {
        return runCatching { CourseDates.Once(LocalDate.parse(date)) }
            .getOrElse { CourseDates.Invalid("日期无效") }
    }
    var text = raw.replace("（截图）", "").replace("(截图)", "").replace(Regex("\\s"), "")
    val odd = text.contains("单")
    val even = text.contains("双")
    if (odd && even) return CourseDates.Invalid("单双周规则不明确")
    text = text.replace(Regex("[单双第周()（）]"), "")
        .replace(Regex("[–—~～至]"), "-").replace(Regex("[，、]"), ",")
    val values = if (text.isBlank() && (odd || even) || raw == "每周") {
        (1..semesterWeeks).toSet()
    } else {
        if (!Regex("\\d{1,2}(-\\d{1,2})?(,\\d{1,2}(-\\d{1,2})?)*").matches(text)) {
            return CourseDates.Invalid("无法识别周次，请用 1–16周、1–16周(单) 或 第5周")
        }
        val result = mutableSetOf<Int>()
        for (part in text.split(',')) {
            val range = part.split('-').map(String::toInt)
            val first = range.first()
            val last = range.last()
            if (first < 1 || last > semesterWeeks || first > last) return CourseDates.Invalid("周次超出学期范围")
            result.addAll(first..last)
        }
        result
    }
    return CourseDates.Weeks(values.filter { !odd && !even || odd && it % 2 == 1 || even && it % 2 == 0 }.toSet())
}

internal fun reminderIssue(course: CourseSchedule, config: CourseReminderConfig): String? {
    if (course.weekday !in 1..7) return "星期无效"
    if (course.startMinute !in 0..1439 || course.endMinute !in 1..1440 || course.endMinute <= course.startMinute) {
        return "请补全真实起止时间，只有节次不能定时提醒"
    }
    return when (val dates = courseDates(course.weeks, config.semesterWeeks)) {
        is CourseDates.Invalid -> dates.reason
        is CourseDates.Weeks -> {
            val monday = runCatching { LocalDate.parse(config.semesterMonday) }.getOrNull()
            if (monday?.dayOfWeek != DayOfWeek.MONDAY) "请设置第1周的周一日期" else null
        }
        is CourseDates.Once -> if (dates.date.dayOfWeek.value != course.weekday) "日期与星期不一致，请核对" else null
    }
}

internal fun courseOccurrences(
    courses: List<CourseSchedule>, config: CourseReminderConfig, from: LocalDate, zone: ZoneId,
    days: Long = 8,
): List<CourseOccurrence> {
    return courses.flatMap { course ->
        if (reminderIssue(course, config) != null) return@flatMap emptyList()
        val rule = courseDates(course.weeks, config.semesterWeeks)
        val monday = runCatching { LocalDate.parse(config.semesterMonday) }.getOrNull()
        (0 until days).mapNotNull { offset ->
            val date = from.plusDays(offset)
            if (date.dayOfWeek.value != course.weekday) return@mapNotNull null
            val matches = when (rule) {
                is CourseDates.Once -> date == rule.date
                is CourseDates.Weeks -> {
                    val daysSince = ChronoUnit.DAYS.between(monday!!, date)
                    daysSince >= 0 && (daysSince / 7 + 1).toInt() in rule.values
                }
                is CourseDates.Invalid -> false
            }
            if (!matches) return@mapNotNull null
            val start = date.atStartOfDay().plusMinutes(course.startMinute.toLong())
            val end = date.atStartOfDay().plusMinutes(course.endMinute.toLong())
            // Do not silently shift nonexistent/ambiguous local times at DST boundaries.
            if (zone.rules.getValidOffsets(start).size != 1 || zone.rules.getValidOffsets(end).size != 1) return@mapNotNull null
            CourseOccurrence(course, date, start.atZone(zone).toInstant().toEpochMilli(), end.atZone(zone).toInstant().toEpochMilli())
        }
    }.sortedWith(compareBy(CourseOccurrence::start, { it.course.id }))
}
