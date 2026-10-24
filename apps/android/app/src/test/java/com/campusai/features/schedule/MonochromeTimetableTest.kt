package com.campusai.features.schedule

import org.junit.Assert.*
import org.junit.Test

class MonochromeTimetableTest {
    private val rowTops = listOf(110, 1164, 1714, 1877, 2378, 3577, 4224)
    private val headers = (1..7).map { day ->
        OcrLine("星期${"一二三四五六日"[day - 1]}", 150 + (day - 1) * 151, 58, 270 + (day - 1) * 151, 87)
    }
    private val markers = rowTops.mapIndexed { index, y -> OcrLine("第${index + 1}节", 28, y + 10, 100, y + 46) }

    @Test fun `dark ruled grid has twenty occupied cells excluding empty noon and browser controls`() {
        val width = 1200
        val height = 4820
        val pixels = IntArray(width * height) { 0xff202020.toInt() }
        val boundaries = rowTops + 4680
        fun rectangle(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
            for (y in top until bottom) for (x in left until right) pixels[y * width + x] = color
        }
        boundaries.forEach { y -> rectangle(134, y, 1192, y + 1, 0xff404040.toInt()) }
        (0..7).forEach { column -> rectangle(134 + column * 151, 110, 135 + column * 151, 4681, 0xff404040.toInt()) }
        val occupied = listOf(listOf(1, 2, 4, 5, 6, 7), listOf(1, 2, 4, 5), listOf(1, 2, 4, 5),
            listOf(1), listOf(1, 4, 5), listOf(5), listOf(6))
        occupied.forEachIndexed { column, periods -> periods.forEach { period ->
            val x = 154 + column * 151
            val y = rowTops[period - 1] + 40
            rectangle(x, y, x + 70, y + 30, 0xffeeeeee.toInt())
        } }
        rectangle(134, 4740, 1192, 4770, 0xffeeeeee.toInt())
        val cells = detectTimetableCells(headers + markers, TimetablePixels(width, height, pixels))
        assertEquals(20, cells.size)
        assertEquals(mapOf(1 to 6, 2 to 4, 3 to 4, 4 to 1, 5 to 3, 6 to 1, 7 to 1), cells.groupingBy { it.weekday }.eachCount())
        assertTrue(cells.none { it.gridPeriod == 3 || it.bottom > 4680 })
        assertTrue(cells.any { it.bottom - it.top > 1000 })
    }

    @Test fun `same cell preserves distinct courses and wrapped odd even week lists`() {
        val cell = TimetableCell(5, 754, 2378, 905, 3577, 5)
        val rows = listOf("C108006B", "[02]", "复变函数", "与积分变", "换[本]", "第02,04,", "06,08,10,",
            "12,14,16", "周 张老师", "示例校区", ",教学楼,", "A108", "A109003B", "[28]",
            "马克思主", "义基本原", "理[本]", "第01,03,", "05,07,O9,", "11,13,15周", "李老师", "示例校区", ",教学楼,", "A308")
        val content = rows.mapIndexed { index, text -> OcrLine(text, 765, 2400 + index * 40, 895, 2430 + index * 40) }
        val clocks = listOf(OcrLine("[16:20-", 24, 2430, 125, 2460), OcrLine("18:10]", 24, 2470, 120, 2500))
        val courses = parseTimetableOcr(headers + markers + clocks + content, listOf(cell))
        assertEquals(listOf("复变函数与积分变换", "马克思主义基本原理"), courses.map { it.name })
        assertEquals(listOf("第02,04,06,08,10,12,14,16周", "第01,03,05,07,09,11,13,15周"), courses.map { it.weeks })
        assertEquals(listOf("张老师", "李老师"), courses.map { it.teacher })
        assertTrue(courses.all { it.startMinute == 980 && it.endMinute == 1090 && it.reviewNote.isNotBlank() })
        assertTrue(courses[0].location.endsWith("A108"))
    }

    @Test fun `OCR retries choose a complete row without duplicating text or losing adjacent fragments`() {
        val rows = bestOcrRows(listOf(
            listOf(OcrLine("信号", 10, 10, 40, 30, .95f), OcrLine("与系统", 42, 10, 80, 30, .95f)),
            listOf(OcrLine("信号与系", 10, 11, 80, 31, .7f)),
        ))
        assertEquals(listOf("信号与系统"), rows.map { it.text })
    }
}
