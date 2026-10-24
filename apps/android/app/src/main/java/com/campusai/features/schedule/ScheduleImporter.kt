package com.campusai.features.schedule

import com.campusai.core.time.CaesarTime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.campusai.core.model.CourseSchedule
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

data class CourseDraft(
    val name: String,
    val weekday: Int,
    val startMinute: Int,
    val endMinute: Int,
    val location: String = "",
    val teacher: String = "",
    val weeks: String = "",
    val reviewNote: String = "",
    val periodStart: Int? = null,
    val periodEnd: Int? = null,
    val periodStartTimes: String = "",
) {
    fun toCourse() = CourseSchedule(
        name = name.trim(), weekday = weekday.coerceIn(1, 7), startMinute = startMinute, endMinute = endMinute,
        location = location.trim(), teacher = teacher.trim(), weeks = weeks.trim(),
        sourceHash = stableHash("${name.trim()}|$weekday|$startMinute|$endMinute|${location.trim()}|${weeks.trim()}" +
            if (periodStart != null) "|$periodStart|$periodEnd" else ""),
        periodStart = periodStart ?: 0, periodEnd = periodEnd ?: 0, periodStartTimes = periodStartTimes,
    )
}

object ScheduleImporter {
    suspend fun fromImage(context: Context, uri: Uri): List<CourseDraft> = withContext(Dispatchers.Default) {
        val bitmap = readTimetableBitmap(context, uri)
        val recognizer = try { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
            catch (failure: Throwable) { bitmap.recycle(); throw failure }
        try {
            val overview = recognizer.recognize(bitmap).ocrLines(includeElements = true).toMutableList()
            // Whole-image OCR can shrink a long screenshot until its header is unreadable.
            // Read the header and clock axis at local resolution before locating cells.
            val header = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, minOf(bitmap.height, bitmap.width / 2))
            try {
                val prepared = prepareCourseCell(header)
                try { overview += recognizer.recognize(prepared).ocrLines(-CELL_PADDING, -CELL_PADDING, includeElements = true) }
                finally { prepared.recycle() }
            } finally { if (header !== bitmap) header.recycle() }
            val clockWidth = timetableAxisRight(overview).coerceIn(1, bitmap.width)
            for (y in 0 until bitmap.height step 1000) {
                val crop = Bitmap.createBitmap(bitmap, 0, y, clockWidth, minOf(1200, bitmap.height - y))
                try {
                    val prepared = prepareCourseCell(crop)
                    try { overview += recognizer.recognize(prepared).ocrLines(-CELL_PADDING, y - CELL_PADDING, includeElements = true) }
                    finally { prepared.recycle() }
                } finally { if (crop !== bitmap) crop.recycle() }
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val cells = detectTimetableCells(overview, TimetablePixels(bitmap.width, bitmap.height, pixels))
            require(cells.size <= 100) { "识别到的课程格过多，请截取一张单独的课程表后再试" }
            // OCR each course separately so neighboring columns are never merged into one line.
            val contents = cells.flatMap { cell ->
                currentCoroutineContext().ensureActive()
                val crop = Bitmap.createBitmap(bitmap, cell.left, cell.top, cell.right - cell.left, cell.bottom - cell.top)
                val prepared = try { prepareCourseCell(crop) } catch (failure: Throwable) { if (crop !== bitmap) crop.recycle(); throw failure }
                try {
                    if (cell.gridPeriod != null) {
                        // Keep narrow, wrapped text in its original cell. A dark-to-light pass
                        // preserves faint grey lettering that the colored-card threshold loses.
                        val enlarged = Bitmap.createScaledBitmap(prepared, prepared.width * 2, prepared.height * 2, true)
                        val zoomed = try { recognizer.recognize(enlarged).ocrLines(cell.left - CELL_PADDING, cell.top - CELL_PADDING, scale = 2) }
                            finally { enlarged.recycle() }
                        val original = recognizer.recognize(crop).ocrLines(cell.left, cell.top)
                        val normal = recognizer.recognize(prepared).ocrLines(cell.left - CELL_PADDING, cell.top - CELL_PADDING)
                        recognizeWrappedNames(recognizer, prepared, cell, bestOcrRows(listOf(zoomed, original, normal)))
                    } else {
                        val recognized = recognizer.recognize(prepared).ocrLines(cell.left - CELL_PADDING, cell.top - CELL_PADDING)
                        // A second contrast pass recovers faint classroom digits and separators.
                        val contrast = prepareCourseCell(crop, hardEdges = true)
                        try {
                            val retry = recognizer.recognize(contrast).ocrLines(cell.left - CELL_PADDING, cell.top - CELL_PADDING)
                            val enlarged = Bitmap.createScaledBitmap(prepared, prepared.width * 2, prepared.height * 2, true)
                            val zoomed = try { recognizer.recognize(enlarged).ocrLines(cell.left - CELL_PADDING, cell.top - CELL_PADDING, scale = 2) }
                                finally { enlarged.recycle() }
                            val original = recognizer.recognize(crop).ocrLines(cell.left, cell.top)
                            listOf(recognized, retry, zoomed, original).maxBy { lines ->
                                val text = lines.joinToString("") { it.text }
                                (if (text.contains(Regex("[@＠©]"))) 100 else 0) +
                                    Regex("[A-Z]?[0-9]{3,5}").findAll(text).sumOf { it.value.length * 20 } +
                                    (if (text.contains(']') || text.contains('］')) 50 else 0) + text.length.coerceAtMost(90)
                            }
                        } finally { contrast.recycle() }
                    }
                }
                finally { prepared.recycle(); if (crop !== bitmap) crop.recycle() }
            }
            // Read the narrow clock axis separately; whole-image OCR may merge or omit it.
            val ruled = cells.any { it.gridPeriod != null }
            val axisLines = if (ruled) cells.distinctBy { it.top }.flatMap { cell ->
                val crop = Bitmap.createBitmap(bitmap, 0, cell.top, cells.minOf { it.left },
                    minOf(cell.bottom - cell.top, clockWidth * 3))
                try {
                    val prepared = prepareCourseCell(crop)
                    try {
                        val enlarged = Bitmap.createScaledBitmap(prepared, prepared.width * 2, prepared.height * 2, true)
                        try { recognizer.recognize(enlarged).ocrLines(-CELL_PADDING, cell.top - CELL_PADDING, includeElements = true, scale = 2) }
                        finally { enlarged.recycle() }
                    } finally { prepared.recycle() }
                } finally { if (crop !== bitmap) crop.recycle() }
            } else if (cells.isEmpty()) emptyList() else {
                val axisWidth = cells.minOf { it.left }.coerceIn(1, bitmap.width)
                val axisTop = cells.minOf { it.top }
                val axisBottom = cells.maxOf { it.bottom }
                val axisCrop = Bitmap.createBitmap(bitmap, 0, axisTop, axisWidth, axisBottom - axisTop)
                try { recognizer.recognize(axisCrop).ocrLines(offsetY = axisTop, includeElements = true) }
                finally { if (axisCrop !== bitmap) axisCrop.recycle() }
            }
            val metadata = (overview.filterNot { line -> ruled && line.x < cells.minOf { it.left } && line.y >= cells.minOf { it.top } } + axisLines)
                .filter { line -> cells.none { line.x in it.left until it.right && line.y in it.top until it.bottom } }.distinct()
            parseTimetableOcr(if (cells.isEmpty()) overview else metadata + contents, cells).map {
                it.copy(reviewNote = it.reviewNote.ifBlank { "请核对课程名、星期、时间和周次后导入。" })
            }
        } finally { recognizer.close(); bitmap.recycle() }
    }

    fun fromIcs(context: Context, uri: Uri): List<CourseDraft> {
        val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(8192)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                require(result.length + count <= 1_000_000) { "日历文件超过 1 MB" }
                result.append(buffer, 0, count)
            }
            result.toString()
        }
            ?: error("无法读取所选日历文件")
        return fromIcsText(raw)
    }

    internal fun fromIcsText(raw: String, zoneId: java.time.ZoneId = CaesarTime.zone): List<CourseDraft> =
        IcsCourseParser.parse(raw, zoneId)

}

