package com.campusai.app

import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.campusai.core.designsystem.SpectraTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.campusai.core.designsystem.BrandMark
import com.campusai.core.designsystem.GlassPanel
import com.campusai.core.designsystem.PageMood
import com.campusai.core.designsystem.SpectraAction
import com.campusai.core.designsystem.SpectraColors
import com.campusai.core.designsystem.SpectraDialog
import com.campusai.core.designsystem.SpectraIconAction
import com.campusai.core.designsystem.SpectraModalBottomSheet
import com.campusai.core.designsystem.SpectraPageScaffold
import com.campusai.core.designsystem.SpectraPrimaryButton
import com.campusai.core.designsystem.SpectraStateKind
import com.campusai.core.designsystem.SpectraStatePane
import com.campusai.core.designsystem.SpectraStatus
import com.campusai.core.designsystem.SpectraStatusTone
import com.campusai.core.designsystem.SpectraSurface
import com.campusai.core.designsystem.SpectraTheme
import com.campusai.core.designsystem.TelemetryChip
import com.campusai.core.model.TimeRecord
import com.campusai.core.model.UiState
import com.campusai.core.health.HealthAvailability
import com.campusai.core.health.HealthFreshness
import com.campusai.core.health.HealthMetricKey
import com.campusai.core.health.HealthMetricStatus
import com.campusai.core.health.HealthMetricTimeSeries
import com.campusai.core.health.HealthMetrics
import com.campusai.core.health.HealthSnapshot
import com.campusai.core.health.HealthPermissionActivity
import com.campusai.core.health.mifitness.MiFitnessSummaryHealthGateway
import com.campusai.features.ai.CaesarHealthUiState
import com.campusai.features.ai.MiFitnessUiStatus
import com.campusai.features.community.CampusAnnouncement
import com.campusai.features.time.TimeViewModel
import com.campusai.features.schedule.CourseDraft
import com.campusai.features.schedule.ScheduleImporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    records: List<TimeRecord>,
    displayName: String,
    avatarUrl: String,
    dailyText: String,
    announcements: UiState<List<CampusAnnouncement>>,
    onRefreshAnnouncements: () -> Unit,
    onStartRecord: () -> Unit,
    onOpenAi: () -> Unit,
    healthState: CaesarHealthUiState,
    onRefreshHealth: () -> Unit,
    onSyncMiFitnessSteps: () -> Unit,
    contentPadding: PaddingValues,
    collapsedComponents: Set<String> = emptySet(),
    onExpandComponent: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val layout = SpectraTheme.layout
    val healthPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onRefreshHealth()
    }
    val todayStart = remember { startOfToday() }
    val todayRecords = records.filter { it.startTime >= todayStart }
    val totalMinutes = todayRecords.sumOf { it.durationMinutes }
    val goalMinutes = 240L
    val streak = remember(records) { calculateStreak(records) }
    val categories = todayRecords.map { it.category }.filter(String::isNotBlank).distinct().take(3)
    val topCategory = todayRecords
        .groupBy { it.category }
        .maxByOrNull { (_, items) -> items.sumOf { it.durationMinutes } }
        ?.key
        ?.takeIf { it.isNotBlank() }

    LaunchedEffect(Unit) {
        onRefreshAnnouncements()
        onRefreshHealth()
    }

    SpectraPageScaffold(mood = PageMood.GROWTH) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = layout.pageHorizontalPadding,
                end = layout.pageHorizontalPadding,
                top = contentPadding.calculateTopPadding() + layout.pageTopSpacing,
                bottom = maxOf(contentPadding.calculateBottomPadding(), layout.pageBottomSpacing),
            ),
            verticalArrangement = Arrangement.spacedBy(layout.sectionGap),
        ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.6f))
                    Text("${timeGreeting()}，${displayName.ifBlank { "Caesar 用户" }}", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        dailyText.ifBlank { "先完成一件最重要的小事" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(.58f),
                    )
                }
                SpectraSurface(
                    modifier = Modifier.size(48.dp),
                    mood = PageMood.GROWTH,
                    shadowed = false,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BrandMark(Modifier.fillMaxSize().padding(5.dp))
                        if (avatarUrl.isNotBlank()) {
                            AsyncImage(
                                model = avatarUrl,
                                contentDescription = "头像",
                                modifier = Modifier.fillMaxSize().padding(3.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
            }
        }
        item {
            CollapsibleComponent(OptionalComponent.TODAY, OptionalComponent.TODAY.name in collapsedComponents, { onExpandComponent(OptionalComponent.TODAY.name) }) {
            SpectraSurface(
                modifier = Modifier.fillMaxWidth(),
                mood = PageMood.GROWTH,
                emphasized = true,
                contentPadding = PaddingValues(0.dp),
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("今日行动", style = MaterialTheme.typography.titleLarge)
                            Text("目标 ${formatDuration(goalMinutes)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.6f))
                        }
                        SpectraStatus(
                            text = "${(totalMinutes * 100 / goalMinutes).coerceAtMost(100)}% 达成",
                            tone = SpectraStatusTone.SUCCESS,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    SpectraProgress(totalMinutes = totalMinutes, goalMinutes = goalMinutes)
                    Spacer(Modifier.height(12.dp))
                    if (categories.isEmpty()) {
                        Text(
                            "从第一条真实记录开始建立你的节奏",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(.56f),
                        )
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(categories) { category -> SpectraStatus(category, tone = SpectraStatusTone.NEUTRAL) }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    SpectraPrimaryButton("开始记录", onStartRecord, Modifier.fillMaxWidth(), icon = Icons.Rounded.Timer)
                }
            }
            }
        }
        item {
            CollapsibleComponent(OptionalComponent.HEALTH, OptionalComponent.HEALTH.name in collapsedComponents, { onExpandComponent(OptionalComponent.HEALTH.name) }) {
            HealthOverviewCard(
                state = healthState,
                onRefresh = onRefreshHealth,
                onCloudRefresh = onSyncMiFitnessSteps,
                onPermissions = {
                    healthPermissionLauncher.launch(Intent(context, HealthPermissionActivity::class.java))
                },
            )
            }
        }
        item {
            CollapsibleComponent(OptionalComponent.STREAK, OptionalComponent.STREAK.name in collapsedComponents, { onExpandComponent(OptionalComponent.STREAK.name) }) {
            SpectraSurface(
                modifier = Modifier.fillMaxWidth(),
                mood = PageMood.GROWTH,
                contentPadding = PaddingValues(0.dp),
            ) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LocalFireDepartment, null, tint = SpectraColors.Warm)
                    Column(Modifier.weight(1f)) {
                        Text("连续 $streak 天", style = MaterialTheme.typography.titleMedium)
                        Text("再完成一次记录，能量条就会继续生长。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.62f))
                        Spacer(Modifier.height(9.dp))
                        Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(SpectraColors.Silver.copy(.55f))) {
                            Box(
                                Modifier
                                    .fillMaxWidth((streak / 7f).coerceIn(.08f, 1f))
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.onSurface.copy(.76f)),
                            )
                        }
                    }
                }
            }
            }
        }
        item {
            CollapsibleComponent(OptionalComponent.INSIGHTS, OptionalComponent.INSIGHTS.name in collapsedComponents, { onExpandComponent(OptionalComponent.INSIGHTS.name) }) {
            SectionLabel("AI 洞察", "基于今天 ${todayRecords.size} 条记录")
            SpectraSurface(
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpenAi),
                mood = PageMood.GROWTH,
                contentPadding = PaddingValues(0.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = SpectraColors.Violet)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (todayRecords.isEmpty()) {
                            "完成第一条记录后，Caesar∞ 会基于实际记录整理今日节奏。"
                        } else {
                            buildString {
                                append("今天已记录 ${todayRecords.size} 条，共 ${formatDuration(totalMinutes)}")
                                if (topCategory != null) append("；时长最多的分类是“$topCategory”")
                                append("。")
                            }
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("让 Caesar∞ 基于这些记录整理下一步", style = MaterialTheme.typography.labelLarge, color = SpectraColors.Focus)
                }
            }
            }
        }
        item {
            CollapsibleComponent(OptionalComponent.ANNOUNCEMENTS, OptionalComponent.ANNOUNCEMENTS.name in collapsedComponents, { onExpandComponent(OptionalComponent.ANNOUNCEMENTS.name) }) {
            SectionLabel("消息", "与你有关")
            when (announcements) {
                UiState.Loading -> SpectraStatePane(
                    kind = SpectraStateKind.LOADING,
                    title = "正在读取最新公告",
                    detail = "时间记录仍在本机可用。",
                    modifier = Modifier.fillMaxWidth(),
                )
                UiState.Empty -> SpectraStatePane(
                    kind = SpectraStateKind.EMPTY,
                    title = "目前没有新公告",
                    detail = "已检查最新消息；你仍可继续记录今日进度。",
                    modifier = Modifier.fillMaxWidth(),
                    actionLabel = "重新检查",
                    onAction = onRefreshAnnouncements,
                )
                is UiState.Error -> SpectraStatePane(
                    kind = SpectraStateKind.ERROR,
                    title = "公告暂时没有同步",
                    detail = announcements.message,
                    modifier = Modifier.fillMaxWidth(),
                    actionLabel = if (announcements.canRetry) "重新读取" else null,
                    onAction = if (announcements.canRetry) onRefreshAnnouncements else null,
                )
                is UiState.Data, is UiState.Offline -> {
                    val items = when (announcements) {
                        is UiState.Data -> announcements.value
                        is UiState.Offline -> announcements.value
                        else -> emptyList()
                    }
                    SpectraSurface(
                        modifier = Modifier.fillMaxWidth(),
                        mood = PageMood.GROWTH,
                        shadowed = false,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            if (announcements is UiState.Offline) {
                                SpectraStatus(
                                    text = "离线 · 显示上次同步公告",
                                    tone = SpectraStatusTone.STALE,
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                            items.forEachIndexed { index, announcement ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                                    Icon(Icons.Rounded.Campaign, null, tint = SpectraColors.Warm, modifier = Modifier.padding(top = 2.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(announcement.title, style = MaterialTheme.typography.titleMedium)
                                        if (announcement.body.isNotBlank()) Text(announcement.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.64f))
                                    }
                                }
                                if (index < items.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.08f))
                            }
                        }
                    }
                }
            }
            }
        }
    }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun HealthOverviewCard(
    state: CaesarHealthUiState,
    onRefresh: () -> Unit,
    onCloudRefresh: () -> Unit,
    onPermissions: () -> Unit,
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var showTechnicalDetails by rememberSaveable { mutableStateOf(false) }
    val cloudConfigured = state.miFitnessConfigured
    val snapshot = state.snapshot?.takeIf { candidate ->
        !cloudConfigured || MiFitnessSummaryHealthGateway.SOURCE_ID in candidate.originPackages
    }
    val displayState = state.copy(snapshot = snapshot)
    val cloudFailure = state.miFitnessStatus in setOf(
        MiFitnessUiStatus.NO_DATA,
        MiFitnessUiStatus.AUTH_ERROR,
        MiFitnessUiStatus.NETWORK_ERROR,
        MiFitnessUiStatus.STORAGE_ERROR,
    )
    val metrics = historicalHealthMetrics(displayState)
    val summaryMetrics = healthSummaryMetrics(displayState)
    val sourceRows = healthSourceRows(displayState)
    val stepSeries = snapshot?.metricTimeSeries?.get(HealthMetricKey.STEPS)
        ?.takeIf { it.points.isNotEmpty() }
    val hasData = metrics.isNotEmpty()
    val issueNotice = healthMetricIssueNotice(displayState)
    val statusText = when {
        state.loading || state.miFitnessSyncing -> "更新中"
        cloudConfigured && state.miFitnessStatus == MiFitnessUiStatus.NO_DATA -> "今天暂无记录"
        cloudFailure && hasData -> "缓存可用 · 刷新失败"
        cloudFailure -> "刷新失败"
        snapshot?.freshness == HealthFreshness.STALE -> "缓存已过期"
        hasData -> "已更新"
        state.availability is HealthAvailability.MissingPermissions -> "待授权"
        else -> "暂无数据"
    }
    val statusTone = when {
        cloudConfigured && state.miFitnessStatus == MiFitnessUiStatus.NO_DATA -> SpectraStatusTone.WARNING
        cloudFailure -> SpectraStatusTone.ERROR
        snapshot?.freshness == HealthFreshness.STALE -> SpectraStatusTone.STALE
        hasData -> SpectraStatusTone.SUCCESS
        state.availability is HealthAvailability.MissingPermissions -> SpectraStatusTone.WARNING
        else -> SpectraStatusTone.INFO
    }
    val summaryMeta = if (hasData) "查看今日健康详情" else "同步后显示今日健康数据"

    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        radius = 24,
        emphasized = true,
        shadowed = true,
        opticalPriority = 5,
        onClick = { showDetails = true },
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FavoriteBorder, null, tint = MaterialTheme.colorScheme.onSurface.copy(.78f))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (cloudConfigured) "Mi Fitness" else "健康数据", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (cloudConfigured) "今日健康" else "Health Connect",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(.52f),
                    )
                }
                SpectraStatus(statusText, tone = statusTone)
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "查看健康数据详情",
                    modifier = Modifier.padding(start = 4.dp).size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(.52f),
                )
            }
            Spacer(Modifier.height(16.dp))
            if (summaryMetrics.isEmpty()) {
                Text(
                    if (hasData) "${metrics.size} 项健康数据已同步" else "还没有可展示的健康记录",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (hasData) "点击查看完整的今日健康详情。"
                    else if (cloudConfigured) "同步后会在这里显示今日健康数据。"
                    else "请检查 Health Connect 授权与已写入的数据来源。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(.56f),
                )
            } else {
                HealthMetricStrip(summaryMetrics)
            }
            Spacer(Modifier.height(13.dp))
            Text(
                summaryMeta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(.56f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (showDetails) {
        SpectraModalBottomSheet(onDismissRequest = { showDetails = false }) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (cloudConfigured) "Mi Fitness" else "Health Connect", style = MaterialTheme.typography.headlineMedium)
                            Text(
                                if (cloudConfigured) "今日健康" else "本机健康记录",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(.56f),
                            )
                        }
                        SpectraStatus(statusText, tone = statusTone)
                    }
                }
                item {
                    HealthSheetSection("今日健康") {
                        if (metrics.isEmpty()) {
                            Text(
                                issueNotice ?: "今天还没有可显示的健康数据。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(.58f),
                            )
                        } else {
                            metrics.forEach { HealthMetricDetailRow(it) }
                            issueNotice?.let { notice ->
                                Text(
                                    notice,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(.56f),
                                )
                            }
                        }
                    }
                }
                if (stepSeries != null) {
                    item {
                        HealthSheetSection("今日步数分时") {
                            StepSeriesList(stepSeries)
                        }
                    }
                }
                item {
                    val needsPermission = state.availability is HealthAvailability.MissingPermissions
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (cloudConfigured) {
                            SpectraAction(
                                text = if (state.miFitnessSyncing) "正在同步" else "同步今日健康",
                                onClick = onCloudRefresh,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.loading && !state.miFitnessSyncing,
                                emphasized = true,
                                mood = PageMood.HEALTH,
                            )
                        } else {
                            SpectraAction(
                                text = if (needsPermission) "授权健康数据" else "刷新数据",
                                onClick = if (needsPermission) onPermissions else onRefresh,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.loading,
                                emphasized = true,
                                mood = PageMood.HEALTH,
                            )
                        }
                    }
                }
                item {
                    HealthSheetSection("更多信息") {
                        TextButton(onClick = { showTechnicalDetails = !showTechnicalDetails }) {
                            Text(if (showTechnicalDetails) "收起数据与同步信息" else "查看数据与同步信息")
                        }
                        AnimatedVisibility(showTechnicalDetails) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                sourceRows.forEach { source ->
                                    HealthDetailRow(source.name, source.channel)
                                }
                                if (cloudConfigured) {
                                    HealthDetailRow("同步范围", "今日健康")
                                    HealthDetailRow("手环连接", "CampusAI 不连接手环")
                                    HealthDetailRow("本机存储", "健康摘要加密保存")
                                    if (cloudFailure) {
                                        HealthDetailRow("最近同步", miFitnessFailureLabel(state.miFitnessStatus), error = true)
                                    }
                                } else {
                                    HealthDetailRow("健康权限", state.permissionLabel())
                                }
                                snapshot?.lastSyncAt?.let { HealthDetailRow("数据更新", compactHealthTime(it)) }
                                snapshot?.freshness?.let { HealthDetailRow("新鲜度", it.compactLabel()) }
                                state.actionMessage?.takeIf(String::isNotBlank)?.let {
                                    HealthDetailRow("最近操作", it)
                                }
                                state.healthError?.takeIf(String::isNotBlank)?.let {
                                    HealthDetailRow("同步状态", "暂时不可用", error = true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class HealthMetricItem(
    val label: String,
    val value: String,
    val unit: String? = null,
    val statusLabel: String? = null,
    val error: Boolean = false,
)

private data class HealthSourceRow(val name: String, val raw: String, val channel: String)

private fun healthSummaryMetrics(state: CaesarHealthUiState): List<HealthMetricItem> {
    val snapshot = state.snapshot
    val candidates = buildList<Pair<Int, HealthMetricItem>> {
        snapshot?.metrics?.steps?.let { add(100 to HealthMetricItem("今日步数", formatHealthLong(it), "步")) }
        snapshot?.metrics?.sleepMinutes?.let { add(96 to compactSleepMetric(it)) }
        snapshot?.metrics?.heartRateAverageBpm?.let { add(92 to HealthMetricItem("平均心率", it.toString(), "bpm")) }
        snapshot?.metrics?.oxygenSaturationAveragePercent?.let {
            add(88 to HealthMetricItem("平均血氧", formatHealthDouble(it), "%"))
        }
        snapshot?.metrics?.activeCaloriesKcal?.let {
            add(84 to HealthMetricItem("活动消耗", formatHealthDouble(it), "千卡"))
        }
        snapshot?.metrics?.activityDurationMinutes?.let {
            add(82 to HealthMetricItem("活动时长", formatSleepMinutes(it)))
        }
        snapshot?.metrics?.restingHeartRateBpm?.let { add(80 to HealthMetricItem("静息心率", it.toString(), "bpm")) }
        snapshot?.metrics?.stressAverage?.let { add(78 to HealthMetricItem("平均压力", it.toString(), "分")) }
        snapshot?.metrics?.workoutCount?.let { add(76 to HealthMetricItem("训练", it.toString(), "次")) }
        snapshot?.metrics?.distanceMeters?.let { add(72 to distanceMetric(it)) }
    }
    return candidates.sortedByDescending { it.first }.take(3).map { it.second }
}

private fun historicalHealthMetrics(state: CaesarHealthUiState): List<HealthMetricItem> {
    val snapshot = state.snapshot ?: return emptyList()
    return buildList {
        HealthMetricKey.entries.forEach { key -> healthMetricItem(snapshot, key)?.let(::add) }
        snapshot.metrics.sleepStageCount?.let {
            add(HealthMetricItem("睡眠阶段记录", it.toString(), "段", statusLabel = "时序"))
        }
    }
}

private fun healthMetricItem(snapshot: HealthSnapshot, key: HealthMetricKey): HealthMetricItem? {
    val typed = snapshot.metricValues[key]
    val fallback = healthMetricFallback(snapshot.metrics, key)
    val status = typed?.status ?: if (fallback == null) HealthMetricStatus.EMPTY else HealthMetricStatus.AVAILABLE
    if (status in setOf(HealthMetricStatus.EMPTY, HealthMetricStatus.ERROR)) return null
    val value = typed?.value ?: fallback ?: return null
    val statusLabel = when (status) {
        HealthMetricStatus.AVAILABLE -> null
        HealthMetricStatus.EMPTY -> "无记录"
        HealthMetricStatus.PARTIAL -> "部分数据"
        HealthMetricStatus.STALE -> "已过期"
        HealthMetricStatus.ERROR -> typed?.reasonCode?.let { "错误 · $it" } ?: "读取错误"
    }
    val formatted = formatRegisteredHealthMetric(key, value)
    return formatted.copy(
        statusLabel = statusLabel,
        error = false,
    )
}

internal fun healthMetricIssueNotice(state: CaesarHealthUiState): String? {
    if (state.miFitnessStatus == MiFitnessUiStatus.NO_DATA && state.snapshot?.metricValues.orEmpty().values.none {
            it.value != null && it.status !in setOf(HealthMetricStatus.EMPTY, HealthMetricStatus.ERROR)
        }
    ) {
        return "今天还没有同步到健康数据。"
    }
    val metricError = state.snapshot?.metricValues.orEmpty().values.any { it.status == HealthMetricStatus.ERROR }
    val seriesError = state.snapshot?.metricTimeSeries.orEmpty().values.any { it.status == HealthMetricStatus.ERROR }
    val refreshError = state.miFitnessStatus in setOf(
        MiFitnessUiStatus.AUTH_ERROR,
        MiFitnessUiStatus.NETWORK_ERROR,
        MiFitnessUiStatus.STORAGE_ERROR,
    )
    return if (metricError || seriesError || refreshError || !state.healthError.isNullOrBlank()) {
        "部分健康数据暂未同步，请稍后重试。"
    } else {
        null
    }
}

private fun healthMetricFallback(metrics: HealthMetrics, key: HealthMetricKey): Double? = when (key) {
    HealthMetricKey.STEPS -> metrics.steps?.toDouble()
    HealthMetricKey.DISTANCE_METERS -> metrics.distanceMeters
    HealthMetricKey.ACTIVE_CALORIES_KCAL -> metrics.activeCaloriesKcal
    HealthMetricKey.ACTIVITY_DURATION_MINUTES -> metrics.activityDurationMinutes?.toDouble()
    HealthMetricKey.VALID_STAND_COUNT -> metrics.validStandCount?.toDouble()
    HealthMetricKey.SLEEP_MINUTES -> metrics.sleepMinutes?.toDouble()
    HealthMetricKey.SLEEP_DEEP_MINUTES -> metrics.sleepDeepMinutes?.toDouble()
    HealthMetricKey.SLEEP_LIGHT_MINUTES -> metrics.sleepLightMinutes?.toDouble()
    HealthMetricKey.SLEEP_REM_MINUTES -> metrics.sleepRemMinutes?.toDouble()
    HealthMetricKey.SLEEP_AWAKE_MINUTES -> metrics.sleepAwakeMinutes?.toDouble()
    HealthMetricKey.SLEEP_SCORE -> metrics.sleepScore?.toDouble()
    HealthMetricKey.HEART_RATE_AVERAGE_BPM -> metrics.heartRateAverageBpm?.toDouble()
    HealthMetricKey.HEART_RATE_MAXIMUM_BPM -> metrics.heartRateMaximumBpm?.toDouble()
    HealthMetricKey.HEART_RATE_MINIMUM_BPM -> metrics.heartRateMinimumBpm?.toDouble()
    HealthMetricKey.RESTING_HEART_RATE_BPM -> metrics.restingHeartRateBpm?.toDouble()
    HealthMetricKey.OXYGEN_SATURATION_AVERAGE_PERCENT -> metrics.oxygenSaturationAveragePercent
    HealthMetricKey.OXYGEN_SATURATION_MAXIMUM_PERCENT -> metrics.oxygenSaturationMaximumPercent
    HealthMetricKey.OXYGEN_SATURATION_MINIMUM_PERCENT -> metrics.oxygenSaturationMinimumPercent
    HealthMetricKey.STRESS_AVERAGE -> metrics.stressAverage?.toDouble()
    HealthMetricKey.STRESS_MAXIMUM -> metrics.stressMaximum?.toDouble()
    HealthMetricKey.STRESS_MINIMUM -> metrics.stressMinimum?.toDouble()
    HealthMetricKey.VO2_MAX_AVERAGE -> metrics.vo2MaxAverage
    HealthMetricKey.VO2_MAX_MAXIMUM -> metrics.vo2MaxMaximum
    HealthMetricKey.VO2_MAX_MINIMUM -> metrics.vo2MaxMinimum
    HealthMetricKey.WORKOUT_COUNT -> metrics.workoutCount?.toDouble()
}

private fun healthMetricLabel(key: HealthMetricKey): String = when (key) {
    HealthMetricKey.STEPS -> "今日步数"
    HealthMetricKey.DISTANCE_METERS -> "活动距离"
    HealthMetricKey.ACTIVE_CALORIES_KCAL -> "活动消耗"
    HealthMetricKey.ACTIVITY_DURATION_MINUTES -> "活动时长"
    HealthMetricKey.VALID_STAND_COUNT -> "有效站立"
    HealthMetricKey.SLEEP_MINUTES -> "睡眠时长"
    HealthMetricKey.SLEEP_DEEP_MINUTES -> "深睡时长"
    HealthMetricKey.SLEEP_LIGHT_MINUTES -> "浅睡时长"
    HealthMetricKey.SLEEP_REM_MINUTES -> "REM 时长"
    HealthMetricKey.SLEEP_AWAKE_MINUTES -> "清醒时长"
    HealthMetricKey.SLEEP_SCORE -> "睡眠评分"
    HealthMetricKey.HEART_RATE_AVERAGE_BPM -> "平均心率"
    HealthMetricKey.HEART_RATE_MAXIMUM_BPM -> "最高心率"
    HealthMetricKey.HEART_RATE_MINIMUM_BPM -> "最低心率"
    HealthMetricKey.RESTING_HEART_RATE_BPM -> "静息心率"
    HealthMetricKey.OXYGEN_SATURATION_AVERAGE_PERCENT -> "平均血氧"
    HealthMetricKey.OXYGEN_SATURATION_MAXIMUM_PERCENT -> "最高血氧"
    HealthMetricKey.OXYGEN_SATURATION_MINIMUM_PERCENT -> "最低血氧"
    HealthMetricKey.STRESS_AVERAGE -> "平均压力"
    HealthMetricKey.STRESS_MAXIMUM -> "最高压力"
    HealthMetricKey.STRESS_MINIMUM -> "最低压力"
    HealthMetricKey.VO2_MAX_AVERAGE -> "平均最大摄氧量"
    HealthMetricKey.VO2_MAX_MAXIMUM -> "最高最大摄氧量"
    HealthMetricKey.VO2_MAX_MINIMUM -> "最低最大摄氧量"
    HealthMetricKey.WORKOUT_COUNT -> "训练记录"
}

private fun formatRegisteredHealthMetric(key: HealthMetricKey, value: Double): HealthMetricItem = when (key) {
    HealthMetricKey.DISTANCE_METERS -> distanceMetric(value)
    HealthMetricKey.SLEEP_MINUTES,
    HealthMetricKey.SLEEP_DEEP_MINUTES,
    HealthMetricKey.SLEEP_LIGHT_MINUTES,
    HealthMetricKey.SLEEP_REM_MINUTES,
    HealthMetricKey.SLEEP_AWAKE_MINUTES,
    HealthMetricKey.ACTIVITY_DURATION_MINUTES -> HealthMetricItem(healthMetricLabel(key), formatSleepMinutes(value.toLong()))
    HealthMetricKey.ACTIVE_CALORIES_KCAL -> HealthMetricItem(healthMetricLabel(key), formatHealthDouble(value), "千卡")
    HealthMetricKey.HEART_RATE_AVERAGE_BPM,
    HealthMetricKey.HEART_RATE_MAXIMUM_BPM,
    HealthMetricKey.HEART_RATE_MINIMUM_BPM,
    HealthMetricKey.RESTING_HEART_RATE_BPM -> HealthMetricItem(healthMetricLabel(key), formatHealthDouble(value), "bpm")
    HealthMetricKey.OXYGEN_SATURATION_AVERAGE_PERCENT,
    HealthMetricKey.OXYGEN_SATURATION_MAXIMUM_PERCENT,
    HealthMetricKey.OXYGEN_SATURATION_MINIMUM_PERCENT -> HealthMetricItem(healthMetricLabel(key), formatHealthDouble(value), "%")
    HealthMetricKey.STRESS_AVERAGE,
    HealthMetricKey.STRESS_MAXIMUM,
    HealthMetricKey.STRESS_MINIMUM,
    HealthMetricKey.SLEEP_SCORE -> HealthMetricItem(healthMetricLabel(key), formatHealthDouble(value), "分")
    HealthMetricKey.VO2_MAX_AVERAGE,
    HealthMetricKey.VO2_MAX_MAXIMUM,
    HealthMetricKey.VO2_MAX_MINIMUM -> HealthMetricItem(healthMetricLabel(key), formatHealthDouble(value), "ml/kg/min")
    HealthMetricKey.STEPS -> HealthMetricItem(healthMetricLabel(key), formatHealthLong(value.toLong()), "步")
    HealthMetricKey.VALID_STAND_COUNT -> HealthMetricItem(healthMetricLabel(key), value.toLong().toString(), "次")
    HealthMetricKey.WORKOUT_COUNT -> HealthMetricItem(healthMetricLabel(key), value.toLong().toString(), "次")
}

internal fun displayedDailySteps(state: CaesarHealthUiState): Long? = state.snapshot?.metrics?.steps

internal fun displayedDailySleepMinutes(state: CaesarHealthUiState): Long? = state.snapshot?.metrics?.sleepMinutes

internal fun displayedHealthMetricLabels(state: CaesarHealthUiState): List<String> =
    historicalHealthMetrics(state).map(HealthMetricItem::label)

internal fun displayedStepSeries(state: CaesarHealthUiState): HealthMetricTimeSeries? =
    state.snapshot?.metricTimeSeries?.get(HealthMetricKey.STEPS)?.takeIf { it.points.isNotEmpty() }

private fun healthSourceRows(state: CaesarHealthUiState): List<HealthSourceRow> =
    state.snapshot?.originPackages.orEmpty().sorted().map { raw ->
        HealthSourceRow(
            name = healthSourceName(raw),
            raw = raw,
            channel = if (raw == MiFitnessSummaryHealthGateway.SOURCE_ID) "已同步" else "Health Connect",
        )
    }.distinctBy { "${it.channel}:${it.raw}" }

@Composable
private fun HealthMetricStrip(metrics: List<HealthMetricItem>) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        metrics.forEachIndexed { index, metric ->
            Column(Modifier.weight(1f)) {
                Text(
                    metric.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(.54f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(metric.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    metric.unit?.takeIf(String::isNotBlank)?.let {
                        Spacer(Modifier.width(3.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.58f))
                    }
                }
            }
            if (index < metrics.lastIndex) {
                Box(
                    Modifier.padding(horizontal = 10.dp).width(1.dp).height(38.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(.10f)),
                )
            }
        }
    }
}

@Composable
private fun StepSeriesList(series: HealthMetricTimeSeries) {
    if (series.status == HealthMetricStatus.PARTIAL || series.status == HealthMetricStatus.STALE) {
        Text(
            if (series.status == HealthMetricStatus.PARTIAL) "部分分时记录" else "来自上次同步",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(.56f),
        )
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(series.points, key = { it.epochMillis }) { point ->
            GlassPanel(
                modifier = Modifier.width(92.dp).height(68.dp),
                radius = 16,
                shadowed = false,
                optical = false,
            ) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        compactHealthClock(point.epochMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(.54f),
                    )
                    Text(
                        "${formatHealthLong(point.value.toLong())} 步",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun HealthSheetSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun HealthMetricDetailRow(metric: HealthMetricItem, badge: String? = metric.statusLabel) {
    HealthDetailRow(
        label = buildString {
            append(metric.label)
            badge?.let { append("  ·  $it") }
        },
        value = buildString {
            append(metric.value)
            metric.unit?.takeIf(String::isNotBlank)?.let { append(" $it") }
        },
        error = metric.error,
    )
}

@Composable
private fun HealthDetailRow(label: String, value: String, error: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.58f), modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1.25f),
        )
    }
}

private fun CaesarHealthUiState.permissionLabel(): String = when (availability) {
    HealthAvailability.Available -> "已授权 $grantedPermissionCount / $requiredPermissionCount 项"
    is HealthAvailability.MissingPermissions -> "缺少 ${availability.permissions.size} 项权限"
    HealthAvailability.NeedsProvider -> "需要 Health Connect"
    HealthAvailability.Unsupported -> "当前系统不支持"
    null -> "尚未检查"
}

private fun healthSourceName(raw: String): String = when {
    raw == "mi_fitness_cloud_cn" -> "Mi Fitness"
    raw == "com.mi.health" -> "Mi Fitness"
    else -> raw.substringAfterLast('.').ifBlank { raw }
}

private fun miFitnessFailureLabel(status: MiFitnessUiStatus): String = when (status) {
    MiFitnessUiStatus.NO_DATA -> "今天还没有同步到健康数据。"
    MiFitnessUiStatus.AUTH_ERROR -> "身份验证失败，请在个人页更新凭据。"
    MiFitnessUiStatus.NETWORK_ERROR -> "网络异常，请稍后重试。"
    MiFitnessUiStatus.STORAGE_ERROR -> "系统安全存储暂不可用。"
    else -> "本次刷新未完成。"
}

private fun formatHealthLong(value: Long): String = String.format(Locale.CHINA, "%,d", value)

private fun formatHealthDouble(value: Double): String {
    val rounded = value.roundToInt()
    return if (value == rounded.toDouble()) rounded.toString() else String.format(Locale.CHINA, "%.1f", value)
}

private fun distanceMetric(meters: Double): HealthMetricItem = if (meters >= 1_000.0) {
    HealthMetricItem("距离", formatHealthDouble(meters / 1_000.0), "km")
} else {
    HealthMetricItem("距离", meters.roundToInt().toString(), "m")
}

private fun formatSleepMinutes(minutes: Long): String {
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours == 0L -> "$minutes 分钟"
        remainder == 0L -> "$hours 小时"
        else -> "${hours}时${remainder}分"
    }
}

private fun compactSleepMetric(minutes: Long): HealthMetricItem = if (minutes < 60L) {
    HealthMetricItem("睡眠", minutes.toString(), "分钟")
} else {
    HealthMetricItem("睡眠", formatHealthDouble(minutes / 60.0), "小时")
}

private fun HealthFreshness.compactLabel(): String = when (this) {
    HealthFreshness.LIVE -> "实时"
    HealthFreshness.FRESH -> "新鲜"
    HealthFreshness.STALE -> "已过期"
    HealthFreshness.UNKNOWN -> "新鲜度未知"
}

private fun compactHealthTime(value: Long): String = runCatching {
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(value))
}.getOrDefault("未知")

private fun compactHealthClock(value: Long): String = runCatching {
    SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(value))
}.getOrDefault("未知")

@Composable
private fun SpectraProgress(totalMinutes: Long, goalMinutes: Long) {
    val progress = (totalMinutes / goalMinutes.toFloat()).coerceIn(0f, 1f)
    val motion = SpectraTheme.tokens.motion
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(motion.resolve(motion.longMillis)),
        label = "home-goal-progress",
    )
    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.Start) {
        Text(
            formatDuration(totalMinutes),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(5.dp))
        Text("今日累计", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.58f))
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(.10f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animatedProgress.coerceAtLeast(.012f))
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.onSurface.copy(.82f)),
            )
        }
    }
}

