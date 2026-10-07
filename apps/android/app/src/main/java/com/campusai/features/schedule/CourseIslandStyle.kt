package com.campusai.features.schedule

import android.app.Notification
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Bundle
import com.campusai.R
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Native HyperOS templates: the system owns expansion, timers and touch targets. */
internal object CourseIslandStyle {
    const val PICTURE = "miui.focus.pic_course"
    const val PICTURE_LIGHT = "miui.focus.pic_course_light"
    const val OPEN = "miui.focus.action_course_open"
    const val DISMISS = "miui.focus.action_course_dismiss"

    /** Match this ROM's per-app custom-layout gate; an unknown gate is not a grant. */
    fun customLayoutsAllowed(context: Context): Boolean = runCatching {
        val resources = context.packageManager.getResourcesForApplication("miui.systemui.plugin")
        val id = resources.getIdentifier("config_canShowCustomFocusPackages", "array", "miui.systemui.plugin")
        id != 0 && context.packageName in resources.getStringArray(id)
    }.getOrDefault(false)

    fun supportedAppearance(context: Context, requested: CourseIslandAppearance): CourseIslandAppearance =
        if (requested == CourseIslandAppearance.AURORA && customLayoutsAllowed(context)) requested
        else CourseIslandAppearance.COLORFUL

    fun params(item: CourseOccurrence, now: Long, dark: Boolean = false, leadMinutes: Int = 15): JSONObject {
        val active = now >= item.start
        val target = if (active) item.end else item.start
        val stage = if (active) "上课中" else "即将上课"
        val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        val range = "${time.format(Instant.ofEpochMilli(item.start))} — ${time.format(Instant.ofEpochMilli(item.end))}"
        val room = item.course.location.substringAfterLast('[').substringBefore(']').ifBlank { "教室待补充" }
        val period = if (item.course.periodStart > 0) "第 ${item.course.periodStart}–${item.course.periodEnd} 节" else "课程提醒"
        val accent = if (active) "#75E4D0" else "#B3C5FF"
        val lightAccent = if (active) "#147A70" else "#6251B5"
        val timer = JSONObject().put("timerType", -1).put("timerWhen", target).put("timerSystemCurrent", now)
        fun text(title: String) = JSONObject().put("title", title).put("colorTitle", "#172434")
            .put("colorTitleDark", "#FFFFFF").put("showHighlightColor", false)
        val picture = JSONObject().put("type", 1).put("pic", PICTURE_LIGHT).put("picDark", PICTURE)
        // The collapsed island stays black even when the notification shade is light.
        val islandPicture = JSONObject().put("type", 1).put("pic", PICTURE).put("picDark", PICTURE)
        val progressStart = if (active) item.start else item.start - leadMinutes.coerceAtLeast(1) * 60_000L
        val progress = (((now - progressStart) * 100) / (target - progressStart).coerceAtLeast(1)).toInt().coerceIn(0, 100)
        val island = JSONObject().put("islandProperty", 1).put("islandOrder", false)
            .put("islandTimeout", ((item.end - now) / 1000).coerceAtLeast(1))
            .put("dismissIsland", false).put("needCloseAnimation", true)
            .put("bigIslandArea", JSONObject()
                .put("imageTextInfoLeft", JSONObject().put("type", 1).put("picInfo", islandPicture)
                    .put("textInfo", text(if (active) "上课" else "待上课").put("colorTitle", "#FFFFFF")))
                .put("sameWidthDigitInfo", JSONObject().put("timerInfo", timer).put("content", "")
                    .put("colorDigit", accent).put("showHighlightColor", true).put("turnAnim", true)))
            .put("smallIslandArea", JSONObject().put("combinePicInfo", JSONObject().put("picInfo", islandPicture)
                .put("progressInfo", JSONObject().put("progress", progress).put("colorReach", accent)
                    .put("colorUnReach", "#3A415A").put("isCCW", false))))
        val body = JSONObject().put("protocol", 3).put("business", "campusai_course")
            .put("updatable", true).put("ticker", "$stage · ${item.course.name}")
            .put("tickerPic", PICTURE_LIGHT).put("tickerPicDark", PICTURE).put("aodPic", PICTURE).put("aodTitle", "$stage · $room")
            .put("enableFloat", false).put("islandFirstFloat", !active).put("isShowNotification", true).put("reopen", "reopen")
            .put("timeout", ((item.end - now + 59_999) / 60_000).coerceAtLeast(1))
            .put("outEffectSrc", "outer_glow").put("bgInfo", JSONObject().put("type", 1)
                .put("colorBg", if (dark) "#E619202D" else "#E6F8FCFF"))
            .put("picInfo", picture).put("param_island", island)
            .put("baseInfo", text(item.course.name).put("type", 2).put("content", room)
                .put("subContent", period)
                .put("extraTitle", stage).put("showDivider", true)
                .put("colorContent", "#445167").put("colorContentDark", "#BED5DB")
                .put("colorSubContent", "#445167").put("colorSubContentDark", "#BED5DB")
                .put("colorExtraTitle", lightAccent).put("colorExtraTitleDark", accent))
            .put("hintInfo", JSONObject().put("type", 2).put("content", if (active) "距下课" else "距上课")
                .put("subContent", "${if (active) "本节" else "开课"} · $range").put("timerInfo", timer)
                .put("colorTitle", lightAccent).put("colorTitleDark", accent)
                .put("colorContent", "#445167").put("colorContentDark", "#BED5DB")
                .put("colorSubContent", "#445167").put("colorSubContentDark", "#BAC8D9")
                .put("actionInfo", JSONObject().put("action", OPEN).put("actionTitle", "查看课表")
                    .put("actionIntentType", 1)))
        return JSONObject().put("param_v2", body)
    }

    fun apply(context: Context, notification: Notification, item: CourseOccurrence, now: Long, leadMinutes: Int = 15,
        appearance: CourseIslandAppearance = CourseIslandAppearance.COLORFUL) {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        notification.extras.putString("miui.focus.param", params(item, now, dark, leadMinutes).toString())
        notification.extras.putBundle("miui.focus.pics", Bundle().apply {
            putParcelable(PICTURE, Icon.createWithResource(context, R.drawable.ic_course_notification))
            putParcelable(PICTURE_LIGHT, Icon.createWithResource(context, R.drawable.ic_course_notification).setTint(0xff243746.toInt()))
        })
        notification.extras.putBundle("miui.focus.actions", Bundle().apply {
            putParcelable(OPEN, Notification.Action.Builder(null, "查看课表", notification.contentIntent).build())
            notification.actions?.firstOrNull()?.let { putParcelable(DISMISS, it) }
        })
        if (appearance == CourseIslandAppearance.AURORA) {
            // Custom RemoteViews use the unwrapped protocol body. Keep the ordinary
            // notification and standard island payload for hosts that ignore custom layouts.
            val custom = params(item, now, dark, leadMinutes).getJSONObject("param_v2")
                .put("outEffectSrc", "")
            notification.extras.putString("miui.focus.param.custom", custom.toString())
            notification.extras.putAll(CourseIslandExpandedCard.extras(context, notification, item, now))
        }
    }
}
