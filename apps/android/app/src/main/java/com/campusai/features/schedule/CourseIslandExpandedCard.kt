package com.campusai.features.schedule

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.campusai.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** RemoteViews rendered by HyperOS itself, including the expanded native island. */
internal object CourseIslandExpandedCard {
    const val EXPANDED = "miui.focus.rv.island.expand"
    const val SHADE = "miui.focus.rv"
    const val SHADE_NIGHT = "miui.focus.rvNight"

    fun extras(
        context: Context,
        notification: Notification,
        item: CourseOccurrence,
        now: Long,
        elapsedRealtime: Long = SystemClock.elapsedRealtime(),
    ): Bundle = Bundle().apply {
        putParcelable(SHADE, create(context, notification, item, now, elapsedRealtime, dark = false, animate = false))
        putParcelable(SHADE_NIGHT, create(context, notification, item, now, elapsedRealtime, dark = true, animate = false))
        putParcelable(EXPANDED, create(context, notification, item, now, elapsedRealtime, dark = true, animate = true))
    }

    private fun create(
        context: Context,
        notification: Notification,
        item: CourseOccurrence,
        now: Long,
        elapsedRealtime: Long,
        dark: Boolean,
        animate: Boolean,
    ): RemoteViews {
        val active = now >= item.start
        val target = if (active) item.end else item.start
        val room = item.course.location.substringAfterLast('[').substringBefore(']').ifBlank { "教室待补充" }
        val period = if (item.course.periodStart > 0) {
            context.getString(R.string.course_island_period, item.course.periodStart, item.course.periodEnd)
        } else context.getString(R.string.course_island_reminder)
        val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        val range = "${time.format(Instant.ofEpochMilli(item.start))} — ${time.format(Instant.ofEpochMilli(item.end))}"
        val foreground = if (dark) 0xfff1f3ff.toInt() else 0xff182537.toInt()
        val secondary = if (dark) 0xffaebcd0.toInt() else 0xff506078.toInt()
        val accent = if (dark) 0xffc6c7ff.toInt() else 0xff5456a6.toInt()
        val timer = if (dark) 0xffa4e9ff.toInt() else 0xff236d8d.toInt()
        return RemoteViews(context.packageName, R.layout.course_island_expanded_card).apply {
            setInt(R.id.course_island_surface, "setBackgroundResource", if (dark) R.drawable.course_island_card_dark else R.drawable.course_island_card_light)
            setInt(R.id.course_island_frame, "setBackgroundResource", if (dark) R.drawable.course_island_card_frame_dark else R.drawable.course_island_card_frame_light)
            setViewVisibility(R.id.course_island_flow, if (animate) View.VISIBLE else View.GONE)
            setProgressBar(R.id.course_island_flow, 100, 0, true)
            setTextViewText(R.id.course_island_stage, context.getString(if (active) R.string.course_island_active else R.string.course_island_upcoming))
            setTextViewText(R.id.course_island_period, period)
            setTextViewText(R.id.course_island_name, item.course.name)
            setTextViewText(R.id.course_island_room, room)
            setTextViewText(R.id.course_island_timer_label, context.getString(if (active) R.string.course_island_until_end else R.string.course_island_until_start))
            setTextViewText(R.id.course_island_range, range)
            setTextColor(R.id.course_island_stage, accent)
            setTextColor(R.id.course_island_name, foreground)
            setTextColor(R.id.course_island_timer, timer)
            setTextColor(R.id.course_island_open, foreground)
            for (id in listOf(R.id.course_island_period, R.id.course_island_room, R.id.course_island_timer_label, R.id.course_island_range, R.id.course_island_dismiss)) {
                setTextColor(id, secondary)
            }
            setInt(R.id.course_island_icon, "setColorFilter", accent)
            setInt(R.id.course_island_divider, "setBackgroundColor", if (dark) 0x20c3d1ef else 0x18344864)
            // Chronometer runs in SystemUI. Convert epoch deadlines to elapsed time once,
            // rather than waking our process or reposting the notification every second.
            setChronometerCountDown(R.id.course_island_timer, true)
            setChronometer(R.id.course_island_timer, elapsedRealtime + (target - now).coerceAtLeast(0L), null, target > now)
            notification.contentIntent?.let {
                setOnClickPendingIntent(R.id.course_island_open, it)
                setOnClickPendingIntent(R.id.course_island_name, it)
            } ?: setViewVisibility(R.id.course_island_open, View.GONE)
            val dismiss = notification.deleteIntent ?: notification.actions?.firstOrNull()?.actionIntent
            dismiss?.let { setOnClickPendingIntent(R.id.course_island_dismiss, it) }
                ?: setViewVisibility(R.id.course_island_dismiss, View.GONE)
        }
    }
}
