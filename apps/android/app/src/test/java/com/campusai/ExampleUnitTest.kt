package com.campusai

import com.campusai.features.schedule.ScheduleImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleImporterTest {
  @Test fun `ics weekly course becomes editable draft`() {
    val ics = """
      BEGIN:VCALENDAR
      BEGIN:VEVENT
      DTSTART:20260824T080000
      DTEND:20260824T094000
      SUMMARY:数据结构
      LOCATION:第一教学楼 204
      RRULE:FREQ=WEEKLY;COUNT=16
      END:VEVENT
      END:VCALENDAR
    """.trimIndent()
    val result = ScheduleImporter.fromIcsText(ics)
    assertEquals(1, result.size)
    assertEquals("数据结构", result.single().name)
    assertEquals(1, result.single().weekday)
    assertEquals(480, result.single().startMinute)
    assertTrue(result.single().weeks.contains("RRULE:FREQ=WEEKLY;COUNT=16"))
    assertTrue(result.single().reviewNote.isNotBlank())
  }

  @Test fun `course fingerprint is deterministic`() {
    val a = ScheduleImporter.fromIcsText("BEGIN:VEVENT\nDTSTART:20260824T080000\nDTEND:20260824T094000\nSUMMARY:数据结构\nEND:VEVENT")
    val b = ScheduleImporter.fromIcsText("BEGIN:VEVENT\nDTSTART:20260824T080000\nDTEND:20260824T094000\nSUMMARY:数据结构\nEND:VEVENT")
    assertEquals(a.single().toCourse().sourceHash, b.single().toCourse().sourceHash)
  }
}