private const val CELL_PADDING = 16

/** Reflow only name rows so the OCR language model sees a whole name, not 3–5 characters at a time. */
private suspend fun recognizeWrappedNames(recognizer: TextRecognizer, prepared: Bitmap, cell: TimetableCell, lines: List<OcrLine>): List<OcrLine> {
    val ordered = lines.sortedBy { it.y }
    val starts = ordered.indices.filter { courseCodeRow.matches(ordered[it].text.replace(" ", "")) }
    if (starts.isEmpty()) return lines
    val result = ordered.toMutableList()
    (starts + ordered.size).zipWithNext().forEach { (start, end) ->
        val nameRows = ordered.subList(start + 1, end)
            .dropWhile { courseGroupRow.matches(it.text.replace(" ", "")) }
            .takeWhile { !weekRowStart.containsMatchIn(it.text.replace(" ", "")) }
        if (nameRows.size !in 1..8) return@forEach
        val width = prepared.width - CELL_PADDING * 2 - 8
        val height = nameRows.maxOf { it.bottom - it.top } + 8
        val ribbon = Bitmap.createBitmap((width + 4) * nameRows.size, height, Bitmap.Config.ARGB_8888)
        ribbon.eraseColor(android.graphics.Color.WHITE)
        try {
            val canvas = Canvas(ribbon)
            nameRows.forEachIndexed { index, line ->
                val top = (line.top - cell.top + CELL_PADDING - 3).coerceAtLeast(0)
                val bottom = (line.bottom - cell.top + CELL_PADDING + 3).coerceAtMost(prepared.height)
                val part = Bitmap.createBitmap(prepared, CELL_PADDING + 4, top, width, bottom - top)
                try { canvas.drawBitmap(part, (index * (width + 4)).toFloat(), (height - part.height) / 2f, null) }
                finally { part.recycle() }
            }
            val enlarged = Bitmap.createScaledBitmap(ribbon, ribbon.width * 2, ribbon.height * 2, true)
            val recognized = try { recognizer.recognize(enlarged) } finally { enlarged.recycle() }
            val oldText = nameRows.joinToString("") { it.text }
            val hasDegree = Regex("\\[本|本]").containsMatchIn(oldText)
            val text = recognized.text.replace(Regex("\\s+"), "").let {
                if (hasDegree) it.replace(Regex("[\\[【]?本[\\]】]?$"), "").trimEnd('[', '【') else it
            }
            val recognizedRows = recognized.textBlocks.flatMap { it.lines }
            val confidence = recognizedRows.sumOf { it.confidence.toDouble() * it.text.length } /
                recognizedRows.sumOf { it.text.length }.coerceAtLeast(1)
            val previousConfidence = nameRows.sumOf { it.confidence.toDouble() * it.text.length } /
                nameRows.sumOf { it.text.length }.coerceAtLeast(1)
            if (text.isNotBlank() && !text.contains(Regex("\\d{1,2}[-,]\\d{1,2}周"))) {
                if (confidence >= previousConfidence) {
                    result.removeAll(nameRows.toSet())
                    result += nameRows.first().copy(text = text)
                }
            }
        } finally { ribbon.recycle() }
    }
    return result
}

