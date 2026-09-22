package com.campusai.features.schedule

import android.content.Context
import android.net.Uri
import com.campusai.core.model.CourseSchedule
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CourseDraft(
    val name: String,
    val weekday: Int,
    val startMinute: Int,
    val endMinute: Int,
    val location: String = "",
    val teacher: String = "",
    val weeks: String = "",
    val reviewNote: String = "",
) {
    fun toCourse() = CourseSchedule(
        name = name.trim(), weekday = weekday.coerceIn(1, 7), startMinute = startMinute, endMinute = endMinute,
        location = location.trim(), teacher = teacher.trim(), weeks = weeks.trim(),
        sourceHash = stableHash("${name.trim()}|$weekday|$startMinute|$endMinute|${location.trim()}|${weeks.trim()}"),
    )
}

object ScheduleImporter {
    suspend fun fromImage(context: Context, uri: Uri): List<CourseDraft> {
        val sourceBytes = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        require(sourceBytes < 0 || sourceBytes <= 25L * 1024 * 1024) { "图片超过 25 MB，请先截取课程表区域后再试" }
        val image = InputImage.fromFilePath(context, uri)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        return try {
            val result = suspendCancellableCoroutine<Text> { continuation ->
                val task = recognizer.process(image)
                task.addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                task.addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
            }
            parseRecognizedText(result)
        } finally { recognizer.close() }
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

    private fun parseRecognizedText(text: Text): List<CourseDraft> =
        parseTimetableOcr(text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            line.boundingBox?.let { OcrLine(line.text.trim(), it.left, it.top, it.right, it.bottom) }
        })
}

private fun stableHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
