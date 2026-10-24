package com.campusai.core.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import com.campusai.features.ai.IslandSettingsStore
import com.campusai.features.schedule.CourseReminderRuntime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HealthTaskNotificationStatusTest {
    @Test fun progressReplyActionOnlyFollowsSavedMessagesAndResetsOnNextSync() = runTest {
        val store = IslandSettingsStore(context)
        val owner = CourseReminderRuntime.owner(context)
        store.save(owner, store.read(owner).copy(healthAutomation = true, healthContentPreview = true))
        val progress = AndroidHealthTaskProgressPublisher(context)
        fun current() = manager.activeNotifications.single { it.tag == "caesar-health-task-status" }.notification
        progress.publish(HealthTaskStage.SYNCING)
        assertTrue(current().actions.isNullOrEmpty())
        progress.publish(HealthTaskStage.REFRESHED, "平均心率 74 次/分")
        progress.publish(HealthTaskStage.COMPLETE)
        assertTrue(current().actions.isNullOrEmpty())
        progress.publish(HealthTaskStage.SYNCING)
        progress.publish(HealthTaskStage.GENERATING)
        assertTrue(current().actions.isNullOrEmpty())
        progress.publish(HealthTaskStage.SENDING, "已保存的回复")
        progress.publish(HealthTaskStage.COMPLETE)
        assertEquals("查看回复", current().actions.single().title)
        progress.publish(HealthTaskStage.SYNCING)
        assertNull(current().contentIntent)
        progress.clear()
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @After
    fun tearDown() {
        manager.deleteNotificationChannel(HealthTaskNotificationContract.CHANNEL_ID)
    }

    @Test
    fun previewSettingControlsBodyAndDoesNotDisableNotifications() = runTest {
        val store = IslandSettingsStore(context)
        val owner = CourseReminderRuntime.owner(context)
        val publisher = AndroidHealthTaskNotificationPublisher(context, interMessageDelayMillis = 0)
        val message = "测试短消息正文"
        assertEquals(HealthTaskNotificationDelivery.Delivered,
            publisher.publish("test", listOf(message), 1))
        assertEquals(message, manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TEXT))
        AndroidHealthTaskNotificationPublisher.cancelVisible(context)
        store.save(owner, store.read(owner).copy(healthContentPreview = false))
        assertEquals(HealthTaskNotificationDelivery.Delivered,
            publisher.publish("test", listOf(message), 2))
        val hidden = manager.activeNotifications.single().notification
        assertFalse(hidden.extras.toString().contains(message))
        assertEquals("收到一条日常短消息，点击查看", hidden.extras.getString(Notification.EXTRA_TEXT))
        AndroidHealthTaskNotificationPublisher.cancelVisible(context)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun mutedAutomationChannelIsReportedAsDisabled() {
        manager.createNotificationChannel(
            NotificationChannel(
                HealthTaskNotificationContract.CHANNEL_ID,
                "test",
                NotificationManager.IMPORTANCE_NONE,
            ),
        )

        assertFalse(healthTaskNotificationsEnabled(context))
    }

    @Test
    fun enabledAutomationChannelIsReportedAsEnabled() {
        manager.createNotificationChannel(
            NotificationChannel(
                HealthTaskNotificationContract.CHANNEL_ID,
                "test",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )

        assertTrue(healthTaskNotificationsEnabled(context))
    }
}
