package com.campusai.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.campusai.core.designsystem.SpectraColors
import com.campusai.core.model.TimeRecord
import com.campusai.core.model.TimeRecordCalendar

internal data class AchievementUi(val name: String, val description: String, val progress: Int, val target: Int,
    val colors: List<Color>, val icon: ImageVector, val sides: Int = 6) {
    val unlocked get() = progress >= target
}

internal fun buildAchievements(records: List<TimeRecord>, remoteStreak: Int, now: Long = System.currentTimeMillis()): List<AchievementUi> {
    val completed = records.filter { it.endTime <= now && TimeRecordCalendar.completionDate(it) != null }
    val minutes = completed.sumOf { it.durationMinutes }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val dates = completed.mapNotNull { TimeRecordCalendar.completionDate(it) }.distinct().sorted()
    var run = 0
    var longest = 0
    dates.forEachIndexed { i, date ->
        run = if (i > 0 && dates[i - 1].plusDays(1) == date) run + 1 else 1
        longest = maxOf(longest, run)
    }
    val streak = maxOf(remoteStreak, longest)
    val focuses = completed.count { it.category.contains("专注") && it.durationMinutes >= 25 }
    val categories = completed.map { it.category }.filter { it.isNotBlank() }.distinct().size
    val notes = completed.count { it.remark.isNotBlank() }
    val palette = listOf(SpectraColors.Cyan, SpectraColors.Focus, SpectraColors.Violet, SpectraColors.Warm, SpectraColors.Rose)
    fun badge(name: String, detail: String, progress: Int, target: Int, icon: ImageVector, color: Int, sides: Int = 6) =
        AchievementUi(name, detail, progress, target, listOf(palette[color % palette.size], palette[(color + 1) % palette.size]), icon, sides)
    return listOf(
        badge("第一束光", "完成第一条时间记录", completed.size, 1, Icons.Rounded.Eco, 0, 0),
        badge("专注起航", "完成一次 25 分钟专注", focuses, 1, Icons.Rounded.RocketLaunch, 1),
        badge("稳定节奏", "连续记录 7 天", streak, 7, Icons.Rounded.LocalFireDepartment, 3),
        badge("深度轨道", "累计投入 10 小时", minutes, 600, Icons.Rounded.Public, 2, 0),
        badge("时间建筑师", "完成 25 条记录", completed.size, 25, Icons.Rounded.Architecture, 0),
        badge("完整光谱", "覆盖 5 个学习分类", categories, 5, Icons.Rounded.Palette, 4, 8),
        badge("百小时节点", "累计投入 100 小时", minutes, 6000, Icons.Rounded.Diamond, 3),
        badge("初见山峰", "累计投入 1 小时", minutes, 60, Icons.Rounded.Landscape, 1),
        badge("星星之火", "完成 5 条记录", completed.size, 5, Icons.Rounded.Star, 3, 0),
        badge("专注巡航", "完成 10 次 25 分钟专注", focuses, 10, Icons.Rounded.Flight, 0),
        badge("心流时刻", "一次记录达到 60 分钟", completed.count { it.durationMinutes >= 60 }, 1, Icons.Rounded.Waves, 1, 0),
        badge("拾光手记", "为 10 条记录填写备注", notes, 10, Icons.Rounded.EditNote, 2, 8),
        badge("三色探索", "体验 3 个记录分类", categories, 3, Icons.Rounded.Explore, 4, 0),
        badge("月光旅人", "在 30 个不同日期留下记录", dates.size, 30, Icons.Rounded.DarkMode, 2),
        badge("双周坚持", "连续记录 14 天", streak, 14, Icons.Rounded.CalendarMonth, 0, 8),
        badge("光之收藏家", "完成 100 条记录", completed.size, 100, Icons.Rounded.EmojiEvents, 3),
    )
}

@Composable
internal fun AchievementBadge(item: AchievementUi, modifier: Modifier = Modifier) {
    val ink = if (item.unlocked) item.colors.first() else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val brush = Brush.linearGradient(item.colors.map { it.copy(alpha = if (item.unlocked) .8f else .22f) })
            if (item.sides == 0) {
                drawCircle(ink.copy(alpha = .09f), radius = size.minDimension * .46f)
                drawCircle(brush, radius = size.minDimension * .46f, style = Stroke(1.5.dp.toPx()))
            } else {
                val path = Path()
                repeat(item.sides) { index ->
                    val angle = Math.PI * 2 * index / item.sides - Math.PI / 2
                    val x = center.x + kotlin.math.cos(angle).toFloat() * size.minDimension * .46f
                    val y = center.y + kotlin.math.sin(angle).toFloat() * size.minDimension * .46f
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, ink.copy(alpha = .09f))
                drawPath(path, brush, style = Stroke(1.5.dp.toPx()))
            }
        }
        Icon(item.icon, contentDescription = null, modifier = Modifier.size(26.dp), tint = ink)
    }
}
