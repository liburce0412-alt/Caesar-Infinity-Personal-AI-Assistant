package com.campusai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.campusai.core.automation.HealthTaskDefaults
import com.campusai.core.automation.NoBackupScheduledTaskStore
import com.campusai.core.designsystem.CaesarOrbMode
import com.campusai.core.model.CourseSchedule
import com.campusai.features.ai.AiIslandNotifications
import com.campusai.features.ai.IslandSettingsStore
import com.campusai.features.schedule.*
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in native visual fixtures, with no AI calls, saved messages, or settings changes. */
@RunWith(AndroidJUnit4::class)
class IslandVisualReviewTest {
    @Test fun captureNativeStates() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("islandVisualReview") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        val owner = CourseReminderRuntime.owner(context)
        val config = NoBackupScheduledTaskStore(context).read(HealthTaskDefaults.TASK_ID)
        val settings = IslandSettingsStore(context).read(owner)
        assertTrue(CourseIslandAccess.awaitReady())
        val channel = "caesar_health_task_status_v1"
        manager.createNotificationChannel(NotificationChannel(channel, "Caesar 健康任务状态", NotificationManager.IMPORTANCE_LOW))
        val directory = File(context.getExternalFilesDir(null), "island-visual-review").apply { mkdirs() }
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        assertEquals("Finish the call before visual review", AudioManager.MODE_NORMAL, audio.mode)
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("input keyevent KEYCODE_HOME")
        if (args.getString("scenarios")?.contains("messageDual") == true) {
            assertFalse("Finish the live AI interaction before the dual-island fixture",
                manager.activeNotifications.any { it.tag == "caesar-ai-status" })
        }
        try {
            for (scenario in (args.getString("scenarios") ?: "message3").split(',')) {
                assertEquals("A call interrupted visual review", AudioManager.MODE_NORMAL, audio.mode)
                val dual = scenario == "messageDual"
                val stopped = if (dual) async(start = CoroutineStart.UNDISPATCHED) {
                    AiIslandNotifications.stopRequests.first()
                } else null
                if (dual) {
                    assertTrue(settings.ai)
                    AiIslandNotifications.publish(context, CaesarOrbMode.SPEAKING, owner)
                    delay(800)
                }
                val messages = when (scenario) {
                    "message1" -> listOf("手头的事慢慢来就好")
                    "message2" -> listOf("今天已经走了不少路啦", "歇会儿也挺好的，别太赶啦")
                    "messageLong" -> listOf("今天走过的这些路都算数，忙完了也给自己留一点休息时间", "手头的事可以慢慢来，不用一下子全做完", "要是有点累，先坐一会儿也没关系")
                    "messageMarkup" -> listOf("<b>标签</b> & 文字", "A < B & C > D", "保留 < > 和 &")
                    else -> listOf("今天已经走了不少路啦", "歇会儿也挺好的", "手头的事慢慢来就好")
                }
                val course = scenario.startsWith("course")
                val mode = when (scenario) {
                    "aiThinking", "healthSync", "healthPrivate" -> CaesarOrbMode.THINKING
                    "aiResponding" -> CaesarOrbMode.RESPONDING
                    "aiListening" -> CaesarOrbMode.LISTENING
                    "aiTranscribing" -> CaesarOrbMode.TRANSCRIBING
                    "aiSpeaking" -> CaesarOrbMode.SPEAKING
                    "aiError" -> CaesarOrbMode.ERROR
                    "aiCancelled" -> CaesarOrbMode.CANCELLED
                    else -> CaesarOrbMode.COMPLETE
                }
                val notification = if (course) {
                    val now = System.currentTimeMillis()
                    val start = if (scenario == "courseActive") now - 60_000 else now + 5 * 60_000
                    val item = CourseOccurrence(CourseSchedule(-1, "课程布局测试 · 程序设计", 3, 480, 580,
                        "教学楼[S402]", periodStart = 3, periodEnd = 4, sourceHash = "visual-review"),
                        LocalDate.now(), start, now + 40 * 60_000)
                    CourseReminderNotifications.build(context, owner, item, CourseReminderConfig(), maxOf(now, start)).also {
                        CourseIslandStyle.apply(context, it, item, now)
                    }
                } else AiIslandNotifications.build(context, mode, owner = owner, channel = channel,
                    labelOverride = when { scenario.startsWith("message") -> "新消息"; scenario.startsWith("health") -> "已同步"; else -> null },
                    allowStop = scenario.startsWith("ai"),
                    openLabel = when { scenario.startsWith("message") -> "查看回复"; scenario.startsWith("ai") -> "打开对话"; else -> null },
                    contentPreview = when { scenario == "messagePrivate" -> null; scenario.startsWith("message") -> messages.joinToString("\n"); scenario == "healthSync" -> "步数 1234\n活动 20分钟\n睡眠 7小时10分\n平均心率 74 次/分"; else -> null })
                val root = JSONObject(notification.extras.getString("miui.focus.param")!!)
                val body = root.getJSONObject("param_v2")
                body.put("islandFirstFloat", false)
                notification.extras.putString("miui.focus.param", root.toString())
                // Test fixtures must not sound or change course dismissal/history state.
                notification.sound = null
                notification.vibrate = null
                notification.deleteIntent = null
                CourseIslandAccess.publish { native ->
                    check(native) { "Native publication unavailable" }
                    manager.notify("island-visual-review", 742099, notification)
                }
                val brief = scenario.startsWith("ai") && !AiIslandNotifications.active(mode)
                delay(if (brief) 200 else 1100)
                val variant = args.getString("variant") ?: "current"
                for (state in if (args.getString("shade") == "true") listOf("compact", "expanded", "shade") else listOf("compact", "expanded")) {
                    if (state == "expanded") { shell("input tap 560 95"); delay(if (brief) 200 else 650) }
                    if (state == "shade") { shell("cmd statusbar expand-notifications"); delay(650) }
                    val file = File(directory, "$variant-$scenario-$state.png")
                    shell("screencap -p ${file.absolutePath}")
                    assertTrue(file.length() > 0)
                }
                args.getString("buttonY")?.toInt()?.let { y ->
                    val x = args.getString("buttonX")?.toInt() ?: 1220
                    shell("input tap $x $y")
                    delay(2500)
                    shell("screencap -p ${directory.absolutePath}/$variant-$scenario-destination.png")
                }
                manager.cancel("island-visual-review", 742099)
                shell("input keyevent KEYCODE_HOME")
                if (dual) {
                    delay(800)
                    shell("input tap 560 95")
                    delay(650)
                    shell("screencap -p ${directory.absolutePath}/$variant-$scenario-ai-expanded.png")
                    shell("input tap 1050 480")
                    assertEquals(CaesarOrbMode.SPEAKING, withTimeout(2500) { stopped!!.await() })
                    withTimeout(2500) {
                        while (manager.activeNotifications.any { it.tag == "caesar-ai-status" }) delay(50)
                    }
                }
                delay(350)
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Captured $variant/$scenario\n") })
            }
        } finally {
            manager.cancel("island-visual-review", 742099)
            if (args.getString("scenarios")?.contains("messageDual") == true) AiIslandNotifications.cancel(context)
        }
        val after = NoBackupScheduledTaskStore(context).read(HealthTaskDefaults.TASK_ID)
        assertEquals(config?.enabled, after?.enabled)
        assertEquals(config?.intervalMinutes, after?.intervalMinutes)
        assertEquals(config?.provider, after?.provider)
        assertEquals(config?.modelId, after?.modelId)
        assertEquals(config?.includeHealthSummary, after?.includeHealthSummary)
        assertEquals(config?.options, after?.options)
        assertEquals(settings, IslandSettingsStore(context).read(owner))
    }
}
