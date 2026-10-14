package com.campusai.features.ai

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.campusai.MainActivity
import com.campusai.R
import com.campusai.core.auth.AuthRepository
import com.campusai.core.designsystem.CaesarOrbArtwork
import com.campusai.core.designsystem.CaesarOrbMode
import com.campusai.features.schedule.CourseReminderNotifications
import com.campusai.features.schedule.CourseReminderRuntime
import com.campusai.features.schedule.CourseReminderStore
import com.campusai.features.schedule.CourseIslandAccess
import org.json.JSONObject
import org.json.JSONArray
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** Lives beside the ViewModel host, so a generation remains visible after leaving the AI page. */
@Composable
internal fun AiIslandStatusHost(state: AiUiState, interaction: CaesarOrbMode?) {
    val context = LocalContext.current.applicationContext
    val auth by remember(context) { AuthRepository.getInstance(context).state }.collectAsState()
    val owner = auth.userId.takeIf { auth.signedIn && it.isNotBlank() } ?: "local_user"
    val store = remember(context) { CourseReminderStore(context) }
    var xiaomiEnabled by remember(owner) { mutableStateOf(store.read(owner).xiaomi) }
    val mode = interaction ?: aiOrbPresentation(state, false, false, false, null, null).mode
    // An ongoing interaction belongs to the account which started it, across mode changes.
    val activityOwner = remember(AiIslandNotifications.active(mode)) { owner }
    var wasActive by remember(owner) { mutableStateOf(false) }
    DisposableEffect(context, store, owner) {
        val subscription = store.observe(owner) { config ->
            xiaomiEnabled = config.xiaomi
            if (!config.xiaomi) AiIslandNotifications.cancel(context)
        }
        onDispose {
            subscription.close()
            AiIslandNotifications.cancel(context)
        }
    }
    LaunchedEffect(mode, owner, xiaomiEnabled) {
        if (!xiaomiEnabled || owner != activityOwner) {
            wasActive = false
            AiIslandNotifications.cancel(context)
            return@LaunchedEffect
        }
        if (AiIslandNotifications.active(mode)) {
            wasActive = true
            while (isActive) {
                AiIslandNotifications.publish(context, mode, owner)
                delay(50_000) // Renew a bounded lease; never update for every audio/GL frame.
            }
        } else {
            if (wasActive) AiIslandNotifications.publish(context, mode, owner)
            wasActive = false
            delay(3_000)
            AiIslandNotifications.cancel(context)
        }
    }
}

/** Reuses the course integration's existing opt-in and guarded native publication. */
internal object AiIslandNotifications {
    const val ACTION_OPEN = "com.campusai.ai.OPEN_STATUS"
    private const val CHANNEL = "caesar_ai_status_v1"
    private const val TAG = "caesar-ai-status"
    private const val ID = 742001
    private const val PICTURE = "miui.focus.pic_caesar_orb"
    private const val FACE = "miui.focus.pic_caesar_face"
    private const val OPEN = "miui.focus.action_ai_open"
    private const val STOP = "miui.focus.action_ai_stop"
    private const val ACTION_STOP = "com.campusai.ai.STOP_STATUS"
    private val revision = AtomicLong()
    private val postLock = Any()
    private val mutableStopRequests = MutableSharedFlow<CaesarOrbMode>(extraBufferCapacity = 1)
    val stopRequests = mutableStopRequests.asSharedFlow()

    internal fun handleStop(context: Context, intent: Intent) = synchronized(postLock) {
        if (intent.action != ACTION_STOP || intent.getLongExtra("revision", -1) != revision.get() ||
            intent.getStringExtra("owner") != CourseReminderRuntime.owner(context)) return@synchronized
        val mode = CaesarOrbMode.entries.firstOrNull { it.name == intent.getStringExtra("mode") }
            ?.takeIf(::active) ?: return@synchronized
        revision.incrementAndGet() // Reject replay and any queued publication for this status.
        mutableStopRequests.tryEmit(mode)
        NotificationManagerCompat.from(context).cancel(TAG, ID)
    }

    fun active(mode: CaesarOrbMode) = mode in setOf(CaesarOrbMode.LISTENING, CaesarOrbMode.TRANSCRIBING,
        CaesarOrbMode.THINKING, CaesarOrbMode.RESPONDING, CaesarOrbMode.SPEAKING)

