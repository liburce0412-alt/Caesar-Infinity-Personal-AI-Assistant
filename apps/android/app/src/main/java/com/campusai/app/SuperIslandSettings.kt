package com.campusai.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.campusai.core.model.CourseSchedule
import com.campusai.features.ai.IslandSettings
import com.campusai.features.ai.IslandSettingsStore
import com.campusai.features.schedule.CourseIslandAccess
import com.campusai.features.schedule.CourseReminderNotifications
import com.campusai.features.schedule.CourseReminderRuntime
import com.campusai.features.schedule.CourseReminderStore

@Composable
internal fun SuperIslandSettings(owner: String, courses: List<CourseSchedule>) {
    val context = LocalContext.current
    val store = remember { IslandSettingsStore(context) }
    var settings by remember(owner) { mutableStateOf(store.read(owner)) }
    var courseSettings by remember(owner) { mutableStateOf(CourseReminderStore(context).read(owner)) }
    var showCourses by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(CourseIslandAccess.ready()) }
    var notifications by remember { mutableStateOf(CourseReminderNotifications.enabled(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifications = CourseReminderNotifications.enabled(context)
    }
    DisposableEffect(lifecycle, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ready = CourseIslandAccess.ready()
                notifications = CourseReminderNotifications.enabled(context)
            }
        }
        lifecycle.addObserver(observer)
        val subscription = store.observe(owner) { settings = it }
        onDispose { lifecycle.removeObserver(observer); subscription.close() }
    }
    fun save(value: IslandSettings) {
        runCatching {
            check(CourseReminderRuntime.owner(context) == owner)
            store.save(owner, value)
            if (!value.ai) com.campusai.features.ai.AiIslandNotifications.cancel(context)
            if (!value.healthAutomation) com.campusai.core.automation.AndroidHealthTaskProgressPublisher.cancel(context)
            if (!value.healthContentPreview) {
                com.campusai.core.automation.AndroidHealthTaskProgressPublisher.cancel(context)
                com.campusai.core.automation.AndroidHealthTaskNotificationPublisher.cancelVisible(context)
            }
        }.onFailure { message = "保存失败，请重新打开设置。" }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("按类型管理超级岛，保留各自的提醒与显示设置。", style = MaterialTheme.typography.bodyMedium)
        if (CourseReminderNotifications.protocol(context) < 3) {
            Text("当前系统不支持小米超级岛，将使用普通通知。", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { showCourses = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text("课程提醒与课程超级岛", style = MaterialTheme.typography.titleMedium)
                Text(if (courseSettings.enabled) "已启用 · 提前 ${courseSettings.leadMinutes} 分钟" else "设置学期、提前提醒、课程超级岛与样式")
            }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI 交互状态")
                Text("聆听、转写、生成与朗读", style = MaterialTheme.typography.bodySmall)
            }
            Switch(settings.ai, { save(settings.copy(ai = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("健康自动化状态")
                Text("同步、准备短消息、发送与结束", style = MaterialTheme.typography.bodySmall)
            }
            Switch(settings.healthAutomation, { save(settings.copy(healthAutomation = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("显示健康摘要与消息正文")
                Text("用于超级岛和通知；关闭后仅显示状态，锁屏遵循系统设置", style = MaterialTheme.typography.bodySmall)
            }
            Switch(settings.healthContentPreview, { save(settings.copy(healthContentPreview = it)) })
        }
        HorizontalDivider()
        Text(if (ready) "Shizuku 已授权" else "原生超级岛需要启动 Shizuku 并授权；不可用时保留普通通知。",
            style = MaterialTheme.typography.bodySmall)
        Text("发布原生卡片时会短暂调整小米推送服务的联网规则并自动恢复；Shizuku 不可用时使用普通通知。",
            style = MaterialTheme.typography.bodySmall)
        if (!ready) TextButton(onClick = {
            if (CourseIslandAccess.running()) CourseIslandAccess.requestPermission()
            else message = "请先在 Shizuku 中启动服务，再返回授权。"
        }) { Text("授权 Shizuku") }
        TextButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33 && !notifications) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else if (Build.VERSION.SDK_INT >= 26) context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }) { Text(if (notifications) "管理通知与锁屏显示" else "开启通知权限") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (showCourses) CourseReminderSettingsDialog(owner, courses,
        onDismiss = { showCourses = false }, onSaved = {
            courseSettings = CourseReminderStore(context).read(owner)
            showCourses = false
        })
}
