package com.campusai.core.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Completed records belong to their local completion day. Weeks start on Monday. */
object TimeRecordCalendar {
    fun completionDate(record: TimeRecord, zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
        if (record.durationMinutes > 0 && record.endTime > record.startTime)
            Instant.ofEpochMilli(record.endTime).atZone(zone).toLocalDate() else null

    fun inRange(records: List<TimeRecord>, range: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): List<TimeRecord> {
        val today = now.atZone(zone).toLocalDate()
        val from = when (range) {
            "周" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            "月" -> today.withDayOfMonth(1)
            else -> today
        }
        return records.filter { record ->
            val day = completionDate(record, zone)
            day != null && !day.isBefore(from) && !day.isAfter(today) && record.endTime <= now.toEpochMilli()
        }
    }

    fun streak(records: List<TimeRecord>, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Int {
        val days = records.filter { it.endTime <= now.toEpochMilli() }.mapNotNull { completionDate(it, zone) }.toSet()
        var day = now.atZone(zone).toLocalDate()
        if (day !in days) day = day.minusDays(1)
        var count = 0
        while (day in days) { count++; day = day.minusDays(1) }
        return count
    }
}
