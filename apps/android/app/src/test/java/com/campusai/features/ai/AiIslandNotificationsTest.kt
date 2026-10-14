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
        assertEquals("正在生成回复", active.extras.getString(Notification.EXTRA_TEXT))
        assertNotNull(active.contentIntent)
        val body = JSONObject(active.extras.getString("miui.focus.param")!!).getJSONObject("param_v2")
        assertEquals(2, body.getJSONArray("textButton").length())
        val face = body.getJSONObject("param_island").getJSONObject("bigIslandArea").getJSONObject("picInfo").getString("pic")
        assertNotNull(active.extras.getBundle("miui.focus.pics")!!.getParcelable<android.graphics.drawable.Icon>(face))
        assertEquals("停止生成", active.actions[1].title)
        val picture = body.getJSONObject("param_island").getJSONObject("smallIslandArea")
            .getJSONObject("combinePicInfo").getJSONObject("picInfo").getString("pic")
        assertNotNull(active.extras.getBundle("miui.focus.pics")!!.getParcelable<android.graphics.drawable.Icon>(picture))
        val done = AiIslandNotifications.build(context, CaesarOrbMode.COMPLETE)
        assertFalse(done.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(3_000L, done.timeoutAfter)
        assertEquals(1, done.actions.size)
        assertFalse(done.extras.containsKey("miui.focus.param.custom"))
    }
}
