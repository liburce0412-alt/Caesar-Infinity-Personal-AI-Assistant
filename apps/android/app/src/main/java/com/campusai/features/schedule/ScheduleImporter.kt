package com.campusai.features.schedule

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
) {
    fun toCourse() = CourseSchedule(
        name = name.trim(), weekday = weekday.coerceIn(1, 7), startMinute = startMinute, endMinute = endMinute,
        location = location.trim(), teacher = teacher.trim(), weeks = weeks.trim(),
        sourceHash = stableHash("${name.trim()}|$weekday|$startMinute|$endMinute|${location.trim()}|${weeks.trim()}"),
    )
}

object ScheduleImporter {
    suspend fun fromImage(context: Context, uri: Uri): List<CourseDraft> = withContext(Dispatchers.Default) {
        val bitmap = readTimetableBitmap(context, uri)
        val recognizer = try { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
            catch (failure: Throwable) { bitmap.recycle(); throw failure }
        try {
            val overview = recognizer.recognize(bitmap).ocrLines(includeElements = true)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val cells = detectTimetableCells(overview, TimetablePixels(bitmap.width, bitmap.height, pixels))
            require(cells.size <= 100) { "识别到的色块过多，请截取一张单独的课程表后再试" }
            // OCR each course separately so neighboring columns are never merged into one line.
            val contents = cells.flatMap { cell ->
                currentCoroutineContext().ensureActive()
                val crop = Bitmap.createBitmap(bitmap, cell.left, cell.top, cell.right - cell.left, cell.bottom - cell.top)
                val prepared = try { prepareCourseCell(crop) } catch (failure: Throwable) { if (crop !== bitmap) crop.recycle(); throw failure }
                try {
                    val recognized = recognizer.recognize(prepared).ocrLines(cell.left - CELL_PADDING, cell.top - CELL_PADDING)
                    if (recognized.any { it.text.contains(Regex("[@＠©]")) }) recognized else {
                        // A faint wrapped separator/title line can disappear after grayscale normalization.
                        val contrast = prepareCourseCell(crop, hardEdges = true)
                        try {
                            val retry = recognizer.recognize(contrast).ocrLines(cell.left - CELL_PADDING, cell.top - CELL_PADDING)
                            if (retry.any { it.text.contains(Regex("[@＠©]")) }) retry else recognized
                        } finally { contrast.recycle() }
                    }
                }
                finally { prepared.recycle(); if (crop !== bitmap) crop.recycle() }
            }
            val metadata = overview.filter { line -> cells.none { line.x in it.left until it.right && line.y in it.top until it.bottom } }
            parseTimetableOcr(if (cells.isEmpty()) overview else metadata + contents, cells)
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

    internal fun fromIcsText(raw: String, zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<CourseDraft> =
        IcsCourseParser.parse(raw, zoneId)

}

private const val CELL_PADDING = 16

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
    val backgroundMinimum = if (coloredCount == 0) 128 else histogram.indices.first { value ->
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
            else if (whiteText) 255 - ((minOf(r, g, b) - backgroundMinimum) * 255 / (255 - backgroundMinimum)).coerceIn(0, 255)
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

private fun Text.ocrLines(offsetX: Int = 0, offsetY: Int = 0, includeElements: Boolean = false): List<OcrLine> = textBlocks.flatMap { it.lines }.flatMap { line ->
    buildList {
        line.boundingBox?.let { add(OcrLine(line.text.trim(), it.left + offsetX, it.top + offsetY, it.right + offsetX, it.bottom + offsetY)) }
        if (includeElements) line.elements.filter { element ->
            Regex("^(?:(?:星期|周)[一二三四五六日天]|(?:第)?\\d{1,2}(?:节)?)$").matches(element.text.trim())
        }.forEach { element ->
            element.boundingBox?.let { add(OcrLine(element.text.trim(), it.left + offsetX, it.top + offsetY, it.right + offsetX, it.bottom + offsetY)) }
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
    while (bounds.outWidth / sample > 3600 || bounds.outHeight / sample > 3600 ||
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