/** Isolate light lettering from colorful cell backgrounds and remove table borders before OCR. */
private fun prepareCourseCell(source: Bitmap, hardEdges: Boolean = false): Bitmap {
    val width = source.width; val height = source.height
    val colors = IntArray(width * height)
    source.getPixels(colors, 0, width, 0, 0, width, height)
    fun colorful(color: Int): Boolean {
        val r = (color shr 16) and 255; val g = (color shr 8) and 255; val b = color and 255
        return maxOf(r, g, b) - minOf(r, g, b) >= 35
    }
    val columnCoverage = (0 until width).map { x -> (0 until height step 3).count { y -> colorful(colors[y * width + x]) } }
    val left = columnCoverage.indexOfFirst { it > height / 9 }.coerceAtLeast(0)
    val right = columnCoverage.indexOfLast { it > height / 9 }.takeIf { it > left } ?: width - 1
    val histogram = IntArray(256)
    var coloredCount = 0
    colors.forEach { color -> if (colorful(color)) {
        histogram[minOf((color shr 16) and 255, (color shr 8) and 255, color and 255)]++
        coloredCount++
    } }
    var cumulative = 0
    val backgroundMinimum = if (coloredCount == 0) {
        colors.filterIndexed { index, _ -> index % 7 == 0 }
            .map { minOf((it shr 16) and 255, (it shr 8) and 255, it and 255) }.sorted().let { it[it.size / 2] }
    } else histogram.indices.first { value ->
        cumulative += histogram[value]
        cumulative > coloredCount / 4
    }
    val whiteText = backgroundMinimum < 190
    val outputWidth = width + CELL_PADDING * 2
    val output = IntArray(outputWidth * (height + CELL_PADDING * 2)) { -1 }
    for (y in 2 until height - 2) for (x in left + 2 until right - 1) {
        val color = colors[y * width + x]
        val r = (color shr 16) and 255; val g = (color shr 8) and 255; val b = color and 255
        val gray = if (hardEdges && whiteText) { if (minOf(r, g, b) > maxOf(185, backgroundMinimum + 45)) 0 else 255 }
            else if (whiteText) 255 - ((minOf(r, g, b) - backgroundMinimum) * 255 /
                (if (coloredCount == 0) (255 - backgroundMinimum) * 3 / 5 else 255 - backgroundMinimum).coerceAtLeast(1)).coerceIn(0, 255)
            else (r * 30 + g * 59 + b * 11) / 100
        output[(y + CELL_PADDING) * outputWidth + x + CELL_PADDING] = 0xff000000.toInt() or (gray shl 16) or (gray shl 8) or gray
    }
    return Bitmap.createBitmap(output, outputWidth, height + CELL_PADDING * 2, Bitmap.Config.ARGB_8888)
}

