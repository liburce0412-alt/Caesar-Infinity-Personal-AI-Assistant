package com.campusai.features.schedule

import org.junit.Assert.*
import org.junit.Test

class TimetableOcrGeometryTest {
    private val headers = (1..7).map { day -> OcrLine("周${"一二三四五六日"[day - 1]}", 60 + (day - 1) * 96, 306, 130 + (day - 1) * 96, 330) }
    private val periods = (1..12).map { period -> OcrLine(period.toString(), 5, 368 + (period - 1) * 93, 35, 393 + (period - 1) * 93) }
    private val spans = listOf(Triple(1, 1, 2), Triple(1, 6, 7), Triple(1, 8, 9), Triple(2, 3, 4), Triple(2, 6, 8), Triple(2, 10, 12), Triple(3, 1, 2), Triple(4, 1, 2), Triple(4, 6, 7), Triple(4, 8, 9), Triple(5, 6, 8))
    private val colors = listOf(0xffffc532.toInt(), 0xff8ece00.toInt(), 0xffcd91e0.toInt(), 0xffffa568.toInt(), 0xfff55a9f.toInt(), 0xff479fee.toInt(), 0xff8ece00.toInt(), 0xffebd60b.toInt(), 0xff8ece00.toInt(), 0xff91d4cf.toInt(), 0xffff778f.toInt())

    private fun fixture(): TimetablePixels {
        val width = 720; val height = 1600
        val image = IntArray(width * height) { -1 }
        spans.forEachIndexed { index, (day, start, end) ->
            for (y in 337 + (start - 1) * 93 until 337 + end * 93 - 3) {
                for (x in 49 + (day - 1) * 96 until 143 + (day - 1) * 96) image[y * width + x] = colors[index]
            }
        }
        // Pale selected-day header and colored bottom navigation must not become courses.
        for (y in 275..329) for (x in 433..526) image[y * width + x] = 0xffdffaff.toInt()
        for (y in 1530..1550) for (x in 200..240) image[y * width + x] = 0xff008de8.toInt()
        return TimetablePixels(width, height, image)
    }

    @Test fun `screenshot geometry yields eleven colored cells including adjoining different courses`() {
        val cells = detectTimetableCells(headers + periods, fixture())
        assertEquals(11, cells.size)
        assertEquals(mapOf(1 to 3, 2 to 3, 3 to 1, 4 to 3, 5 to 1), cells.groupingBy { it.weekday }.eachCount())
        val text = cells.flatMapIndexed { index, cell -> listOf(
            OcrLine(if (index == 0) "数据库开" else "课程$index", cell.left + 5, cell.top + 5, cell.right - 5, cell.top + 24),
            OcrLine(if (index == 0) "发… @麦" else "@麦庐园", cell.left + 5, cell.top + 28, cell.right - 5, cell.top + 47),
            OcrLine("庐园校区", cell.left + 5, cell.top + 50, cell.right - 5, cell.top + 69),
            OcrLine("三教3206", cell.left + 5, cell.top + 72, cell.right - 5, cell.top + 91),
        ) }
        val drafts = parseTimetableOcr(headers + periods + text + OcrLine("备注 文化传统与现代文明", 48, 1465, 620, 1490), cells)
        assertEquals(11, drafts.size)
        assertEquals(spans, drafts.map { Triple(it.weekday, it.periodStart, it.periodEnd) })
        assertEquals("数据库开发…", drafts.first().name)
        assertEquals("麦庐园校区三教3206", drafts.first().location)
        assertTrue(drafts.first().reviewNote.contains("截断"))
        assertTrue(drafts.all { it.startMinute == -1 && it.endMinute == -1 })
        assertTrue(drafts.none { "备注" in it.name || "第1节" in it.name })
    }

    @Test fun `OCR holes and a missing weekday or period label keep the cell intact`() {
        val image = fixture()
        // Simulate white lettering temporarily covering a complete narrow scan band.
        for (y in 365..369) for (x in 60..130) image.argb[y * image.width + x] = -1
        val lines = headers.filterNot { it.text == "周三" } + periods.filterNot { it.text in listOf("1", "2", "7") }
        val cells = detectTimetableCells(lines, image)
        assertEquals(11, cells.size)
        assertEquals(1, cells.count { it.weekday == 3 })
        val first = cells.first()
        val draft = parseTimetableOcr(lines + OcrLine("数学@A101", first.left + 5, first.top + 10, first.right - 5, first.top + 40), listOf(first)).single()
        assertEquals(1, draft.periodStart)
        assertEquals(2, draft.periodEnd)
    }

    @Test fun `uncertain line-only OCR is rejected instead of making dozens of courses`() {
        val text = (1..50).map { OcrLine("碎片$it", 50, 350 + it * 15, 130, 360 + it * 15) }
        assertTrue(runCatching { parseTimetableOcr(headers + periods + text) }.isFailure)
    }

    @Test fun `white text antialiasing changes brightness without splitting a course`() {
        val image = fixture()
        for (index in image.argb.indices) {
            val original = image.argb[index]
            if (original == -1) continue
            val whiteMix = (index / image.width % 31) / 60.0
            image.argb[index] = listOf(16, 8, 0).map { shift ->
                val channel = (original shr shift) and 255
                (channel + (255 - channel) * whiteMix).toInt() shl shift
            }.reduce(Int::or)
        }
        assertEquals(11, detectTimetableCells(headers + periods, image).size)
    }

    @Test fun `a course spanning time rows uses the first start and last end`() {
        val cell = TimetableCell(1, 48, 337, 144, 521)
        val lines = headers + periods + listOf(OcrLine("数学@A101", 52, 347, 140, 374),
            OcrLine("08:35-09:20", 0, 365, 40, 390), OcrLine("09:30-10:15", 0, 460, 40, 485))
        val draft = parseTimetableOcr(lines, listOf(cell)).single()
        assertEquals(515, draft.startMinute)
        assertEquals(615, draft.endMinute)
        assertEquals("数学", draft.name)
    }
}
