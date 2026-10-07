package com.campusai.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.campusai.core.designsystem.SpectraDialog
import com.campusai.core.model.CourseSchedule
import com.campusai.features.schedule.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate

@Composable
internal fun CourseReminderSettingsDialog(owner: String, courses: List<CourseSchedule>, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val saved = remember(owner) { CourseReminderStore(context).read(owner) }
    var enabled by rememberSaveable(owner) { mutableStateOf(saved.enabled) }
    var monday by rememberSaveable(owner) { mutableStateOf(saved.semesterMonday) }
    var weeks by rememberSaveable(owner) { mutableStateOf(saved.semesterWeeks.toString()) }
    var lead by rememberSaveable(owner) { mutableStateOf(saved.leadMinutes.toString()) }
    var xiaomi by rememberSaveable(owner) { mutableStateOf(saved.xiaomi) }
    val customIslandAllowed = remember { CourseIslandStyle.customLayoutsAllowed(context) }
    var islandAppearance by rememberSaveable(owner) {
        mutableStateOf(CourseIslandStyle.supportedAppearance(context, saved.islandAppearance))
    }
    var notificationAllowed by remember { mutableStateOf(CourseReminderNotifications.enabled(context)) }
    var exactAllowed by remember { mutableStateOf(CourseReminderRuntime.canScheduleExactly(context)) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showAllIssues by remember { mutableStateOf(false) }
    var islandReady by remember { mutableStateOf(CourseIslandAccess.ready()) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationAllowed = CourseReminderNotifications.enabled(context)
        CourseReminderRuntime.requestRefresh(context)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationAllowed = CourseReminderNotifications.enabled(context)
                exactAllowed = CourseReminderRuntime.canScheduleExactly(context)
                islandReady = CourseIslandAccess.ready()
                CourseReminderRuntime.requestRefresh(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        val listener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, _ -> islandReady = CourseIslandAccess.ready() }
        rikka.shizuku.Shizuku.addRequestPermissionResultListener(listener)
        onDispose { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(listener) }
    }
    val config = CourseReminderConfig(enabled, monday.trim(), weeks.toIntOrNull() ?: 20,
        lead.toIntOrNull() ?: 15, live = false, xiaomi = xiaomi, islandAppearance = islandAppearance)
    val issues = courses.mapNotNull { course -> reminderIssue(course, config)?.let { course.name to it } }
    SpectraDialog(onDismissRequest = { if (!saving) onDismiss() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("课程提醒", style = MaterialTheme.typography.headlineSmall)
            Text("课前提醒一次，上课后安静倒计时，下课自动收起。", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("自动提醒", Modifier.weight(1f)); Switch(enabled, { enabled = it }, enabled = !saving)
            }
            OutlinedTextField(monday, { monday = it }, label = { Text("第1周周一 · YYYY-MM-DD") },
                supportingText = { Text("用于计算周次与课表日期，不确定时请核对校历。") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !saving)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(weeks, { weeks = it }, label = { Text("学期周数") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !saving)
                OutlinedTextField(lead, { lead = it }, label = { Text("提前分钟") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !saving)
            }
            if (CourseReminderNotifications.protocol(context) >= 3) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("小米超级岛", Modifier.weight(1f)); Switch(xiaomi, { xiaomi = it }, enabled = !saving)
                }
                Text(if (islandReady) "Shizuku 已授权 · 原生倒计时与展开卡片" else "可用 Shizuku 接入原生超级岛；不可用时保留普通通知。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (xiaomi) {
                    if (customIslandAllowed) {
                        Text("展开卡片", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = islandAppearance == CourseIslandAppearance.AURORA,
                                onClick = { islandAppearance = CourseIslandAppearance.AURORA }, enabled = !saving,
                                label = { Text("紫白青蓝") })
                            FilterChip(selected = islandAppearance == CourseIslandAppearance.COLORFUL,
                                onClick = { islandAppearance = CourseIslandAppearance.COLORFUL }, enabled = !saving,
                                label = { Text("彩色流光") })
                        }
                    } else {
                        Text("当前系统使用深色玻璃与原生彩色流光。", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("发布卡片时会短暂调整小米推送服务的联网规则，并自动恢复；Shizuku 停止后使用普通通知。", style = MaterialTheme.typography.bodySmall)
                    if (!islandReady) TextButton(onClick = {
                        if (CourseIslandAccess.running()) CourseIslandAccess.requestPermission()
                        else message = "请先在 Shizuku 中启动服务，再返回授权。"
                    }) { Text("授权 Shizuku") }
                }
            }
            Text("${courses.size - issues.size} / ${courses.size} 门课程可计算提醒", style = MaterialTheme.typography.titleSmall)
            if (issues.isNotEmpty()) {
                Text("以下课程暂不自动提醒；可在课程详情中编辑：", style = MaterialTheme.typography.bodySmall)
                (if (showAllIssues) issues else issues.take(3)).forEach { (name, issue) -> Text("$name：$issue", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (issues.size > 3) TextButton(onClick = { showAllIssues = !showAllIssues }) { Text(if (showAllIssues) "收起待核对课程" else "查看全部 ${issues.size} 条待核对记录") }
            }
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && !notificationAllowed) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else if (Build.VERSION.SDK_INT >= 26) context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                else context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text(if (notificationAllowed) "通知权限已开 · 管理锁屏显示" else "开启通知权限") }
            if (!exactAllowed && Build.VERSION.SDK_INT >= 31) {
                Text("未允许准点提醒时，系统省电可能延迟通知。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) {
                    Text("允许准点提醒")
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onDismiss, enabled = !saving) { Text("取消") }
                Button(enabled = !saving, onClick = {
                    val date = runCatching { LocalDate.parse(monday.trim()) }.getOrNull()
                    val error = when {
                        monday.isNotBlank() && date?.dayOfWeek != DayOfWeek.MONDAY -> "请填写有效的周一日期"
                        weeks.toIntOrNull() !in 1..30 -> "学期周数应为1至30"
                        lead.toIntOrNull() !in 0..60 -> "提前分钟应为0至60"
                        enabled && issues.size == courses.size -> "没有可计算提醒的课程，请先补齐日期和起止时间"
                        else -> null
                    }
                    if (error != null) message = error else {
                        saving = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { CourseReminderRuntime.save(context, owner, config) }
                                onSaved()
                            } catch (failure: Exception) { message = "保存失败：${failure.message}" }
                            finally { saving = false }
                        }
                    }
                }) { Text(if (saving) "保存中…" else "保存") }
            }
        }
    }
}
