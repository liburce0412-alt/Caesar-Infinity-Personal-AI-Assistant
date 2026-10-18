package com.campusai.features.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.campusai.core.designsystem.PageMood
import com.campusai.core.designsystem.SpectraAction
import com.campusai.core.designsystem.SpectraColors
import com.campusai.core.designsystem.SpectraStatus
import com.campusai.core.designsystem.SpectraStatusTone
import com.campusai.core.designsystem.SpectraSurface
import com.campusai.core.health.HealthAvailability
import com.campusai.core.health.HealthFreshness
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
internal fun RefreshHealthStatusWhenVisible(visible: Boolean, onRefresh: () -> Unit) {
    val today by com.campusai.core.time.rememberCaesarDate()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val refresh by rememberUpdatedState(onRefresh)
    LaunchedEffect(visible, today, lifecycle) {
        if (visible) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            refresh()
            kotlinx.coroutines.awaitCancellation()
        }
    }
}

@Composable
internal fun HealthStatus(
    state: CaesarHealthUiState,
    onRefresh: () -> Unit,
    onCloudRefresh: () -> Unit,
    onPermissions: () -> Unit,
) {
    if (state.miFitnessConfigured) {
        MiFitnessCloudStatus(state, onCloudRefresh)
        return
    }
    val today by com.campusai.core.time.rememberCaesarDate()
    val current = remember(state, today) { state.forCurrentHealthDay() }
    val hasVerifiedHealthData = current.snapshot?.metrics?.let { metrics ->
        listOf(
            metrics.steps,
            metrics.distanceMeters,
            metrics.activeCaloriesKcal,
            metrics.heartRateAverageBpm,
            metrics.heartRateMaximumBpm,
            metrics.restingHeartRateBpm,
            metrics.oxygenSaturationAveragePercent,
            metrics.sleepMinutes,
            metrics.sleepStageCount,
            metrics.workoutCount,
        ).any { it != null }
    } == true
    val healthLabel = when (state.availability) {
        HealthAvailability.Available -> if (hasVerifiedHealthData) "已授权 · 数据已读取" else "已授权 · 尚无数据"
        is HealthAvailability.MissingPermissions -> if (hasVerifiedHealthData) "部分授权 · 数据已读取" else "部分授权 · 尚无数据"
        HealthAvailability.NeedsProvider -> "Health Connect 需更新"
        HealthAvailability.Unsupported -> "当前系统不支持"
        null -> if (state.loading) "正在检查 Health Connect" else "Health Connect 未检查"
    }
    val healthTone = when (state.availability) {
        HealthAvailability.Available -> SpectraStatusTone.SUCCESS
        is HealthAvailability.MissingPermissions -> SpectraStatusTone.WARNING
        HealthAvailability.NeedsProvider, HealthAvailability.Unsupported -> SpectraStatusTone.ERROR
        null -> SpectraStatusTone.INFO
    }
    SpectraSurface(
        modifier = Modifier.fillMaxWidth(),
        mood = PageMood.HEALTH,
        emphasized = true,
        shadowed = false,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Health Connect", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                SpectraStatus(healthLabel, tone = healthTone)
            }
            Text(
                "权限 ${state.grantedPermissionCount}/${state.requiredPermissionCount}" +
                    (current.snapshot?.lastSyncAt?.let { " · 最后同步 ${healthStatusTime(it)}" } ?: " · 暂无可验证同步时间"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(.66f),
            )
            current.snapshot?.let { snapshot ->
                val summary = buildList {
                    snapshot.metrics.steps?.let { add("今日 $it 步") }
                    snapshot.metrics.heartRateAverageBpm?.let { add("平均心率 $it bpm") }
                    snapshot.metrics.sleepMinutes?.let { add("睡眠 $it 分钟") }
                }
                if (summary.isNotEmpty()) Text(summary.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "来源：${snapshot.originPackages.takeIf { it.isNotEmpty() }?.joinToString() ?: "尚未观测到数据源"} · ${healthFreshnessLabel(snapshot.freshness)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(.56f),
                )
            }
            state.healthError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpectraAction("刷新", onRefresh, Modifier.weight(1f), enabled = !state.loading, mood = PageMood.HEALTH)
                SpectraAction("授权", onPermissions, Modifier.weight(1f), mood = PageMood.HEALTH)
            }
            Text(
                "Health Connect 只读取用户已授权的历史健康记录；缺失指标保持为未知，不会回退显示为 0。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(.52f),
            )
        }
    }
}