    suspend fun publish(context: Context, mode: CaesarOrbMode, owner: String = CourseReminderRuntime.owner(context)) {
        val request = revision.incrementAndGet()
        withContext(Dispatchers.IO) {
            // Reuse the user's existing Xiaomi integration opt-in, without opening a new prompt.
            val store = CourseReminderStore(context)
            val coroutine = currentCoroutineContext()
            fun isCurrent(): Boolean {
                coroutine.ensureActive()
                if (revision.get() != request) return false
                val allowed = CourseReminderRuntime.owner(context) == owner && store.read(owner).xiaomi &&
                    CourseReminderNotifications.protocol(context) >= 3 &&
                    NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                    (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                if (!allowed) cancel(context, request)
                return allowed
            }
            if (!isCurrent()) return@withContext
            if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Caesar AI 状态", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "语音输入、回复和朗读状态；不展示对话内容"
                    setSound(null, null); enableVibration(false)
                },
            )
            val notification = build(context, mode, request, owner)
            val post = { native: Boolean ->
                synchronized(postLock) {
                    if (isCurrent()) {
                        if (!native) {
                            notification.extras.remove("miui.focus.param")
                            notification.extras.remove("miui.focus.pics")
                        }
                        try { NotificationManagerCompat.from(context).notify(TAG, ID, notification) }
                        catch (_: SecurityException) { /* Permission can be revoked during publication. */ }
                    }
                }
            }
            val nativeReady = CourseIslandAccess.awaitReady()
            if (!isCurrent()) return@withContext
            if (nativeReady) CourseIslandAccess.publish(post, ::isCurrent) else post(false)
        }
    }

    fun cancel(context: Context) = cancel(context, request = null)

    private fun cancel(context: Context, request: Long?) = synchronized(postLock) {
        if (request != null && revision.get() != request) return@synchronized
        revision.incrementAndGet()
        NotificationManagerCompat.from(context).cancel(TAG, ID)
    }

    internal fun build(context: Context, mode: CaesarOrbMode, request: Long = revision.get(),
        owner: String = CourseReminderRuntime.owner(context)): Notification {
        val label = when (mode) {
            CaesarOrbMode.LISTENING -> "正在聆听"
            CaesarOrbMode.TRANSCRIBING -> "正在转写"
            CaesarOrbMode.THINKING -> "正在处理"
            CaesarOrbMode.RESPONDING -> "正在生成回复"
            CaesarOrbMode.SPEAKING -> "正在朗读"
            CaesarOrbMode.COMPLETE -> "本次处理结束"
            CaesarOrbMode.CANCELLED -> "已停止"
            CaesarOrbMode.ERROR -> "本次未完成"
            CaesarOrbMode.IDLE -> "准备就绪"
        }
        val open = PendingIntent.getActivity(context, ID, Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopLabel = when (mode) {
            CaesarOrbMode.SPEAKING -> "停止朗读"
            CaesarOrbMode.LISTENING, CaesarOrbMode.TRANSCRIBING -> "结束语音"
            else -> "停止生成"
        }
        val stop = PendingIntent.getBroadcast(context, ID,
            Intent(context, AiIslandActionReceiver::class.java).setAction(ACTION_STOP)
                .setData("caesar://ai-status/$request".toUri())
                .putExtra("revision", request).putExtra("owner", owner).putExtra("mode", mode.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_course_notification)
            .setLargeIcon(CaesarOrbArtwork.bitmap(true))
            .setContentTitle("Caesar∞").setContentText(label).setContentIntent(open)
            .setSilent(true).setOnlyAlertOnce(true).setOngoing(active(mode)).setAutoCancel(!active(mode))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setCategory(NotificationCompat.CATEGORY_STATUS)
            .setTimeoutAfter(if (active(mode)) 120_000 else 3_000)
            .addAction(0, "查看回复", open)
        if (active(mode)) builder.addAction(0, stopLabel, stop)
        val notification = builder.build()
        val picture = JSONObject().put("type", 1).put("pic", PICTURE).put("picDark", PICTURE)
        val text = JSONObject().put("title", label).put("colorTitle", "#FFFFFF").put("colorTitleDark", "#FFFFFF")
        val island = JSONObject().put("islandProperty", 1).put("islandOrder", false)
            .put("islandTimeout", if (active(mode)) 120 else 3).put("needCloseAnimation", true).put("dismissIsland", false)
            .put("bigIslandArea", JSONObject().put("imageTextInfoLeft", JSONObject()
                .put("type", 1).put("picInfo", picture).put("textInfo", text))
                .put("picInfo", JSONObject().put("type", 1).put("pic", FACE).put("picDark", FACE)))
            .put("smallIslandArea", JSONObject().put("combinePicInfo", JSONObject().put("picInfo", picture)))
        val body = JSONObject().put("protocol", 3).put("business", "campusai_ai_status")
            .put("updatable", true).put("ticker", label).put("enableFloat", false)
            .put("reopen", "reopen").put("islandFirstFloat", false)
            .put("tickerPic", PICTURE).put("tickerPicDark", PICTURE).put("aodPic", PICTURE).put("aodTitle", label)
            .put("isShowNotification", true).put("timeout", if (active(mode)) 2 else 1)
            .put("picInfo", picture).put("param_island", island)
            .put("baseInfo", JSONObject().put("type", 1).put("title", "Caesar∞").put("content", label))
            .put("textButton", JSONArray().apply {
                put(JSONObject().put("action", OPEN).put("actionTitle", "查看回复").put("actionIntentType", 1))
                if (active(mode)) put(JSONObject().put("action", STOP).put("actionTitle", stopLabel)
                    .put("actionIntentType", 2).put("clickWithCollapse", true))
            })
        notification.extras.putString("miui.focus.param", JSONObject().put("param_v2", body).toString())
        notification.extras.putBundle("miui.focus.pics", Bundle().apply {
            putParcelable(PICTURE, Icon.createWithBitmap(CaesarOrbArtwork.bitmap(true)))
            putParcelable(FACE, Icon.createWithBitmap(AiIslandFace.bitmap(mode)))
        })
        notification.extras.putBundle("miui.focus.actions", Bundle().apply {
            putParcelable(OPEN, Notification.Action.Builder(null, "查看回复", open).build())
            if (active(mode)) putParcelable(STOP, Notification.Action.Builder(null, stopLabel, stop).build())
        })
        return notification
    }
}
