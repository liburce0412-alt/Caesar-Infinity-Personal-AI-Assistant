package com.campusai.features.schedule

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Retains calendar semantics in the existing weeks field; the timetable is an overview,
 * not a recurrence engine. Rules and dates must never silently become "every week". */
internal object IcsCourseParser {
    private data class Property(val name: String, val parameters: Map<String, String>, val value: String, val raw: String)

    fun parse(raw: String, zone: ZoneId): List<CourseDraft> {
        require(raw.length <= 1_000_000) { "日历文件超过 1 MB" }
        val unfolded = raw.replace(Regex("\r?\n[ \t]"), "")
        return Regex("BEGIN:VEVENT(.*?)END:VEVENT", RegexOption.DOT_MATCHES_ALL).findAll(unfolded).mapNotNull { event ->
            val properties = event.groupValues[1].lineSequence().mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator < 0) return@mapNotNull null
                val parts = line.substring(0, separator).split(';')
                Property(parts.first().uppercase(), parts.drop(1).mapNotNull {
                    val pair = it.split('=', limit = 2)
                    if (pair.size == 2) pair[0].uppercase() to pair[1].trim('"') else null
                }.toMap(), line.substring(separator + 1).trim(), line.trim())
            }.toList()
            fun property(name: String) = properties.firstOrNull { it.name == name }
            val name = property("SUMMARY")?.value?.unescape()?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val start = runCatching { dateTime(requireNotNull(property("DTSTART")), zone) }.getOrNull()
            val end = runCatching { dateTime(requireNotNull(property("DTEND")), zone) }.getOrNull()
            val sameDay = start != null && end != null && start.toLocalDate() == end.toLocalDate() && end.isAfter(start)
            val calendarFields = properties.filter { it.name in setOf("DTSTART", "DTEND", "DURATION", "RRULE", "RDATE", "EXDATE", "RECURRENCE-ID") }
            val recurring = calendarFields.any { it.name in setOf("RRULE", "RDATE", "EXDATE", "RECURRENCE-ID") }
            val detail = buildList {
                if (start != null) add("日期 ${start.toLocalDate()} · ${zone.id}")
                addAll(calendarFields.map { it.raw })
            }.joinToString("\n")
            CourseDraft(
                name = name,
                weekday = start?.dayOfWeek?.value ?: 1,
                startMinute = if (sameDay) start!!.hour * 60 + start.minute else -1,
                endMinute = if (sameDay) end!!.hour * 60 + end.minute else -1,
                location = property("LOCATION")?.value?.unescape().orEmpty(),
                teacher = property("DESCRIPTION")?.value?.unescape()?.lineSequence()
                    ?.firstOrNull { it.contains("教师") || it.contains("老师") }?.substringAfter(':').orEmpty(),
                weeks = detail,
                reviewNote = when {
                    !sameDay -> "全天、跨午夜、缺失结束时间或不支持的时区：请按原始日期拆分或补全时间；不会自动猜测时段。"
                    recurring -> "已保留重复与排除规则。当前周表不展开具体授课日期，请核对星期、周次及例外日期后确认。"
                    else -> "这是 ${start!!.toLocalDate()} 的单次事件，周表仅展示安排概览，不表示每周重复。"
                },
            )
        }.distinctBy { it.toCourse().sourceHash }.toList()
    }

    private fun dateTime(property: Property, zone: ZoneId): ZonedDateTime {
        require(property.parameters["VALUE"] != "DATE" && 'T' in property.value)
        val utc = property.value.endsWith('Z')
        val text = property.value.removeSuffix("Z")
        val pattern = if (text.length == 13) "yyyyMMdd'T'HHmm" else "yyyyMMdd'T'HHmmss"
        val local = LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern))
        val sourceZone = if (utc) ZoneOffset.UTC else property.parameters["TZID"]?.let(ZoneId::of) ?: zone
        // Reject nonexistent DST wall times rather than silently shifting a lesson.
        require(sourceZone.rules.getValidOffsets(local).size == 1) { "时间处于时区切换区间" }
        return local.atZone(sourceZone).withZoneSameInstant(zone)
    }

    private fun String.unescape() = replace("\\n", "\n", ignoreCase = true)
        .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
}