@Composable
internal fun MiFitnessCloudStatus(
    state: CaesarHealthUiState,
    onRefresh: () -> Unit,
) {
    val today by com.campusai.core.time.rememberCaesarDate()
    val current = remember(state, today) { state.forCurrentHealthDay() }
    val snapshot = current.snapshot
    val cloudSnapshot = snapshot?.takeIf {
        com.campusai.core.health.mifitness.MiFitnessSummaryHealthGateway.SOURCE_ID in it.originPackages
    }
    val hasShareableMetrics = currentDayHealthSummary(cloudSnapshot)?.hasShareableMetrics == true
    val failed = state.miFitnessStatus in setOf(
        MiFitnessUiStatus.NO_DATA,
        MiFitnessUiStatus.AUTH_ERROR,
        MiFitnessUiStatus.NETWORK_ERROR,
        MiFitnessUiStatus.STORAGE_ERROR,
    )
    val cloudMetricSummary = buildList {
        if (!hasShareableMetrics) return@buildList
        cloudSnapshot?.metrics?.steps?.let { add("步数 $it 步") }
        cloudSnapshot?.metrics?.distanceMeters?.let { add("距离 ${it.roundToInt()} 米") }
        cloudSnapshot?.metrics?.activeCaloriesKcal?.let { add("消耗 ${it.roundToInt()} 千卡") }
        cloudSnapshot?.metrics?.activityDurationMinutes?.let { add("活动 $it 分钟") }
        cloudSnapshot?.metrics?.sleepMinutes?.let { add("睡眠 $it 分钟") }
        cloudSnapshot?.metrics?.heartRateAverageBpm?.let { add("平均心率 $it bpm") }
        cloudSnapshot?.metrics?.oxygenSaturationAveragePercent?.let { add("平均血氧 ${it.roundToInt()}%") }
        cloudSnapshot?.metrics?.stressAverage?.let { add("平均压力 $it") }
        cloudSnapshot?.metrics?.workoutCount?.let { add("训练 $it 次") }
    }
    SpectraSurface(
        modifier = Modifier.fillMaxWidth(),
        mood = PageMood.HEALTH,
        emphasized = true,
        shadowed = false,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mi Fitness", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "今日健康",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(.52f),
                    )
                }
                SpectraStatus(
                    when {
                        state.miFitnessSyncing -> "正在同步"
                        state.miFitnessStatus == MiFitnessUiStatus.NO_DATA -> "今天暂无记录"
                        failed && cloudSnapshot != null -> "缓存可用 · 刷新失败"
                        failed -> "刷新失败"
                        cloudSnapshot != null && !hasShareableMetrics -> "同步完成 · 暂无指标"
                        cloudSnapshot != null -> "已更新"
                        else -> "待同步"
                    },
                    tone = when {
                        state.miFitnessStatus == MiFitnessUiStatus.NO_DATA -> SpectraStatusTone.WARNING
                        failed -> SpectraStatusTone.ERROR
                        cloudSnapshot != null && !hasShareableMetrics -> SpectraStatusTone.INFO
                        cloudSnapshot != null -> SpectraStatusTone.SUCCESS
                        else -> SpectraStatusTone.INFO
                    },
                )
            }
            if (cloudMetricSummary.isNotEmpty()) {
                Text(cloudMetricSummary.joinToString(" · "), style = MaterialTheme.typography.titleMedium)
            } else Text(
                "今天还没有可显示的健康数据。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(.66f),
            )
            cloudSnapshot?.lastSyncAt?.let {
                Text(
                    "更新 ${healthStatusTime(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(.56f),
                )
            }
            if (failed) {
                Text(
                    when (state.miFitnessStatus) {
                        MiFitnessUiStatus.NO_DATA -> "今天还没有同步到健康数据。"
                        MiFitnessUiStatus.AUTH_ERROR -> "身份验证失败，请在个人页更新凭据。"
                        MiFitnessUiStatus.NETWORK_ERROR -> "网络异常，请稍后重试。"
                        MiFitnessUiStatus.STORAGE_ERROR -> "系统安全存储暂不可用。"
                        else -> "本次刷新未完成。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            current.actionMessage?.takeUnless { failed }?.let {
                Text(if (cloudSnapshot != null && !hasShareableMetrics) "同步已完成，但今天暂无可用健康指标；缺失值仍为未知。" else it,
                    style = MaterialTheme.typography.bodySmall, color = SpectraColors.Success)
            }
            SpectraAction(
                text = if (state.miFitnessSyncing) "正在同步" else "同步今日健康",
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.loading && !state.miFitnessSyncing,
                mood = PageMood.HEALTH,
            )
            Text(
                "CampusAI 不连接手环，也不需要 Health Connect 权限。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(.52f),
            )
        }
    }
}

internal fun healthFreshnessLabel(value: HealthFreshness): String = when (value) {
    HealthFreshness.LIVE -> "实时"
    HealthFreshness.FRESH -> "新鲜"
    HealthFreshness.STALE -> "已过期"
    HealthFreshness.UNKNOWN -> "新鲜度未知"
}

internal fun healthStatusTime(value: Long): String = runCatching {
    DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.ofEpochMilli(value).atZone(com.campusai.core.time.CaesarTime.zone))
}.getOrDefault("未知")

@Composable
internal fun ContextToggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
