package com.campusai.core.automation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.campusai.MainActivity
import com.campusai.R
import com.campusai.core.database.AiReportWriteCoordinator
import com.campusai.core.database.AiReportEntity
import com.campusai.core.database.CampusDao
import com.campusai.core.ai.ResolvedExecution
import com.campusai.core.model.AiMode
import com.campusai.features.ai.IslandSettingsStore
import com.campusai.features.schedule.CourseReminderRuntime
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

interface HealthTaskConversationWriter {
    suspend fun appendAssistantMessages(
        task: ScheduledTaskConfig,
        messages: List<String>,
        createdAt: Long,
        execution: ResolvedExecution? = null,
    ): Result<String>
}

class RoomHealthTaskConversationWriter(
    private val dao: CampusDao,
) : HealthTaskConversationWriter {
    override suspend fun appendAssistantMessages(
        task: ScheduledTaskConfig,
        messages: List<String>,
        createdAt: Long,
        execution: ResolvedExecution?,
    ): Result<String> = runCatching {
        require(messages.isNotEmpty() && messages.all(String::isNotBlank))
        AiReportWriteCoordinator.withLock {
            val conversationId = HealthTaskNotificationContract.conversationId(task.id)
            // These tasks are device-local, with consent stored in NoBackupScheduledTaskStore.
            // A later account login must not claim their earlier health conversations.
            val existing = dao.getAiReport(conversationId, ownerUserId = "local_user")
            val rows = if (existing == null) {
                JSONArray()
            } else {
                runCatching { JSONArray(existing.messagesJson) }.getOrElse {
                    throw HealthTaskException("task_conversation_invalid", "定时任务会话无法读取。")
                }
            }
            messages.forEach { message ->
                rows.put(
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", message)
                        .put("presentation", JSONObject.NULL)
                        .put("attachments", JSONArray())
                        .put("imageRefs", JSONArray())
                        .put("cloudHealthSensitive", true),
                )
            }
            dao.insertAiReport(
                AiReportEntity(
                    id = conversationId,
                    provider = execution?.provider?.name ?: existing?.provider ?: "LOCAL",
                    mode = AiMode.FAST.name,
                    model = execution?.model ?: existing?.model ?: "Caesar∞ · 本机状态",
                    executionEngine = execution?.engine?.name ?: existing?.executionEngine ?: "LOCAL_DETERMINISTIC",
                    requestId = execution?.requestId ?: existing?.requestId ?: "automation-status:${task.id}:$createdAt",
                    title = CONVERSATION_TITLE,
                    summary = messages.last().take(160),
                    messagesJson = rows.toString(),
                    createdAt = existing?.createdAt ?: createdAt,
                    updatedAt = createdAt,
                    ownerUserId = "local_user",
                ),
            )
            conversationId
        }
    }

    private companion object {
        const val CONVERSATION_TITLE = "Caesar∞ 日常"
    }
}

sealed interface HealthTaskNotificationDelivery {
    data object Delivered : HealthTaskNotificationDelivery
    data object PermissionDisabled : HealthTaskNotificationDelivery
    data object Superseded : HealthTaskNotificationDelivery
    data object Failed : HealthTaskNotificationDelivery
}

interface HealthTaskNotificationPublisher {
    suspend fun publish(
        taskId: String,
        messages: List<String>,
        emittedAt: Long,
        deliveryId: String = "$taskId:$emittedAt",
        withValidityLease: suspend (() -> Unit) -> Boolean = { deliver ->
            deliver()
            true
        },
    ): HealthTaskNotificationDelivery
}

object HealthTaskNotificationContract {
    const val ACTION_OPEN_CONVERSATION = "com.campusai.action.OPEN_AUTOMATION_CONVERSATION"
    const val EXTRA_CONVERSATION_ID = "com.campusai.extra.AUTOMATION_CONVERSATION_ID"
    const val CHANNEL_ID = "caesar_daily_messages_v1"

    fun conversationId(taskId: String): String = "automation-health-$taskId"
}

/** Shared by settings and the publisher so a user-muted channel is never shown as enabled. */
fun healthTaskNotificationsEnabled(context: Context, channelId: String = HealthTaskNotificationContract.CHANNEL_ID): Boolean {
    val appContext = context.applicationContext
    if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return false
    if (
        Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    if (Build.VERSION.SDK_INT >= 26) {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(channelId)
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false
    }
    return true
}

class AndroidHealthTaskNotificationPublisher(
    context: Context,
    private val interMessageDelayMillis: Long = DEFAULT_MESSAGE_DELAY_MILLIS,
) : HealthTaskNotificationPublisher {
    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    override suspend fun publish(
        taskId: String,
        messages: List<String>,
        emittedAt: Long,
        deliveryId: String,
        withValidityLease: suspend (() -> Unit) -> Boolean,
    ): HealthTaskNotificationDelivery {
        require(messages.size in 1..3)
        val owner = CourseReminderRuntime.owner(appContext)
        ensureChannel()
        if (!healthTaskNotificationsEnabled(appContext)) {
            return HealthTaskNotificationDelivery.PermissionDisabled
        }
        messages.forEachIndexed { index, message ->
            if (index > 0) delay(interMessageDelayMillis)
            if (!healthTaskNotificationsEnabled(appContext)) {
                return HealthTaskNotificationDelivery.PermissionDisabled
            }
            val notificationId = notificationId(deliveryId, index)
            val pendingIntent = PendingIntent.getActivity(
                appContext,
                notificationId,
                Intent(appContext, MainActivity::class.java)
                    .setAction(HealthTaskNotificationContract.ACTION_OPEN_CONVERSATION)
                    .putExtra(
                        HealthTaskNotificationContract.EXTRA_CONVERSATION_ID,
                        HealthTaskNotificationContract.conversationId(taskId),
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            try {
                val delivered = withValidityLease {
                    if (owner == CourseReminderRuntime.owner(appContext)) {
                        val text = if (IslandSettingsStore(appContext).read(owner).healthContentPreview) message
                            else "收到一条日常短消息，点击查看"
                        val notification = NotificationCompat.Builder(appContext, HealthTaskNotificationContract.CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_launcher_monochrome)
                            .setContentTitle(NOTIFICATION_TITLE)
                            .setContentText(text)
                            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                            .setAutoCancel(true)
                            .setContentIntent(pendingIntent)
                            .build()
                        manager.notify(TAG, notificationId, notification)
                    }
                }
                if (!delivered || owner != CourseReminderRuntime.owner(appContext)) return HealthTaskNotificationDelivery.Superseded
            } catch (_: SecurityException) {
                return HealthTaskNotificationDelivery.PermissionDisabled
            }
        }
        return HealthTaskNotificationDelivery.Delivered
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val systemManager = appContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            HealthTaskNotificationContract.CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = CHANNEL_DESCRIPTION
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        }
        systemManager.createNotificationChannel(channel)
    }

    private fun notificationId(deliveryId: String, index: Int): Int =
        ("$deliveryId:$index".hashCode() and Int.MAX_VALUE).coerceAtLeast(1)

    companion object {
        private const val TAG = "caesar-health-daily-messages"
        fun cancelVisible(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.activeNotifications.filter { it.tag == TAG }.forEach { manager.cancel(TAG, it.id) }
        }
        private const val CHANNEL_NAME = "Caesar∞ 日常"
        private const val CHANNEL_DESCRIPTION = "Caesar∞ 的日常短消息"
        private const val NOTIFICATION_TITLE = "Caesar∞"
        private const val DEFAULT_MESSAGE_DELAY_MILLIS = 1_500L
    }
}