@Composable
private fun SectionLabel(title: String, meta: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.55f))
    }
}

@Composable
fun TimeScreen(
    records: List<TimeRecord>,
    viewModel: TimeViewModel,
    onStartFocus: (Int) -> Unit,
    onMessage: suspend (String, String?) -> SnackbarResult,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val layout = SpectraTheme.layout
    val scope = rememberCoroutineScope()
    val courses by viewModel.courses.collectAsState()
    var range by rememberSaveable { mutableStateOf("日") }
    var focusPreset by rememberSaveable { mutableIntStateOf(50) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TimeRecord?>(null) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var importDrafts by remember { mutableStateOf<List<CourseDraft>?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            importing = true; importError = null
            runCatching { ScheduleImporter.fromImage(context, uri) }
                .onSuccess { if (it.isEmpty()) importError = "没有识别到可靠的课程格。请换一张清晰、完整的课程表截图，或使用日历文件。" else importDrafts = it }
                .onFailure { importError = "截图识别失败：${it.message ?: "图片无法读取"}。你可以改用日历文件或手动添加。" }
            importing = false
        }
    }
    val icsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            importing = true; importError = null
            runCatching { withContext(Dispatchers.IO) { ScheduleImporter.fromIcs(context, uri) } }
                .onSuccess { if (it.isEmpty()) importError = "这个日历文件里没有可导入的课程事件。" else importDrafts = it }
                .onFailure { importError = "日历文件解析失败：${it.message ?: "格式不受支持"}。" }
            importing = false
        }
    }
    val filtered = when (range) {
        "周" -> records.filter { it.startTime >= System.currentTimeMillis() - 7 * 86_400_000L }
        "月" -> records.filter { it.startTime >= System.currentTimeMillis() - 31 * 86_400_000L }
        else -> records.filter { it.startTime >= startOfToday() }
    }
    var deleted by remember { mutableStateOf<TimeRecord?>(null) }
    LaunchedEffect(deleted) {
        val record = deleted ?: return@LaunchedEffect
        if (onMessage("已删除“${record.title}”", "撤销") == SnackbarResult.ActionPerformed) {
            viewModel.undoDeleteTimeRecord(record.id)
        } else viewModel.confirmDeleteTimeRecord()
        deleted = null
    }

    SpectraPageScaffold(mood = PageMood.FOCUS) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = layout.pageHorizontalPadding,
                    end = layout.pageHorizontalPadding,
                    top = contentPadding.calculateTopPadding() + layout.pageTopSpacing,
                    bottom = maxOf(contentPadding.calculateBottomPadding(), layout.pageBottomSpacing),
                ),
                verticalArrangement = Arrangement.spacedBy(layout.sectionGap),
            ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("时间", style = MaterialTheme.typography.headlineLarge); Text("今天的轨迹，清楚而不嘈杂", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.6f)) }
                    SpectraIconAction(
                        icon = Icons.Rounded.FileOpen,
                        label = "导入课程表",
                        onClick = { showImport = true },
                    )
                }
            }
            item {
                SpectraSurface(
                    modifier = Modifier.fillMaxWidth(),
                    mood = PageMood.FOCUS,
                    emphasized = true,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("专注预设", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(12.dp))
                        com.campusai.core.designsystem.CaesarSlidingSelector(
                            options = listOf("25 分钟", "50 分钟", "90 分钟"),
                            selectedIndex = listOf(25, 50, 90).indexOf(focusPreset).coerceAtLeast(0),
                            onSelected = { focusPreset = listOf(25, 50, 90)[it] },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        SpectraPrimaryButton(
                            text = "进入 $focusPreset 分钟专注",
                            onClick = { onStartFocus(focusPreset) },
                            modifier = Modifier.fillMaxWidth(),
                            icon = Icons.Rounded.Timer,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("时长可以点击，也可直接拖动选中胶囊；完成后自动写入时间轴。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.62f))
                    }
                }
            }
            if (courses.isNotEmpty()) {
                item {
                    val today = ((java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7) + 1
                    val todayCourses = courses.filter { it.weekday == today }
                    SpectraSurface(
                        modifier = Modifier.fillMaxWidth(),
                        mood = PageMood.FOCUS,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("今日课程", style = MaterialTheme.typography.titleLarge); Text("${todayCourses.size} 节", style = MaterialTheme.typography.labelMedium) }
                            Spacer(Modifier.height(8.dp))
                            if (todayCourses.isEmpty()) Text("今天没有已导入的课程。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.58f))
                            todayCourses.forEach { course ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("%02d:%02d".format(course.startMinute/60,course.startMinute%60), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(58.dp))
                                    Column { Text(course.name, style = MaterialTheme.typography.titleMedium); if(course.location.isNotBlank()) Text(course.location, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.56f)) }
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    SpectraStatePane(
                        kind = SpectraStateKind.EMPTY,
                        title = "尚未导入课程表",
                        detail = "可以读取课程表截图或 .ics 日历，保存前会先让你确认。",
                        modifier = Modifier.fillMaxWidth(),
                        actionLabel = "导入课程表",
                        onAction = { showImport = true },
                    )
                }
            }
            item {
                com.campusai.core.designsystem.CaesarSlidingSelector(
                    options = listOf("日", "周", "月"),
                    selectedIndex = listOf("日", "周", "月").indexOf(range).coerceAtLeast(0),
                    onSelected = { range = listOf("日", "周", "月")[it] },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${range}时间轴", style = MaterialTheme.typography.titleLarge)
                    Text(formatDuration(filtered.sumOf { it.durationMinutes }), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (filtered.isEmpty()) {
                item {
                    SpectraStatePane(
                        kind = SpectraStateKind.EMPTY,
                        title = "${range}时间轴还很安静",
                        detail = "当前区间没有记录；补录后会立即计入统计。",
                        modifier = Modifier.fillMaxWidth(),
                        actionLabel = "补录时间",
                        onAction = { showAdd = true },
                    )
                }
            } else {
                item {
                    SpectraSurface(
                        modifier = Modifier.fillMaxWidth(),
                        mood = PageMood.FOCUS,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Column {
                            filtered.forEachIndexed { index, record ->
                                TimelineRow(record, onEdit = { editing = record }, onDelete = { viewModel.deleteTimeRecord(record.id); deleted = record })
                                if (index < filtered.lastIndex) HorizontalDivider(Modifier.padding(start = 62.dp), color = SpectraColors.Silver.copy(.65f))
                            }
                        }
                    }
                }
            }
        }
            FloatingActionButton(
                onClick = { showAdd = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = contentPadding.calculateBottomPadding() + 22.dp),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(Icons.Rounded.Add, "新增记录") }
        }
    }
    if (showAdd) AddTimeRecordDialog(initial = null, onDismiss = { showAdd = false }, onSave = { title, category, minutes, note ->
        val end = System.currentTimeMillis(); viewModel.addTimeRecord(title, category, end - minutes * 60_000L, end, note); showAdd = false
    })
    editing?.let { record -> AddTimeRecordDialog(initial = record, onDismiss = { editing = null }, onSave = { title, category, minutes, note ->
        val end = record.endTime
        viewModel.editTimeRecord(record.id, title, category, end - minutes * 60_000L, end, note)
        editing = null
    }) }
    if (showImport) ImportScheduleSourceDialog(
        onDismiss = { showImport = false },
        onImage = { showImport = false; imagePicker.launch("image/*") },
        onIcs = { showImport = false; icsPicker.launch(arrayOf("text/calendar", "application/ics", "application/octet-stream")) },
        onManual = { showImport = false; importDrafts = listOf(CourseDraft("新课程", 1, 8*60, 9*60+40)) },
    )
    if (importing) SpectraDialog(onDismissRequest = {}) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("正在读取课程表", style = MaterialTheme.typography.titleLarge)
            Text("识别在本机完成。完成后会先让你确认，不会直接覆盖现有课程。")
        }
    }
    importError?.let { message ->
        SpectraDialog(onDismissRequest = { importError = null }) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("暂时没能导入", style = MaterialTheme.typography.titleLarge)
                Text(message)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { importError = null }) { Text("知道了") }
                }
            }
        }
    }
    importDrafts?.let { drafts -> SchedulePreviewDialog(
        initial = drafts,
        onDismiss = { importDrafts = null },
        onConfirm = { edited ->
            viewModel.importCourses(edited.map { it.toCourse() }) { inserted, duplicates -> scope.launch { onMessage("已导入 $inserted 门课程${if (duplicates>0) "，跳过 $duplicates 条重复" else ""}", null) } }
            importDrafts = null
        },
    ) }
}

