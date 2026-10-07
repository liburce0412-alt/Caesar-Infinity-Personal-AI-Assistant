package com.campusai.features.schedule

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.campusai.core.model.CourseSchedule
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CourseReminderNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L
    private val item = CourseOccurrence(CourseSchedule(1, "Java", 3, 480, 580, "S402", sourceHash = "a"),
        LocalDate.of(2026, 10, 7), now + 60000, now + 120000)
    @Test fun `preclass and active notifications use system timer and expire at class end`() {
        val config = CourseReminderConfig()
        val pre = CourseReminderNotifications.build(context, "owner", item, config, now)
        assertEquals(item.start, pre.`when`)
        assertEquals(120000L, pre.timeoutAfter)
        assertTrue(pre.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(pre.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        val active = CourseReminderNotifications.build(context, "owner", item, config, now + 60000)
        assertEquals(item.end, active.`when`)
        assertEquals(CourseReminderNotifications.LIVE_CHANNEL, active.channelId)
        assertNotNull(active.deleteIntent)
        assertEquals("结束本次显示", active.actions.single().title)
    }
    @Test fun `settings dismissal and demos are isolated by account`() {
        val store = CourseReminderStore(context)
        store.save("a", CourseReminderConfig(enabled = true, semesterMonday = "2026-09-07"))
        assertTrue(store.read("a").enabled)
        assertFalse(store.read("b").enabled)
        store.dismiss("a", item.key)
        assertTrue(item.key in store.dismissed("a"))
        assertTrue(store.dismissed("b").isEmpty())
        store.demo("a", now, "normal")
        assertNotNull(store.demoOccurrence("a", now))
        assertNull(store.demoOccurrence("b", now))
        assertNull(store.demoOccurrence("a", now + 90000))
        store.clearDemo()
        assertNull(store.demoOccurrence("a", now))
    }

    @Test fun `native island keeps collapsed and expanded timers aligned across class start`() {
        for (at in listOf(now, item.start)) {
            val params = CourseIslandStyle.params(item, at).getJSONObject("param_v2")
            val target = if (at < item.start) item.start else item.end
            val island = params.getJSONObject("param_island")
            val compactTimer = island.getJSONObject("bigIslandArea").getJSONObject("sameWidthDigitInfo").getJSONObject("timerInfo")
            val expandedTimer = params.getJSONObject("hintInfo").getJSONObject("timerInfo")
            assertEquals(target, compactTimer.getLong("timerWhen"))
            assertEquals(target, expandedTimer.getLong("timerWhen"))
            assertEquals(at, expandedTimer.getLong("timerSystemCurrent"))
            assertEquals((item.end - at) / 1000, island.getLong("islandTimeout"))
            assertEquals((item.end - at + 59999) / 60000, params.getLong("timeout"))
            assertFalse("Expanded progress replaces the countdown template on HyperOS", params.has("progressInfo"))
        }
        val notification = CourseReminderNotifications.build(context, "owner", item, CourseReminderConfig(), now)
        CourseIslandStyle.apply(context, notification, item, now)
        val open = notification.extras.getBundle("miui.focus.actions")!!.getParcelable<Notification.Action>(CourseIslandStyle.OPEN)!!
        assertEquals(notification.contentIntent, open.actionIntent)
    }

    @Test fun `new appearance persists per account and colorful template remains available`() {
        val store = CourseReminderStore(context)
        assertEquals(CourseIslandAppearance.AURORA, store.read("new").islandAppearance)
        store.save("owner", CourseReminderConfig(xiaomi = true, islandAppearance = CourseIslandAppearance.COLORFUL))
        assertEquals(CourseIslandAppearance.COLORFUL, store.read("owner").islandAppearance)
        assertEquals(CourseIslandAppearance.AURORA, store.read("other").islandAppearance)
        val colorful = CourseReminderNotifications.build(context, "owner", item, CourseReminderConfig(), now)
        CourseIslandStyle.apply(context, colorful, item, now, appearance = CourseIslandAppearance.COLORFUL)
        assertFalse(colorful.extras.containsKey("miui.focus.param.custom"))
        assertFalse(colorful.extras.containsKey(CourseIslandExpandedCard.EXPANDED))
        val original = org.json.JSONObject(colorful.extras.getString("miui.focus.param")!!).getJSONObject("param_v2")
        assertEquals("outer_glow", original.getString("outEffectSrc"))

        val aurora = CourseReminderNotifications.build(context, "owner", item, CourseReminderConfig(), now)
        CourseIslandStyle.apply(context, aurora, item, now, appearance = CourseIslandAppearance.AURORA)
        val custom = org.json.JSONObject(aurora.extras.getString("miui.focus.param.custom")!!)
        assertFalse(custom.has("param_v2"))
        assertEquals("", custom.getString("outEffectSrc"))
        assertEquals(original.getJSONObject("param_island").toString(), custom.getJSONObject("param_island").toString())
        assertTrue(aurora.extras.containsKey(CourseIslandExpandedCard.EXPANDED))
        assertNotNull(aurora.contentIntent)
    }

    @Test fun `a system without a custom layout grant receives the working standard template`() {
        assertFalse(CourseIslandStyle.customLayoutsAllowed(context))
        assertEquals(CourseIslandAppearance.COLORFUL,
            CourseIslandStyle.supportedAppearance(context, CourseIslandAppearance.AURORA))
        android.provider.Settings.System.putInt(context.contentResolver, "notification_focus_protocol", 3)
        val notification = CourseReminderNotifications.build(context, "owner", item,
            CourseReminderConfig(xiaomi = true, islandAppearance = CourseIslandAppearance.AURORA), now)
        assertNotNull(notification.extras.getString("miui.focus.param"))
        assertFalse(notification.extras.containsKey("miui.focus.param.custom"))
        assertFalse(notification.extras.containsKey(CourseIslandExpandedCard.EXPANDED))
    }
}
