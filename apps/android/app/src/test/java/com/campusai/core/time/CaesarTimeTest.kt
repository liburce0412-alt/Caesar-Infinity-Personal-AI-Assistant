package com.campusai.core.time

import com.campusai.core.health.HealthPeriods
import com.campusai.core.model.DailyContributionCalculator
import com.campusai.core.model.TimeRecord
import com.campusai.core.model.TimeRecordCalendar
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone

class CaesarTimeTest {
    @Test fun `remote UTC and offset timestamps describe the same Beijing display`() {
        val utc = "2026-10-17T16:03:04.123456Z"
        val offset = "2026-10-18T00:03:04.123456+08:00"
        assertEquals(CaesarTime.parseRemoteInstant(utc), CaesarTime.parseRemoteInstant(offset))
        assertEquals("2026-10-18 00:03", CaesarTime.formatRemote(utc))
        assertEquals(CaesarTime.formatRemote(utc), CaesarTime.formatRemote(offset))
        assertEquals("2026-10-18 00:03", CaesarTime.formatRemote("2026-10-17 16:03:04+00:00"))
    }

    @Test fun `unknown or offsetless timestamps do not pretend to be recent`() {
        for (value in listOf("", "not-a-time", "2026-10-18", "2026-10-18T00:03:04", "2026-02-30T01:00:00Z")) {
            assertNull(value, CaesarTime.parseRemoteInstant(value))
            assertEquals("时间未知", CaesarTime.formatRemote(value))
        }
    }

    @Test fun `Beijing midnight changes the date independent of the device zone`() = inOtherDeviceZones {
        assertEquals(LocalDate.of(2026, 10, 17), CaesarTime.today(Instant.parse("2026-10-17T15:59:59Z")))
        assertEquals(LocalDate.of(2026, 10, 18), CaesarTime.today(Instant.parse("2026-10-17T16:00:00Z")))
        assertEquals("10-18 00:00", CaesarTime.format(Instant.parse("2026-10-17T16:00:00Z").toEpochMilli()))
    }

    @Test fun `stats streak contributions and health use the same Beijing day`() = inOtherDeviceZones {
        val now = Instant.parse("2026-06-01T16:30:00Z")
        val current = record("2026-06-01T15:50:00Z", "2026-06-01T16:10:00Z")
        val previous = record("2026-05-31T16:30:00Z", "2026-05-31T16:50:00Z")
        val rows = listOf(previous, current)
        assertEquals(LocalDate.of(2026, 6, 2), TimeRecordCalendar.completionDate(current))
        assertEquals(listOf(current), TimeRecordCalendar.inRange(rows, "日", now))
        assertEquals(rows, TimeRecordCalendar.inRange(rows, "周", now))
        assertEquals(2, TimeRecordCalendar.streak(rows, now))
        val contribution = DailyContributionCalculator.calculate(2026, rows, today = CaesarTime.today(now))
            .first { it.date == LocalDate.of(2026, 6, 2) }
        assertEquals(20L, contribution.durationMinutes)
        val health = HealthPeriods.parse("today", now.toEpochMilli())
        assertEquals(Instant.parse("2026-06-01T16:00:00Z").toEpochMilli(), health.startEpochMillis)
        assertEquals(now.toEpochMilli(), health.endEpochMillis)
        val yesterday = HealthPeriods.parse("yesterday", now.toEpochMilli())
        assertEquals(Instant.parse("2026-05-31T16:00:00Z").toEpochMilli(), yesterday.startEpochMillis)
        assertEquals(health.startEpochMillis, yesterday.endEpochMillis)
    }

    @Test fun `legacy date keys and picker UTC encoding remain calendar dates`() = inOtherDeviceZones {
        val key = "2026-01-01"
        val date = LocalDate.of(2026, 1, 1)
        assertEquals(date, CaesarTime.parseDateKey(key))
        val pickerMillis = checkNotNull(CaesarTime.datePickerMillis(key))
        assertEquals(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), pickerMillis)
        assertEquals(key, CaesarTime.dateKeyFromPickerMillis(pickerMillis))
        assertNull(CaesarTime.datePickerMillis("2026-02-30"))
        val historicalGoal = mapOf(checkNotNull(CaesarTime.parseDateKey(key)) to 90L)
        val row = record("2025-12-31T16:10:00Z", "2025-12-31T16:30:00Z")
        val calendar = DailyContributionCalculator.calculate(2026, listOf(row), historicalGoal, today = date)
        assertEquals(90L, calendar.first().targetMinutes)
        assertEquals(20L, calendar.first().durationMinutes)
        assertEquals(Instant.parse("2025-12-31T16:30:00Z").toEpochMilli(), row.endTime)
    }

    private fun record(start: String, end: String) = TimeRecord(
        title = "学习", category = "学习", startTime = Instant.parse(start).toEpochMilli(),
        endTime = Instant.parse(end).toEpochMilli(), durationMinutes = 20, remark = "",
    )

    private fun inOtherDeviceZones(block: () -> Unit) {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("America/Los_Angeles", "Pacific/Kiritimati", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                block()
            }
        } finally { TimeZone.setDefault(original) }
    }
}