private suspend fun TextRecognizer.recognize(bitmap: Bitmap): Text {
    currentCoroutineContext().ensureActive()
    // ML Kit has no task cancellation API. Keep its bitmap alive until native processing
    // completes, then propagate cancellation before any further OCR or UI result.
    val result = suspendCoroutine<Text> { continuation ->
        process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener { continuation.resumeWithException(it) }
            .addOnCanceledListener { continuation.resumeWithException(kotlinx.coroutines.CancellationException("OCR cancelled")) }
    }
    currentCoroutineContext().ensureActive()
    return result
}

private fun Text.ocrLines(offsetX: Int = 0, offsetY: Int = 0, includeElements: Boolean = false, scale: Int = 1): List<OcrLine> = textBlocks.flatMap { it.lines }.flatMap { line ->
    buildList {
        line.boundingBox?.let { add(OcrLine(line.text.trim(), it.left / scale + offsetX, it.top / scale + offsetY, it.right / scale + offsetX, it.bottom / scale + offsetY, line.confidence)) }
        if (includeElements) line.elements.filter { element ->
            Regex("^(?:(?:星期|周)[一二三四五六日天]|(?:第)?\\d{1,2}(?:节)?|\\d{1,2}[:：]\\d{2})$").matches(element.text.trim())
        }.forEach { element ->
            element.boundingBox?.let { add(OcrLine(element.text.trim(), it.left / scale + offsetX, it.top / scale + offsetY, it.right / scale + offsetX, it.bottom / scale + offsetY)) }
        }
    }
}.distinct()

private suspend fun readTimetableBitmap(context: Context, uri: Uri): Bitmap {
    val maxBytes = 25 * 1024 * 1024
    val source = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maxBytes) { "图片超过 25 MB，请先截取课程表区域后再试" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("无法读取课程表图片")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法解析图片，请选择 JPG 或 PNG 截图" }
    var sample = 1
    while (bounds.outWidth / sample > 8192 || bounds.outHeight / sample > 8192 ||
        (bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > 8_000_000L) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("图片解码失败")
    val orientation = runCatching { ExifInterface(source.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { postScale(-1f, 1f); postRotate(270f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { postScale(-1f, 1f); postRotate(90f) }
        }
    }
    return if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
}

private fun stableHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
