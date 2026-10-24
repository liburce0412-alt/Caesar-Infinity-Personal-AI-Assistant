package com.campusai

import android.os.Bundle
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.campusai.core.automation.*
import com.campusai.core.database.CampusDatabase
import com.campusai.features.ai.IslandSettingsStore
import com.campusai.features.schedule.CourseReminderRuntime
import com.campusai.features.schedule.CourseIslandAccess
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit manual review only: install with -r and invoke am instrument; never connectedAndroidTest. */
@RunWith(AndroidJUnit4::class)
class HealthIslandDeviceReviewTest {
    @Test fun reviewSavedReplyOrSync() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("healthIslandReview") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        val context = instrumentation.targetContext
        val config = NoBackupScheduledTaskStore(context).read(HealthTaskDefaults.TASK_ID)!!
        assertTrue(config.enabled && config.options.syncOnly && config.options.runInBackground)
        assertFalse(config.includeHealthSummary)
        assertEquals(15, config.intervalMinutes)
        val settings = IslandSettingsStore(context).read(CourseReminderRuntime.owner(context))
        assertTrue(settings.healthAutomation && settings.healthContentPreview)
        assertTrue("Shizuku must be ready before native island review", CourseIslandAccess.awaitReady())
        val dao = CampusDatabase.getDatabase(context).campusDao()
        val progress = AndroidHealthTaskProgressPublisher(context)
        val phase = args.getString("phase") ?: "reply"
        if (phase == "sync") {
            assertEquals(ForegroundHealthTaskRunResult.Synced,
                ForegroundHealthTaskRuntime.get(context, dao).runner.run(config.id, force = true))
        } else {
            val report = dao.getAiReport(HealthTaskNotificationContract.conversationId(config.id), "local_user")!!
            val rows = JSONArray(report.messagesJson)
            val messages = (0 until rows.length()).map { rows.getJSONObject(it).getString("content") }
            val preview = messages.takeLast(3).joinToString("\n")
            assertTrue(preview.isNotBlank())
            progress.publish(HealthTaskStage.SYNCING)
            progress.publish(HealthTaskStage.SENDING, preview)
            progress.publish(HealthTaskStage.COMPLETE)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Saved preview characters=${preview.length}\n") })
        }
        val directory = File(context.getExternalFilesDir(null), "island-review").apply { mkdirs() }
        fun capture(name: String) {
            shell("screencap -p ${directory.absolutePath}/$phase-$name.png")
            assertTrue(File(directory, "$phase-$name.png").length() > 0)
        }
        delay(1300)
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
            .single { it.tag == "caesar-health-task-status" }.notification
        assertTrue("Ordinary notification fallback is not native island acceptance",
            notification.extras.containsKey("miui.focus.param"))
        shell("input keyevent KEYCODE_HOME")
        delay(650)
        capture("compact")
        shell("input tap 560 95")
        delay(400)
        capture("expanded")
        args.getString("buttonY")?.toInt()?.let { y ->
            val x = args.getString("buttonX")?.toInt() ?: 720
            shell("input tap $x $y")
            delay(2500)
            capture("destination")
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Review screenshots: ${directory.absolutePath}\n") })
        delay(3000)
        val after = NoBackupScheduledTaskStore(context).read(config.id)!!
        assertEquals(config.options, after.options)
        assertEquals(config.intervalMinutes, after.intervalMinutes)
        assertFalse(after.includeHealthSummary)
    }
}
