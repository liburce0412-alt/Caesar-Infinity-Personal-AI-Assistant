package com.campusai.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.campusai.core.designsystem.*
import com.campusai.core.preferences.UserPreferences
import com.campusai.core.preferences.UserPreferencesRepository
import kotlinx.coroutines.launch

internal enum class OptionalComponent(val title: String) {
    TODAY("今日进度"), HEALTH("今日健康"), STREAK("连续记录"), INSIGHTS("AI 洞察"), ANNOUNCEMENTS("公告消息"), TIMETABLE("课程表"),
}

@Composable
internal fun CollapsibleComponent(
    component: OptionalComponent,
    collapsed: Boolean,
    onExpand: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (collapsed) {
        GlassPanel(onClick = onExpand, modifier = Modifier.fillMaxWidth(), radius = 24) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(component.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text("展开", style = MaterialTheme.typography.labelMedium)
                Icon(Icons.Rounded.ExpandMore, null)
            }
        }
    } else content()
}

@Composable
internal fun ComponentSettings(preferences: UserPreferences, repository: UserPreferencesRepository) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("把首页内容或时间页的课程表收起来，需要时轻点标题就能展开。折叠只影响显示，不会停止同步或删除记录。", style = MaterialTheme.typography.bodyMedium)
        GlassPanel(Modifier.fillMaxWidth(), radius = 24) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionalComponent.entries.forEach { component ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(component.title, style = MaterialTheme.typography.titleSmall)
                            Text(if (component.name in preferences.collapsedComponents) "已折叠" else "完整显示", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = component.name in preferences.collapsedComponents,
                            onCheckedChange = { collapsed -> scope.launch { repository.setComponentCollapsed(component.name, collapsed) } },
                            modifier = Modifier.semantics { contentDescription = "折叠${component.title}" },
                        )
                    }
                }
            }
        }
    }
}

/** One short sequence per entry. Input stays available throughout; reduced motion is static. */
@Composable
internal fun PeaceWelcome(motionEnabled: Boolean = SpectraTheme.tokens.motion.enabled) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(motionEnabled) {
        if (motionEnabled) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(600))
        } else progress.snapTo(1f)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("我们的心愿是", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("世界和平", style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.graphicsLayer {
                alpha = .65f + progress.value * .35f
                translationY = (1f - progress.value) * 8f * density
            })
    }
}

@Composable
internal fun WelcomeGuide(onFinish: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("给今天，留一点从容", "界面，由你做减法", "分享之前，你来决定", "让关心成为日常")
    val details = listOf(
        "记录时间、写下心愿，也给心里的话留个位置。欢迎来到 Caesar∞。",
        "进入「我的 → 组件与内容」，把用不上的首页卡片折叠。需要时，轻点卡片标题即可恢复。",
        "树洞和心愿默认只给自己看。以前发过的内容，也能在「修改」里重新选择公开或隐藏。",
        "在「我的 → 健康自动化」选择 AI 和提醒间隔，确认健康摘要授权后开启。目前仅在 App 前台运行，你随时可以暂停。",
    )
    SpectraFullScreenDialog(onDismissRequest = { if (step > 0) step-- else onFinish() }, mood = PageMood.GROWTH) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("初次见面 · ${step + 1} / 4", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = onFinish) { Text("跳过引导") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 20.dp), verticalArrangement = Arrangement.Center) {
                PeaceWelcome()
                Spacer(Modifier.height(24.dp))
                Text(titles[step], style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(18.dp))
                Text(details[step], style = MaterialTheme.typography.bodyLarge)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step > 0) TextButton(onClick = { step-- }) { Text("上一步") }
                SpectraPrimaryButton(if (step == 3) "开始我的一天" else "继续", { if (step == 3) onFinish() else step++ }, Modifier.weight(1f))
            }
        }
    }
}
