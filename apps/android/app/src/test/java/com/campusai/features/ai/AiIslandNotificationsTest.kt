package com.campusai.features.ai

import android.app.Notification
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.campusai.core.designsystem.CaesarOrbMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import com.campusai.features.schedule.CourseReminderStore
import com.campusai.features.schedule.CourseReminderConfig
import com.campusai.features.schedule.CourseIslandAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiIslandNotificationsTest {
    @Test fun `sync has no reply action and long saved content remains available`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sync = AiIslandNotifications.build(context, CaesarOrbMode.COMPLETE,
            allowStop = false, openLabel = null, contentPreview = "平均心率 74 次/分")
        assertNull(sync.contentIntent)
        assertTrue(sync.actions.isNullOrEmpty())
        val body = JSONObject(sync.extras.getString("miui.focus.param")!!).getJSONObject("param_v2")
        assertFalse(body.has("textButton"))
        assertFalse(body.has("hintInfo"))
        val message = "已保存的长消息。".repeat(50) + "正文结尾"
        val reply = AiIslandNotifications.build(context, CaesarOrbMode.COMPLETE,
            allowStop = false, openLabel = "查看回复", contentPreview = message)
        assertTrue(reply.extras.getString(Notification.EXTRA_BIG_TEXT)!!.endsWith("正文结尾"))
        assertFalse(reply.extras.containsKey("miui.focus.param.custom"))
    }

    @Test fun `health preview is carried by both native island and expanded notification`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val message = "今天已经走了 1234 步，记得休息一下"
        val notification = AiIslandNotifications.build(context, CaesarOrbMode.COMPLETE,
            labelOverride = "本次健康任务已结束", allowStop = false, contentPreview = message)
        assertTrue(notification.extras.getString(Notification.EXTRA_BIG_TEXT)!!.contains(message))
        val body = JSONObject(notification.extras.getString("miui.focus.param")!!).getJSONObject("param_v2")
        assertEquals("新消息", body.getString("ticker"))
        assertEquals(message, android.text.Html.fromHtml(body.getJSONObject("baseInfo").getString("title"), 0).toString())
        assertEquals(15_000L, notification.timeoutAfter)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(1, notification.actions.size)
    }

    @Test fun `health messages use separate native rows and retain the saved conversation action`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val messages = listOf("今天走了不少路啦", "歇会儿也挺好的", "手头的事慢慢来")
        val notification = AiIslandNotifications.build(context, CaesarOrbMode.COMPLETE,
            labelOverride = "新消息", allowStop = false, openLabel = "查看回复",
            contentPreview = messages.joinToString("\n"))
        val body = JSONObject(notification.extras.getString("miui.focus.param")!!).getJSONObject("param_v2")
        assertFalse(body.has("iconTextInfo"))
        assertFalse(body.has("textButton"))
        assertEquals(messages[0], android.text.Html.fromHtml(body.getJSONObject("baseInfo").getString("title"), 0).toString())
        assertEquals(messages[1], body.getJSONObject("baseInfo").getString("content"))
        val hint = body.getJSONObject("hintInfo")
        assertEquals(messages[2], android.text.Html.fromHtml(hint.getString("title"), 0).toString())
        assertEquals("新消息", hint.getString("content"))
        val action = hint.getJSONObject("actionInfo")
        assertEquals("查看回复", action.getString("actionTitle"))
        assertFalse(action.has("actionIntentType"))
        assertFalse(action.has("actionIntent"))
        val bundled = notification.extras.getBundle("miui.focus.actions")!!
            .getParcelable<Notification.Action>(action.getString("action"))!!
        assertEquals(notification.contentIntent, bundled.actionIntent)
    }

    @Test fun `all collapsed AI states fit beside the camera and private replies retain their action window`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (mode in CaesarOrbMode.entries) {
            val notification = AiIslandNotifications.build(context, mode)
            val body = JSONObject(notification.extras.getString("miui.focus.param")!!).getJSONObject("param_v2")
            val title = body.getJSONObject("param_island").getJSONObject("bigIslandArea")
                .getJSONObject("imageTextInfoLeft").getJSONObject("textInfo").getString("title")
            assertTrue(title, title.trim().length in 2..3)
            assertTrue(title.endsWith("\u2002"))
            assertFalse(title.contains("…"))
        }
        val privateReply = AiIslandNotifications.build(context, CaesarOrbMode.COMPLETE,
            allowStop = false, openLabel = "查看回复", contentPreview = null)
        assertEquals(15_000L, privateReply.timeoutAfter)
        val body = JSONObject(privateReply.extras.getString("miui.focus.param")!!).getJSONObject("param_v2")
        assertEquals(15, body.getJSONObject("param_island").getInt("islandTimeout"))
        assertEquals("查看回复", body.getJSONObject("hintInfo").getJSONObject("actionInfo").getString("actionTitle"))
        assertNull(privateReply.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
    }

    @Test fun `health notifications cannot replace a live AI stop intent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val received = mutableListOf<CaesarOrbMode>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { AiIslandNotifications.stopRequests.collect { received += it } }
        try {
            val speaking = AiIslandNotifications.build(context, CaesarOrbMode.SPEAKING)
            AiIslandNotifications.build(context, CaesarOrbMode.THINKING, allowStop = false, openLabel = null)
            AiIslandNotifications.build(context, CaesarOrbMode.COMPLETE, allowStop = false, openLabel = "查看回复")
            AiIslandNotifications.handleStop(context, shadowOf(speaking.actions[1].actionIntent).savedIntent)
            assertEquals(listOf(CaesarOrbMode.SPEAKING), received)
        } finally { collector.cancel() }
    }

    @Test fun `message markup stays literal in native rich text`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val message = "A < B & C > D"
        val notification = AiIslandNotifications.build(context, CaesarOrbMode.COMPLETE,
            allowStop = false, contentPreview = "$message\n$message\n$message")
        val body = JSONObject(notification.extras.getString("miui.focus.param")!!).getJSONObject("param_v2")
        assertEquals(message, android.text.Html.fromHtml(body.getJSONObject("baseInfo").getString("title"), 0).toString())
        assertEquals(message, android.text.Html.fromHtml(body.getJSONObject("baseInfo").getString("content"), 0).toString())
        assertEquals(message, android.text.Html.fromHtml(body.getJSONObject("hintInfo").getString("title"), 0).toString())
    }

    @Test fun `health stages are independent of preview and four metrics use two native rows`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val metrics = listOf("步数 1234", "活动 20分钟", "睡眠 7小时10分", "平均心率 74 次/分")
        for (showContent in listOf(true, false)) {
            for (stage in com.campusai.core.automation.HealthTaskStage.entries) {
                val notification = AiIslandNotifications.build(context, stage.mode,
                    labelOverride = stage.label, allowStop = false, openLabel = null,
                    contentPreview = metrics.joinToString("\n").takeIf { showContent })
                val body = JSONObject(notification.extras.getString("miui.focus.param")!!).getJSONObject("param_v2")
                assertEquals(stage.label, body.getString("ticker"))
                assertTrue(stage.label.length in 2..3)
                assertFalse(body.has("textButton"))
                if (showContent) {
                    val base = body.getJSONObject("baseInfo")
                    assertEquals(metrics.take(2).joinToString(" · "), android.text.Html.fromHtml(base.getString("title"), 0).toString())
                    assertEquals(metrics.drop(2).joinToString(" · "), base.getString("content"))
                    assertFalse(base.has("subContent"))
                    assertTrue(notification.extras.getString(Notification.EXTRA_BIG_TEXT)!!.contains(metrics.last()))
                } else {
                    metrics.forEach { assertFalse(notification.extras.toString().contains(it)) }
                }
            }
        }
    }

    @Test fun `stop action is owner scoped single use and cannot cancel after expiry`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val received = mutableListOf<CaesarOrbMode>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch {
            AiIslandNotifications.stopRequests.collect { received += it }
        }
        try {
            val otherOwner = AiIslandNotifications.build(context, CaesarOrbMode.RESPONDING, owner = "different-account")
            AiIslandNotifications.handleStop(context, shadowOf(otherOwner.actions[1].actionIntent).savedIntent)
            assertTrue(received.isEmpty())
            val current = AiIslandNotifications.build(context, CaesarOrbMode.RESPONDING)
            val intent = shadowOf(current.actions[1].actionIntent).savedIntent
            AiIslandNotifications.handleStop(context, intent)
            AiIslandNotifications.handleStop(context, intent)
            assertEquals(listOf(CaesarOrbMode.RESPONDING), received)
            val expired = AiIslandNotifications.build(context, CaesarOrbMode.SPEAKING)
            AiIslandNotifications.cancel(context)
            AiIslandNotifications.handleStop(context, shadowOf(expired.actions[1].actionIntent).savedIntent)
            assertEquals(1, received.size)
        } finally { collector.cancel() }
    }

    @Test fun `opt-out observation is owner scoped and stops after disposal`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = CourseReminderStore(context)
        store.save("orb-test-owner", CourseReminderConfig(xiaomi = true))
        val values = mutableListOf<Boolean>()
        val subscription = store.observe("orb-test-owner") { values += it.xiaomi }
        store.save("orb-other-owner", CourseReminderConfig(xiaomi = false))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true), values)
        store.save("orb-test-owner", CourseReminderConfig(xiaomi = false))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true, false), values)
        subscription.close()
        store.save("orb-test-owner", CourseReminderConfig(xiaomi = true))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true, false), values)
    }

    @Test fun `cancelled queued publication never reaches notification delivery`() {
        CourseIslandAccess.publish(post = { fail("Cancelled notification was delivered") }, isCurrent = { false })
    }

    @Test fun `native payload carries the orb with private generic status and expires after completion`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val active = AiIslandNotifications.build(context, CaesarOrbMode.RESPONDING)
        assertTrue(active.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.VISIBILITY_PRIVATE, active.visibility)
        assertEquals("正在生成回复", active.extras.getString(Notification.EXTRA_TITLE))
        assertNotNull(active.contentIntent)
        val body = JSONObject(active.extras.getString("miui.focus.param")!!).getJSONObject("param_v2")
        assertEquals(2, body.getJSONArray("textButton").length())
        assertEquals("打开对话", body.getJSONArray("textButton").getJSONObject(0).getString("actionTitle"))
        assertEquals("停止生成", body.getJSONArray("textButton").getJSONObject(1).getString("actionTitle"))
        assertFalse(body.getJSONArray("textButton").getJSONObject(0).has("actionIntentType"))
        assertFalse(body.getJSONArray("textButton").getJSONObject(1).has("actionIntentType"))
        val face = body.getJSONObject("param_island").getJSONObject("bigIslandArea").getJSONObject("picInfo").getString("pic")
        assertNotNull(active.extras.getBundle("miui.focus.pics")!!.getParcelable<android.graphics.drawable.Icon>(face))
        assertEquals("停止生成", active.actions[1].title)
        val picture = body.getJSONObject("param_island").getJSONObject("smallIslandArea")
            .getJSONObject("picInfo").getString("pic")
        assertNotNull(active.extras.getBundle("miui.focus.pics")!!.getParcelable<android.graphics.drawable.Icon>(picture))
        val done = AiIslandNotifications.build(context, CaesarOrbMode.COMPLETE)
        assertFalse(done.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(3_000L, done.timeoutAfter)
        assertEquals(1, done.actions.size)
        assertFalse(done.extras.containsKey("miui.focus.param.custom"))
    }
}
