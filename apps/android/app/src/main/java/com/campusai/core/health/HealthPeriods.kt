package com.campusai.core.health

import com.campusai.core.time.CaesarTime

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

object HealthPeriods {
    fun parse(value: String, nowMillis: Long = System.currentTimeMillis(), zone: ZoneId = CaesarTime.zone): HealthPeriod {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val today = now.toLocalDate()
        val startDate = when (value.lowercase()) {
            "today", "day", "今天", "今日" -> today
            "week", "本周", "这周" -> today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            "month", "本月", "这个月" -> today.withDayOfMonth(1)
            "yesterday", "昨天" -> today.minusDays(1)
            else -> today
        }
        val end = if (value.lowercase() in setOf("yesterday", "昨天")) startDate.plusDays(1).atStartOfDay(zone)
        else now
        return HealthPeriod(
            startEpochMillis = startDate.atStartOfDay(zone).toInstant().toEpochMilli(),
            endEpochMillis = end.toInstant().toEpochMilli(),
            key = value.lowercase().ifBlank { "today" },
        )
    }
}
