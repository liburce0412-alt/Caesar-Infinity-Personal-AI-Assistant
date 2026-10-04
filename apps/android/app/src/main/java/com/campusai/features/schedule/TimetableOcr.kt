package com.campusai.features.schedule

import kotlin.math.abs
import kotlin.math.roundToInt

internal data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val x get() = (left + right) / 2
    val y get() = (top + bottom) / 2
}

internal data class TimetableCell(val weekday: Int, val left: Int, val top: Int, val right: Int, val bottom: Int)
internal data class TimetablePixels(val width: Int, val height: Int, val argb: IntArray)

private val weekdayHeader = Regex("^(?:\\d{1,2}日)?(?:星期|周)([一二三四五六日天])$")
private val weekdays = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7)

private fun weekdayAnchors(lines: List<OcrLine>): List<Pair<Int, OcrLine>> {
    val candidates = lines.mapNotNull { line -> weekdayHeader.matchEntire(line.text.replace(Regex("\\s+"), ""))
        ?.let { weekdays.getValue(it.groupValues[1].first()) to line } }
    // Prefer a single horizontal header row, rather than weekday words in notes below the table.
    val row = candidates.maxByOrNull { candidate -> candidates.count { abs(it.second.y - candidate.second.y) <= maxOf(12, candidate.second.bottom - candidate.second.top) } }
    val anchors = candidates.filter { row != null && abs(it.second.y - row.second.y) <= maxOf(12, row.second.bottom - row.second.top) }
        .distinctBy { it.first }.sortedBy { it.first }
    require(anchors.size >= 2) { "未识别到可靠的星期表头，请选择包含表头的完整课程表，或手动添加" }
    return anchors
}

private data class PeriodAxis(val centers: Map<Int, Int>, val step: Double) {
    val first get() = centers.keys.min()
    val last get() = centers.keys.max()
    fun periodAt(y: Int): Int = centers.minBy { abs(it.value - y) }.key
    val bottom get() = (centers.getValue(last) + step / 2).roundToInt()
}

private fun periodAxis(lines: List<OcrLine>, anchors: List<Pair<Int, OcrLine>>): PeriodAxis? {
    val spacing = columnWidth(anchors)
    val left = anchors.first().second.x - spacing / 2
    val top = anchors.maxOf { it.second.bottom }
    val points = lines.filter { it.y > top && it.x < left }.mapNotNull { line ->
        Regex("^(?:第)?(\\d{1,2})(?:节)?$").matchEntire(line.text.trim())?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 1..24 }?.let { it to line.y }
    }.distinctBy { it.first }.sortedBy { it.first }
    if (points.size < 3) return null
    val steps = points.zipWithNext().map { (a, b) -> (b.second - a.second).toDouble() / (b.first - a.first) }.sorted()
    val step = steps[steps.size / 2]
    if (step <= 0 || points.zipWithNext().any { (a, b) -> abs((b.second - a.second) / (b.first - a.first) - step) > step * .3 }) return null
    val origin = points.map { it.second - it.first * step }.sorted().let { it[it.size / 2] }
    val first = (1..points.first().first).firstOrNull { origin + it * step > top } ?: points.first().first
    return PeriodAxis((first..points.last().first).associateWith { (origin + it * step).roundToInt() }, step)
}

private fun columnWidth(anchors: List<Pair<Int, OcrLine>>): Int = anchors.zipWithNext()
    .map { (a, b) -> (b.second.x - a.second.x) / (b.first - a.first) }.sorted().let { it[it.size / 2] }.coerceAtLeast(1)

