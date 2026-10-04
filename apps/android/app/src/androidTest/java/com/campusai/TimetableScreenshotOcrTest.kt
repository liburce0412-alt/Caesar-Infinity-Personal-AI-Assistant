package com.campusai

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.campusai.features.schedule.ScheduleImporter
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Optional supplied-image regression. Run on an isolated emulator; never uninstall a user's app. */
@RunWith(AndroidJUnit4::class)
class TimetableScreenshotOcrTest {
    @Test fun cancelledImportCanBeFollowedByAnotherImport() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = InstrumentationRegistry.getArguments().getString("fixturePath")
        assumeTrue("Pass fixturePath for cancellation regression", !fixture.isNullOrBlank())
        val first = async { ScheduleImporter.fromImage(context, Uri.fromFile(File(fixture!!))) }
        delay(300)
        first.cancel()
        first.join()
        assertTrue(first.isCancelled)
        assertEquals(InstrumentationRegistry.getArguments().getString("expectedCount", "11").toInt(), ScheduleImporter.fromImage(context, Uri.fromFile(File(fixture!!))).size)
    }

    @Test fun suppliedScreenshotPreservesCourseStructure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = InstrumentationRegistry.getArguments().getString("fixturePath")
        assumeTrue("Pass fixturePath for the supplied timetable screenshot", !fixture.isNullOrBlank())
        val courses = ScheduleImporter.fromImage(context, Uri.fromFile(File(fixture!!)))
        val json = JSONArray(courses.map { course -> JSONObject().apply {
            put("name", course.name); put("weekday", course.weekday)
            put("periodStart", course.periodStart); put("periodEnd", course.periodEnd)
            put("startMinute", course.startMinute); put("endMinute", course.endMinute)
            put("weeks", course.weeks); put("periodStartTimes", course.periodStartTimes); put("location", course.location); put("reviewNote", course.reviewNote)
        } })
        File(context.cacheDir, "timetable-ocr-result.json").writeText(json.toString(2))
        val expectedCount = InstrumentationRegistry.getArguments().getString("expectedCount", "11").toInt()
        assertEquals(json.toString(), expectedCount, courses.size)
        if (expectedCount == 10) {
            assertTrue(courses.all { it.weeks == "第5周（截图）" })
            assertEquals(listOf(Triple(1,6,7), Triple(1,8,9), Triple(2,3,4), Triple(2,6,8), Triple(2,10,12), Triple(3,1,2), Triple(4,1,2), Triple(4,6,7), Triple(4,8,9), Triple(5,6,8)),
                courses.map { Triple(it.weekday, it.periodStart, it.periodEnd) })
            assertEquals(listOf(840,955,595,840,1120,480,480,840,955,840), courses.map { it.startMinute })
            assertTrue(courses.all { it.endMinute == -1 })
            assertTrue(courses.all { it.periodStartTimes == "480,530,595,645,695,840,890,955,1005,1120,1170,1220" })
            listOf("3208", "3206", "3107", "3114", "3201", "T032", "S402").forEach { room ->
                assertTrue("Missing classroom $room: $json", courses.any { it.location.contains(room) })
            }
            assertTrue(courses.any { it.location.contains("S402") })
            return@runBlocking
        }
        assertEquals(mapOf(1 to 3, 2 to 3, 3 to 1, 4 to 3, 5 to 1), courses.groupingBy { it.weekday }.eachCount())
        assertEquals(listOf(Triple(1, 1, 2), Triple(1, 6, 7), Triple(1, 8, 9), Triple(2, 3, 4), Triple(2, 6, 8), Triple(2, 10, 12), Triple(3, 1, 2), Triple(4, 1, 2), Triple(4, 6, 7), Triple(4, 8, 9), Triple(5, 6, 8)),
            courses.map { Triple(it.weekday, it.periodStart, it.periodEnd) })
        assertTrue(courses.all { it.startMinute == -1 && it.endMinute == -1 })
        assertTrue(courses.any { it.name.contains("体育") })
        assertTrue(courses.any { it.name.contains("会计") })
        assertTrue(courses.none { it.name.contains("第1节") || it.name.contains("备注") })
    }
}
