package com.campusai.features.schedule

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ScheduleImportIntegrityTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun event(fields: String) = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nSUMMARY:数学\n$fields\nEND:VEVENT\nEND:VCALENDAR"

    @Test fun `UTC and TZID convert to device time and retain dates`() {
        val utc = ScheduleImporter.fromIcsText(event("DTSTART:20260922T000000Z\nDTEND:20260922T010000Z"), zone).single()
        assertEquals(480, utc.startMinute)
        assertEquals(540, utc.endMinute)
        assertEquals(2, utc.weekday)
        assertTrue(utc.weeks.contains("2026-09-22"))
        assertTrue(utc.reviewNote.contains("单次"))
        val ny = ScheduleImporter.fromIcsText(event("DTSTART;TZID=America/New_York:20260921T200000\nDTEND;TZID=America/New_York:20260921T210000"), zone).single()
        assertEquals(utc.startMinute, ny.startMinute)
        assertEquals(2, ny.weekday)
    }

    @Test fun `interval count until byday and exclusions are preserved for review`() {
        val rule = "RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=8;BYDAY=TU,TH"
        val draft = ScheduleImporter.fromIcsText(event("DTSTART:20260922T080000\nDTEND:20260922T090000\n$rule\nEXDATE:20261006T080000"), zone).single()
        assertTrue(draft.weeks.contains(rule))
        assertTrue(draft.weeks.contains("EXDATE:20261006T080000"))
        assertTrue(draft.reviewNote.isNotBlank())
        assertNotEquals("每周", draft.weeks)
    }

    @Test fun `all day cross midnight and unknown time zones remain unresolved drafts`() {
        for (fields in listOf(
            "DTSTART;VALUE=DATE:20260922\nDTEND;VALUE=DATE:20260923",
            "DTSTART:20260922T235000\nDTEND:20260923T002000",
            "DTSTART;TZID=CustomZone:20260922T080000\nDTEND;TZID=CustomZone:20260922T090000",
            "DTSTART:20260922T080000",
        )) {
            val draft = ScheduleImporter.fromIcsText(event(fields), zone).single()
            assertEquals(-1, draft.startMinute)
            assertEquals(-1, draft.endMinute)
            assertTrue(draft.reviewNote.isNotBlank())
            assertTrue(draft.weeks.contains("DTSTART"))
        }
    }

    @Test fun `course names cannot masquerade as weekday anchors`() {
        val result = runCatching { parseTimetableOcr(listOf(OcrLine("大学英语一", 100, 100, 200, 140), OcrLine("日语", 300, 100, 400, 140))) }
        assertTrue(result.isFailure)
    }

    @Test fun `OCR without real time axis never invents a valid time`() {
        val lines = listOf(OcrLine("周一", 100, 0, 140, 30), OcrLine("周二", 220, 0, 260, 30), OcrLine("数学", 100, 100, 150, 140))
        val unresolved = parseTimetableOcr(lines).single()
        assertEquals(-1, unresolved.startMinute)
        val withAxis = parseTimetableOcr(lines + OcrLine("08:35-09:20", 0, 100, 80, 140)).single()
        assertEquals(515, withAxis.startMinute)
        assertEquals(560, withAxis.endMinute)
        assertTrue(withAxis.reviewNote.isNotBlank())
    }
}