/** Locate colored course rectangles before OCR: wrapped lines belong to a cell, not to separate courses. */
internal fun detectTimetableCells(lines: List<OcrLine>, pixels: TimetablePixels): List<TimetableCell> {
    require(pixels.width > 0 && pixels.height > 0 && pixels.argb.size == pixels.width * pixels.height)
    val anchors = weekdayAnchors(lines)
    val width = columnWidth(anchors)
    val top = anchors.maxOf { it.second.bottom }.coerceIn(0, pixels.height - 1)
    val axis = periodAxis(lines, anchors)
    val bottom = (axis?.bottom ?: pixels.height).coerceIn(top + 1, pixels.height)
    val minimumHeight = maxOf(12, ((axis?.step ?: width.toDouble()) * .45).roundToInt())
    val cells = mutableListOf<TimetableCell>()
    // Infer missing weekday anchors only inside the observed header span.
    for (day in anchors.first().first..anchors.last().first) {
        val center = anchors.first().second.x + (day - anchors.first().first) * width
        val left = (center - width / 2).coerceIn(0, pixels.width - 1)
        val right = (center + width / 2).coerceIn(left + 1, pixels.width)
        var start = -1
        var last = -1
        var color = 0
        fun finish() {
            if (start >= 0 && last - start + 1 >= minimumHeight) cells += TimetableCell(day, left, start, right, last + 1)
            start = -1
        }
        for (y in top until bottom) {
            val samples = (left + (right - left) / 10 until right - (right - left) / 10 step maxOf(1, width / 32))
                .map { pixels.argb[y * pixels.width + it] }
            val colored = samples.filter(::isCellColor)
            val rowColor = if (colored.size >= samples.size * .35) medianColor(colored) else null
            if (rowColor != null) {
                if (start >= 0 && (hueDistance(color, rowColor) > 24 || y - last > maxOf(3, width / 12))) finish()
                if (start < 0) { start = y; color = rowColor }
                last = y
            } else if (start >= 0 && y - last > maxOf(3, width / 12)) finish()
        }
        finish()
    }
    return cells.sortedWith(compareBy({ it.weekday }, { it.top }))
}

private fun isCellColor(color: Int): Boolean {
    val channels = listOf((color shr 16) and 255, (color shr 8) and 255, color and 255)
    return channels.max() - channels.min() >= 35 && channels.max() >= 95
}
private fun medianColor(colors: List<Int>): Int {
    // White lettering and JPEG antialiasing change brightness, but retain the background hue.
    val saturated = colors.sortedByDescending { color ->
        listOf(16, 8, 0).map { (color shr it) and 255 }.let { it.max() - it.min() }
    }.take(maxOf(1, colors.size / 3))
    return listOf(16, 8, 0).map { shift ->
        saturated.map { (it shr shift) and 255 }.sorted().let { it[it.size / 2] } shl shift
    }.reduce(Int::or)
}
private fun hue(color: Int): Double {
    val r = (color shr 16) and 255; val g = (color shr 8) and 255; val b = color and 255
    val max = maxOf(r, g, b); val delta = (max - minOf(r, g, b)).toDouble()
    if (delta == 0.0) return 0.0
    return ((when (max) { r -> (g - b) / delta; g -> (b - r) / delta + 2; else -> (r - g) / delta + 4 } * 60) + 360) % 360
}
private fun hueDistance(a: Int, b: Int): Double = abs(hue(a) - hue(b)).let { minOf(it, 360 - it) }

