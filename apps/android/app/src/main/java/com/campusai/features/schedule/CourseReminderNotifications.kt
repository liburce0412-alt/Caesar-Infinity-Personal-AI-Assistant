package com.campusai.features.schedule

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.campusai.MainActivity
import com.campusai.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object CourseReminderNotifications {
    const val ACTION_OPEN = "com.campusai.course.OPEN"
    const val EXTRA_COURSE_ID = "course_id"
    const val TAG = "course-reminder"
    const val CHANNEL = "course_reminders_v1"
    const val LIVE_CHANNEL = "course_live_v1"
    private const val ID = 741001
    private const val DEMO_ID = 741099

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(CHANNEL, "课前提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "在课程开始前提醒一次"; lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            },
            NotificationChannel(LIVE_CHANNEL, "上课倒计时", NotificationManager.IMPORTANCE_LOW).apply {
                description = "安静显示正在进行的课程"; setSound(null, null); enableVibration(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            },
        ))
    }

    fun enabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun protocol(context: Context): Int = runCatching {
        Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
    }.getOrDefault(0)
    fun focusAllowed(context: Context): Boolean = runCatching {
        context.contentResolver.call(android.net.Uri.parse("content://miui.statusbar.notification.public"),
            "canShowFocus", null, Bundle().apply { putString("package", context.packageName) })
            ?.getBoolean("canShowFocus", false) == true
    }.getOrDefault(false)

    fun build(context: Context, owner: String, item: CourseOccurrence, config: CourseReminderConfig, now: Long): android.app.Notification {
        val inClass = now >= item.start
        val demo = item.course.id == -1
        val id = if (demo) DEMO_ID else ID
        val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        val range = "${time.format(Instant.ofEpochMilli(item.start))}–${time.format(Instant.ofEpochMilli(item.end))}"
        val target = if (inClass) item.end else item.start
        val stage = if (inClass) "距下课" else "距上课"
        val detail = listOfNotNull(item.course.location.takeIf { it.isNotBlank() }, range,
            item.course.periodStart.takeIf { it > 0 }?.let { "第$it–${item.course.periodEnd}节" }).joinToString(" · ")
        val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN).putExtra(EXTRA_COURSE_ID, item.course.id).putExtra("owner", owner)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismiss = PendingIntent.getBroadcast(context, id, Intent(context, CourseReminderReceiver::class.java)
            .setAction(CourseReminderRuntime.ACTION_DISMISS).setData(android.net.Uri.parse("campusai://course/${item.key}"))
            .putExtra("owner", owner).putExtra("key", item.key).putExtra("demo", demo),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, if (inClass) LIVE_CHANNEL else CHANNEL)
            .setSmallIcon(R.drawable.ic_course_notification)
            .setColor(0xff16889a.toInt()).setColorized(false)
            .setContentTitle("${if (inClass) "上课中" else "还有 ${((item.start - now + 59_999) / 60_000).coerceAtLeast(1)} 分钟上课"} · ${item.course.name}")
            .setContentText("$stage · $detail")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$stage\n$detail"))
            .setWhen(target).setUsesChronometer(true).setChronometerCountDown(true)
            .setShowWhen(true).setOngoing(true).setOnlyAlertOnce(true)
            .setPriority(if (inClass) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(open).setDeleteIntent(dismiss)
            .addAction(0, "结束本次显示", dismiss)
            // HyperOS sends deleteIntent on timeout. Expiring at class start races
            // the transition alarm and would dismiss the entire occurrence.
            .setTimeoutAfter((item.end - now).coerceAtLeast(1))
        if (inClass) builder.setSilent(true)
        if (config.live && !config.xiaomi && Build.VERSION.SDK_INT >= 36) builder.setRequestPromotedOngoing(true)
        return builder.build().also { notification ->
            if (config.xiaomi && protocol(context) >= 3) {
                CourseIslandStyle.apply(context, notification, item, now, config.leadMinutes,
                    CourseIslandStyle.supportedAppearance(context, config.islandAppearance))
            }
        }
    }

    fun publish(context: Context, owner: String, item: CourseOccurrence, config: CourseReminderConfig, now: Long) {
        ensureChannels(context)
        if (!enabled(context)) return
        val id = if (item.course.id == -1) DEMO_ID else ID
        val nativeReady = config.xiaomi && protocol(context) >= 3 && CourseIslandAccess.awaitReady()
        val key = "$owner:${item.key}:${now >= item.start}:${config.live}:${config.xiaomi}:${config.islandAppearance}:$nativeReady:${item.course.hashCode()}:${if (config.xiaomi) now / 300_000 else 0}"
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.activeNotifications.any { it.tag == TAG && it.id == id && it.notification.extras.getString("campusai.renderKey") == key }) return
        try {
            val notification = build(context, owner, item, config.copy(xiaomi = false, live = config.live && !config.xiaomi), now)
                .also { it.extras.putString("campusai.renderKey", key) }
            val post = { native: Boolean ->
                if (native) CourseIslandStyle.apply(context, notification, item, now, config.leadMinutes,
                    CourseIslandStyle.supportedAppearance(context, config.islandAppearance))
                NotificationManagerCompat.from(context).notify(TAG, id, notification)
            }
            if (nativeReady) CourseIslandAccess.publish(post) else post(false)
        } catch (_: SecurityException) { /* User may revoke notification access between check and post. */ }
    }

    fun cancel(context: Context, demo: Boolean) {
        NotificationManagerCompat.from(context).cancel(TAG, if (demo) DEMO_ID else ID)
    }
}
