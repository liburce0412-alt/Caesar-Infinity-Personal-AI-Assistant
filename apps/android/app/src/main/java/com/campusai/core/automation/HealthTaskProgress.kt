package com.campusai.core.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.campusai.MainActivity
import com.campusai.core.designsystem.CaesarOrbMode
import com.campusai.features.ai.AiIslandNotifications
import com.campusai.features.schedule.CourseIslandAccess
import com.campusai.features.schedule.CourseReminderNotifications
import com.campusai.features.schedule.CourseReminderRuntime
import com.campusai.features.ai.IslandSettingsStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class HealthTaskStage(val label: String, val mode: CaesarOrbMode) {
    SYNCING("同步中", CaesarOrbMode.THINKING),
    REFRESHED("已同步", CaesarOrbMode.THINKING),
    GENERATING("准备中", CaesarOrbMode.RESPONDING),
    SENDING("新消息", CaesarOrbMode.RESPONDING),
    COMPLETE("已完成", CaesarOrbMode.COMPLETE),
    ERROR("未完成", CaesarOrbMode.ERROR),
}

interface HealthTaskProgressPublisher {
    suspend fun publish(stage: HealthTaskStage, preview: String? = null)
    fun clear()

    object None : HealthTaskProgressPublisher {
        override suspend fun publish(stage: HealthTaskStage, preview: String?) = Unit
        override fun clear() = Unit
    }
}

/** Separate notification identity keeps automation from replacing a live conversation's island. */
class AndroidHealthTaskProgressPublisher(context: Context) : HealthTaskProgressPublisher {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)
    private var owner: String? = null
    private var revision = 0L
    private var lastPreview: String? = null
    private var hasReply = false

    override suspend fun publish(stage: HealthTaskStage, preview: String?) {
        withContext(Dispatchers.IO) { publishOnWorker(stage, preview) }
    }

    private suspend fun publishOnWorker(stage: HealthTaskStage, preview: String?) {
        if (stage == HealthTaskStage.SYNCING) {
            owner = CourseReminderRuntime.owner(context)
            lastPreview = null
            hasReply = false
        }
        if (stage == HealthTaskStage.ERROR) { lastPreview = null; hasReply = false }
        if (preview != null) lastPreview = preview
        if (stage == HealthTaskStage.SENDING && !preview.isNullOrBlank()) hasReply = true
        val currentOwner = owner ?: return
        val showContent = IslandSettingsStore(context).read(currentOwner).healthContentPreview
        val request = ++revision
        val coroutine = currentCoroutineContext()
        fun allowed(): Boolean {
            coroutine.ensureActive()
            return request == revision && currentOwner == CourseReminderRuntime.owner(context) &&
                IslandSettingsStore(context).read(currentOwner).healthAutomation &&
                IslandSettingsStore(context).read(currentOwner).healthContentPreview == showContent &&
                healthTaskNotificationsEnabled(context, CHANNEL)
        }
        if (!allowed()) { clear(); return }
        if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Caesar 健康任务状态", NotificationManager.IMPORTANCE_LOW)
                .apply { setSound(null, null); enableVibration(false) })
        val open = PendingIntent.getActivity(context, ID, Intent(context, MainActivity::class.java)
            .setAction(HealthTaskNotificationContract.ACTION_OPEN_CONVERSATION)
            .putExtra(HealthTaskNotificationContract.EXTRA_CONVERSATION_ID,
                HealthTaskNotificationContract.conversationId(HealthTaskDefaults.TASK_ID))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = AiIslandNotifications.build(context, stage.mode, owner = currentOwner,
            labelOverride = if (stage == HealthTaskStage.COMPLETE && hasReply) "新消息" else stage.label,
            openOverride = open, allowStop = false, channel = CHANNEL,
            contentPreview = lastPreview.takeIf { showContent }, openLabel = "查看回复".takeIf { hasReply })
        val post = { native: Boolean ->
            if (allowed()) {
                if (!native) {
                    notification.extras.remove("miui.focus.param")
                    notification.extras.remove("miui.focus.pics")
                }
                try { manager.notify(TAG, ID, notification) } catch (_: SecurityException) { }
            }
        }
        val ready = CourseReminderNotifications.protocol(context) >= 3 && CourseIslandAccess.awaitReady()
        if (!allowed()) { clear(); return }
        if (ready) CourseIslandAccess.publish(post, ::allowed) else post(false)
        if (stage == HealthTaskStage.COMPLETE || stage == HealthTaskStage.ERROR) lastPreview = null
    }

    override fun clear() {
        revision++
        lastPreview = null
        hasReply = false
        manager.cancel(TAG, ID)
    }

    companion object {
        fun cancel(context: Context) { NotificationManagerCompat.from(context).cancel(TAG, ID) }
        private const val CHANNEL = "caesar_health_task_status_v1"
        private const val TAG = "caesar-health-task-status"
        private const val ID = 742002
    }
}

internal fun healthTaskSummaryPreview(summary: com.campusai.core.ai.CloudDailyHealthSummary): String = buildList {
    summary.steps?.let { add("步数 $it") }
    summary.activityMinutes?.let { add("活动 ${it}分钟") }
    summary.sleepMinutes?.let { add("睡眠 ${it / 60}小时${it % 60}分") }
    summary.averageHeartRateBpm?.let { add("平均心率 ${it.toInt()} 次/分") }
}.joinToString("\n").ifBlank { "今日健康摘要已刷新" }