@Composable
private fun ImportScheduleSourceDialog(onDismiss:()->Unit,onImage:()->Unit,onIcs:()->Unit,onManual:()->Unit) {
    SpectraDialog(onDismissRequest=onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("导入课程表", style = MaterialTheme.typography.titleLarge)
            Text("最省事的方式是截取一张完整课程表。识别结果会先进入可编辑预览。", style=MaterialTheme.typography.bodyMedium)
            SpectraPrimaryButton("选择课程表截图",onImage,Modifier.fillMaxWidth(),icon=Icons.Rounded.ImageSearch)
            TextButton(onClick=onIcs,Modifier.fillMaxWidth()){Text("从 .ics 日历文件导入")}
            TextButton(onClick=onManual,Modifier.fillMaxWidth()){Text("手动添加课程")}
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick=onDismiss){Text("取消")}
            }
        }
    }
}

@Composable
private fun SchedulePreviewDialog(initial:List<CourseDraft>,onDismiss:()->Unit,onConfirm:(List<CourseDraft>)->Unit) {
    var drafts by remember(initial){mutableStateOf(initial)}
    SpectraDialog(onDismissRequest=onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("确认课程（${drafts.size}）", style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.fillMaxWidth().height(420.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item{Text("识别可能会把教室当作课程名。请在保存前快速检查；重复课程会自动跳过。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurface.copy(.62f))}
            items(drafts.size){index-> val item=drafts[index]; var startText by remember(index,item.startMinute){mutableStateOf(formatClock(item.startMinute))}; var endText by remember(index,item.endMinute){mutableStateOf(formatClock(item.endMinute))}; GlassPanel(Modifier.fillMaxWidth(),radius=16){Column(Modifier.padding(12.dp)){
                SpectraTextField(item.name,{value->drafts=drafts.toMutableList().also{it[index]=item.copy(name=value)}},label={Text("课程名")},singleLine=true,shape=RoundedCornerShape(12.dp))
                Spacer(Modifier.height(8.dp))
                SpectraTextField(item.location,{value->drafts=drafts.toMutableList().also{it[index]=item.copy(location=value)}},label={Text("教室（可选）")},singleLine=true,shape=RoundedCornerShape(12.dp))
                Spacer(Modifier.height(8.dp))
                com.campusai.core.designsystem.CaesarSlidingSelector(
                    options = (1..7).map { day -> "周${"一二三四五六日"[day - 1]}" },
                    selectedIndex = (item.weekday - 1).coerceIn(0, 6),
                    onSelected = { selectedDay ->
                        drafts = drafts.toMutableList().also { it[index] = item.copy(weekday = selectedDay + 1) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    SpectraTextField(startText,{value->startText=value;parseClockOrNull(value)?.let{minute->drafts=drafts.toMutableList().also{it[index]=item.copy(startMinute=minute)}}},label={Text("开始 HH:mm")},singleLine=true,shape=RoundedCornerShape(12.dp),modifier=Modifier.weight(1f),isError=parseClockOrNull(startText)==null)
                    SpectraTextField(endText,{value->endText=value;parseClockOrNull(value)?.let{minute->drafts=drafts.toMutableList().also{it[index]=item.copy(endMinute=minute)}}},label={Text("结束 HH:mm")},singleLine=true,shape=RoundedCornerShape(12.dp),modifier=Modifier.weight(1f),isError=parseClockOrNull(endText)==null)
                }
                if(item.endMinute<=item.startMinute) Text("结束时间必须晚于开始时间。",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)
                TextButton(onClick={drafts=drafts.filterIndexed{i,_->i!=index}}){Text("移除这条")}
            }}}
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick=onDismiss){Text("取消")}
                TextButton(enabled=drafts.any{it.name.isNotBlank()}&&drafts.all{it.endMinute>it.startMinute},onClick={onConfirm(drafts.filter{it.name.isNotBlank()})}){Text("确认导入")}
            }
        }
    }
}

@Composable
private fun TimelineRow(record: TimeRecord, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.onSurface.copy(.08f), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Bolt, null, tint = MaterialTheme.colorScheme.onSurface.copy(.72f), modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(record.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${record.category} · ${SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(record.startTime))}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.58f))
        }
        Text(formatDuration(record.durationMinutes), style = MaterialTheme.typography.labelMedium)
        IconButton(onClick = onEdit) { Icon(Icons.Rounded.EditNote, "编辑", tint = MaterialTheme.colorScheme.onSurface.copy(.55f)) }
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "删除", tint = MaterialTheme.colorScheme.onSurface.copy(.55f)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTimeRecordDialog(initial: TimeRecord?, onDismiss: () -> Unit, onSave: (String, String, Long, String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    var title by rememberSaveable(initial?.id) { mutableStateOf(initial?.title.orEmpty()) }
    var category by rememberSaveable(initial?.id) { mutableStateOf(initial?.category ?: "学习") }
    var note by rememberSaveable(initial?.id) { mutableStateOf(initial?.remark.orEmpty()) }
    var minutes by rememberSaveable(initial?.id) { mutableIntStateOf(initial?.durationMinutes?.toInt()?.coerceIn(5, 240) ?: 50) }
    SpectraDialog(onDismissRequest = onDismiss) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (initial == null) "补录时间" else "编辑记录", style = MaterialTheme.typography.headlineMedium)
                SpectraTextField(title, { title = it }, label = { Text("做了什么") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                SpectraTextField(category, { category = it }, label = { Text("分类") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                Text("$minutes 分钟", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = minutes.toFloat(),
                    onValueChange = { value ->
                        val next = ((value / 5f).roundToInt() * 5).coerceIn(5, 240)
                        if (next != minutes) {
                            minutes = next
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                    valueRange = 5f..240f,
                    steps = 46,
                )
                SpectraTextField(note, { note = it }, label = { Text("描述（可选）") }, shape = RoundedCornerShape(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(enabled = title.isNotBlank(), onClick = { onSave(title.trim(), category.trim().ifEmpty { "其他" }, minutes.toLong(), note.trim()) }) { Text("保存") }
                }
            }
    }
}

@Composable
fun FocusSessionScreen(
    presetMinutes: Int,
    motionEnabled: Boolean,
    soundEnabled: Boolean,
    onMinimize: () -> Unit,
    onFinish: (Int) -> Unit,
) {
    val context = LocalContext.current
    val focusLayout = SpectraTheme.layout
    val focusTokens = SpectraTheme.tokens
    val fluid = SpectraTheme.isFluid
    var remaining by rememberSaveable(presetMinutes) { mutableLongStateOf(presetMinutes * 60L) }
    var running by rememberSaveable { mutableStateOf(true) }
    var completed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(running, completed) {
        while (running && remaining > 0) { delay(1_000); remaining-- }
        if (remaining == 0L && !completed) {
            completed = true; running = false
            if (soundEnabled) ToneGenerator(AudioManager.STREAM_NOTIFICATION, 32).apply { startTone(ToneGenerator.TONE_PROP_ACK, 900); delay(950); release() }
            val vibrator = context.getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(90)
            }
        }
    }
    val totalSeconds = presetMinutes * 60f
    val progress = (remaining / totalSeconds).coerceIn(0f, 1f)
    val elapsedMinutes = ((presetMinutes * 60L - remaining).coerceAtLeast(0L) / 60L).toInt()
    SpectraPageScaffold(mood = PageMood.FOCUS) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(
                        horizontal = focusLayout.pageHorizontalPadding,
                        vertical = focusLayout.pageTopSpacing,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("专注会话", color = MaterialTheme.colorScheme.onSurface.copy(.56f), style = MaterialTheme.typography.labelMedium)
                        Text("无界专注", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineMedium)
                    }
                    GlassPanel(Modifier.size(48.dp), radius = 24, emphasized = true, shadowed = false, onClick = onMinimize, opticalPriority = 8) {
                        Icon(Icons.Rounded.KeyboardArrowDown, "最小化", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.align(Alignment.Center))
                    }
                }
                Spacer(Modifier.weight(.55f))
                GlassPanel(
                    Modifier.fillMaxWidth().height(if (fluid) 320.dp else 288.dp),
                    radius = if (fluid) focusTokens.radii.hero.value.roundToInt() else 48,
                    emphasized = true,
                    shadowed = !fluid,
                    opticalPriority = 10,
                ) {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 30.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "%02d:%02d".format(remaining / 60, remaining % 60),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 62.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            when {
                                completed -> "这一段时间已完成"
                                running -> "只做眼前这一件事"
                                else -> "计时已暂停"
                            },
                            color = MaterialTheme.colorScheme.onSurface.copy(.60f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(34.dp))
                        Box(
                            Modifier.fillMaxWidth().height(7.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface.copy(.10f)),
                        ) {
                            Box(
                                Modifier.fillMaxWidth(progress.coerceAtLeast(.01f)).fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.onSurface.copy(.82f)),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("已专注 $elapsedMinutes 分钟", color = MaterialTheme.colorScheme.onSurface.copy(.56f), style = MaterialTheme.typography.bodySmall)
                            Text("目标 $presetMinutes 分钟", color = MaterialTheme.colorScheme.onSurface.copy(.56f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                GlassPanel(
                    Modifier.fillMaxWidth(),
                    radius = if (fluid) focusTokens.radii.card.value.roundToInt() else 28,
                    emphasized = true,
                    shadowed = false,
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(focusTokens.spacing.md),
                        verticalArrangement = Arrangement.spacedBy(focusTokens.spacing.sm),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            SpectraStatus(
                                when {
                                    completed -> "已完成"
                                    running -> "正在计时"
                                    else -> "已暂停"
                                },
                                tone = if (completed) SpectraStatusTone.SUCCESS else SpectraStatusTone.INFO,
                            )
                            Text(if (motionEnabled) "流体场域" else "静默场域", color = MaterialTheme.colorScheme.onSurface.copy(.52f), style = MaterialTheme.typography.bodySmall)
                        }
                        if (completed) {
                            SpectraPrimaryButton("完成并写入时间轴", { onFinish(presetMinutes) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.CheckCircle)
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FocusGlassAction(
                                    text = if (running) "暂停" else "继续",
                                    icon = if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    onClick = { running = !running },
                                    modifier = Modifier.weight(1f),
                                    emphasized = true,
                                )
                                FocusGlassAction(
                                    text = "结束并记录",
                                    icon = Icons.Rounded.Stop,
                                    onClick = { onFinish(max(1, presetMinutes - (remaining / 60).toInt())) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusGlassAction(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    GlassPanel(modifier.height(54.dp), radius = 27, emphasized = emphasized, shadowed = false, onClick = onClick, optical = false) {
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(19.dp))
            Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

private fun formatDuration(minutes: Long): String = when {
    minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
    else -> "${minutes}m"
}

private fun formatClock(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

private fun parseClockOrNull(value: String): Int? {
    val match = Regex("^(\\d{1,2}):([0-5]\\d)$").matchEntire(value.trim()) ?: return null
    val hour = match.groupValues[1].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    return hour * 60 + match.groupValues[2].toInt()
}

private fun startOfToday(): Long = java.util.Calendar.getInstance().apply {
    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

private fun timeGreeting(): String = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "早上好"
    in 12..17 -> "下午好"
    else -> "晚上好"
}

private fun calculateStreak(records: List<TimeRecord>): Int {
    if (records.isEmpty()) return 0
    val days = records.map { java.util.Calendar.getInstance().apply { timeInMillis = it.startTime; set(java.util.Calendar.HOUR_OF_DAY,0); set(java.util.Calendar.MINUTE,0); set(java.util.Calendar.SECOND,0); set(java.util.Calendar.MILLISECOND,0) }.timeInMillis }.toSet()
    var cursor = startOfToday(); if (cursor !in days) cursor -= 86_400_000L
    var streak = 0; while (cursor in days) { streak++; cursor -= 86_400_000L }
    return streak
}
