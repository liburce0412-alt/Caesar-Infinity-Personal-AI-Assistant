package com.campusai.features.schedule

import kotlin.math.abs

internal data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val x get() = (left + right) / 2
    val y get() = (top + bottom) / 2
}

internal fun parseTimetableOcr(lines: List<OcrLine>): List<CourseDraft> {
    val header = Regex("^(?:星期|周)([一二三四五六日天])$")
    val days = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7)
    val anchors = lines.mapNotNull { line -> header.matchEntire(line.text.replace(" ", ""))
        ?.let { days.getValue(it.groupValues[1].first()) to line } }.distinctBy { it.first }
    require(anchors.size >= 2) { "未识别到可靠的星期表头，请选择包含表头的完整课程表，或手动添加" }
    val top = anchors.maxOf { it.second.bottom }
    val left = anchors.minOf { it.second.left }
    val interval = Regex("^(\\d{1,2})[:：](\\d{2})\\s*[-—–~至]\\s*(\\d{1,2})[:：](\\d{2})$")
    val axis = lines.filter { it.x < left }.mapNotNull { line ->
        val values = interval.matchEntire(line.text)?.groupValues?.drop(1)?.map(String::toInt) ?: return@mapNotNull null
        val (h1, m1, h2, m2) = values
        val start = h1 * 60 + m1
        val end = h2 * 60 + m2
        if (h1 !in 0..23 || h2 !in 0..23 || m1 !in 0..59 || m2 !in 0..59 || end <= start) null
        else Triple(line, start, end)
    }
    val candidates = lines.filter { it.y > top && it.x >= left && it.text.length >= 2 &&
        !header.matches(it.text) && !Regex("^(?:第?\\d+[节周]?|上午|下午|晚上)$").matches(it.text) }
    // Without a real time axis, retain separate OCR lines as drafts with empty times.
    // Never derive clock times by dividing the image into arbitrary equal-height bands.
    return candidates.groupBy { line ->
        val day = anchors.minBy { abs(it.second.x - line.x) }.first
        val row = axis.minByOrNull { abs(it.first.y - line.y) }?.first?.y ?: line.y
        day to row
    }.map { (key, values) ->
        val ordered = values.sortedBy { it.top }.map { it.text }.distinct()
        val slot = axis.firstOrNull { it.first.y == key.second }
        CourseDraft(name = ordered.first(), weekday = key.first,
            startMinute = slot?.second ?: -1, endMinute = slot?.third ?: -1,
            location = ordered.drop(1).joinToString(" · "),
            reviewNote = if (slot == null) "未识别到时间轴，请手动填写时间并核对课程。"
                else "时间来自图片时间轴，请核对跨节课程、教室及周次。",
        )
    }
}
