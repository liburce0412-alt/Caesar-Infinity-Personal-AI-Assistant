package com.campusai

import com.campusai.core.model.TimeRecord
import com.campusai.core.model.TimeRecordCalendar
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TimeRecordCalendarTest {
    private fun record(start:String,end:String,minutes:Long=30) = TimeRecord(title="Study",category="学习",
        startTime=Instant.parse(start).toEpochMilli(),endTime=Instant.parse(end).toEpochMilli(),durationMinutes=minutes,remark="")
    @Test fun `midnight month and Monday boundaries use completion date`() {
        val zone=ZoneId.of("UTC")
        val now=Instant.parse("2026-06-01T10:00:00Z")
        val cross=record("2026-05-31T23:50:00Z","2026-06-01T00:20:00Z")
        val future=record("2026-06-01T11:00:00Z","2026-06-01T11:30:00Z")
        val zero=cross.copy(durationMinutes=0)
        for (range in listOf("日","周","月")) assertEquals(listOf(cross),TimeRecordCalendar.inRange(listOf(cross,future,zero),range,now,zone))
    }
    @Test fun `streak survives DST and includes yesterday when today is empty`() {
        val records=listOf(record("2026-03-07T17:00:00Z","2026-03-07T17:30:00Z"),record("2026-03-08T16:00:00Z","2026-03-08T16:30:00Z"))
        assertEquals(2,TimeRecordCalendar.streak(records,Instant.parse("2026-03-09T12:00:00Z"),ZoneId.of("America/New_York")))
    }
}