internal fun parseTimetableOcr(lines: List<OcrLine>, cells: List<TimetableCell> = emptyList()): List<CourseDraft> {
    val anchors = weekdayAnchors(lines)
    val top = anchors.maxOf { it.second.bottom }
    val left = anchors.minOf { it.second.left }
    val contentLeft = anchors.first().second.x - columnWidth(anchors) / 2
    val axis = periodAxis(lines, anchors)
    val interval = Regex("^(\\d{1,2})[:：](\\d{2})\\s*[-—–~至]\\s*(\\d{1,2})[:：](\\d{2})$")
    val clock = Regex("^(\\d{1,2})[:：](\\d{2})$")
    val periodStarts = if (axis == null) emptyMap() else lines.filter { it.x < contentLeft && it.y > top }.mapNotNull { line ->
        val match = clock.matchEntire(line.text.replace(" ", "")) ?: return@mapNotNull null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour !in 0..23 || minute !in 0..59) null else axis.periodAt(line.y) to (hour * 60 + minute)
    }.toMap()
    val periodTimes = if (periodStarts.isEmpty()) "" else (1..axis!!.last).joinToString(",") { periodStarts[it]?.toString().orEmpty() }
    val sourceWeek = lines.firstNotNullOfOrNull { Regex("第\\s*(\\d{1,2})\\s*周").find(it.text)?.groupValues?.get(1) }
    val times = lines.filter { it.x < left }.mapNotNull { line ->
        val values = interval.matchEntire(line.text)?.groupValues?.drop(1)?.map(String::toInt) ?: return@mapNotNull null
        val (h1, m1, h2, m2) = values
        val start = h1 * 60 + m1
        val end = h2 * 60 + m2
        if (h1 !in 0..23 || h2 !in 0..23 || m1 !in 0..59 || m2 !in 0..59 || end <= start) null else Triple(line, start, end)
    }
    val candidates = lines.filter { it.y > top && it.x >= contentLeft && (axis == null || it.y < axis.bottom) && it.text.isNotBlank() &&
        !weekdayHeader.matches(it.text) && (cells.isNotEmpty() || !Regex("^(?:第?\\d+[节周]?|上午|下午|晚上)$").matches(it.text)) }
    if (cells.isNotEmpty()) return cells.mapNotNull { cell ->
        val content = candidates.filter { it.x in cell.left until cell.right && it.y in cell.top until cell.bottom }
        if (content.isEmpty()) return@mapNotNull null
        // Sample row interiors: period numbers may sit above the clock, away from row centres.
        val periods = axis?.let { it.periodAt((cell.top + it.step / 2).roundToInt()) to it.periodAt((cell.bottom - it.step / 2).roundToInt()) }
        val slots = times.filter { it.first.y in cell.top until cell.bottom }
        draftFromCell(content, cell.weekday, slots.minOfOrNull { it.second } ?: periodStarts[periods?.first] ?: -1,
            slots.maxOfOrNull { it.third } ?: -1, periods)
            .copy(periodStartTimes = periodTimes, weeks = sourceWeek?.let { "第${it}周（截图）" }.orEmpty())
    }
    // A monochrome table with a real time axis can still be grouped by that axis.
    // Without cell boundaries or time rows, fail closed instead of importing every wrapped line.
    if (times.isEmpty() && candidates.size > 1) error("未识别到课程格边界。请选择带完整星期表头和课程色块的原图，或改用日历文件。")
    return candidates.groupBy { line ->
        anchors.minBy { abs(it.second.x - line.x) }.first to (times.minByOrNull { abs(it.first.y - line.y) }?.first?.y ?: line.y)
    }.map { (key, values) ->
        val slot = times.firstOrNull { it.first.y == key.second }
        draftFromCell(values, key.first, slot?.second ?: -1, slot?.third ?: -1, null)
    }
}

private fun draftFromCell(lines: List<OcrLine>, day: Int, start: Int, end: Int, periods: Pair<Int, Int>?): CourseDraft {
    val rows = mutableListOf<MutableList<OcrLine>>()
    lines.sortedBy { it.y }.forEach { line ->
        val row = rows.lastOrNull()?.takeIf { abs(it.first().y - line.y) < maxOf(5, (line.bottom - line.top) / 2) }
        if (row != null) row += line else rows += mutableListOf(line)
    }
    val ordered = rows.map { row -> row.sortedBy { it.left }.joinToString("") { it.text.trim().trim('|', '｜') }.trim() }
        .filter(String::isNotEmpty).mapIndexed { index, value -> if (index > 0 && value.startsWith('©')) "@" + value.drop(1) else value }
    val combined = ordered.joinToString("").replace("＠", "@").replace(Regex("\\s+"), " ")
    val marker = combined.indexOf('@')
    val name = if (marker >= 0) combined.take(marker).trim() else ordered.first()
    val location = if (marker >= 0) combined.drop(marker + 1).trim() else ordered.drop(1).joinToString("")
    val truncated = Regex("[.…]{2,}|…").containsMatchIn(combined)
    return CourseDraft(name = name.ifBlank { "待填写课程名" }, weekday = day, startMinute = start, endMinute = end,
        location = location, periodStart = periods?.first, periodEnd = periods?.second,
        reviewNote = buildString {
            if (start < 0) append(if (periods != null) "按原图节次显示。" else "截图未提供钟点。")
            if (truncated) append("原图文字已截断（…），已保留可见内容。")
        })
}
