package com.campusai.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.campusai.core.designsystem.CaesarSlidingSelector
import com.campusai.core.designsystem.PageMood
import com.campusai.core.designsystem.SpectraDialog
import com.campusai.core.designsystem.SpectraSurface
import com.campusai.core.model.CourseSchedule
import com.campusai.features.schedule.courseClock
import com.campusai.features.schedule.hasTimeOverlap
import com.campusai.features.schedule.placeDayCourses
import java.time.LocalDate

private fun weekdayLabel(day: Int) = "周${"一二三四五六日"[day - 1]}"

@Composable
internal fun CourseTimetable(courses: List<CourseSchedule>, onRemove: ((CourseSchedule) -> Unit)? = null, onImport: () -> Unit) {
    var view by rememberSaveable { mutableIntStateOf(0) }
    var day by rememberSaveable { mutableIntStateOf(LocalDate.now().dayOfWeek.value) }
    var selected by remember { mutableStateOf<CourseSchedule?>(null) }
    val validCourses = remember(courses) {
        courses.filter { it.weekday in 1..7 && it.startMinute in 0..1439 && it.endMinute in 1..1440 && it.endMinute > it.startMinute }
    }
    SpectraSurface(modifier = Modifier.fillMaxWidth(), mood = PageMood.FOCUS, contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("课程表", style = MaterialTheme.typography.titleLarge)
                    Text("${courses.size} 项课程安排", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onImport) { Text("添加 / 导入") }
            }
            CaesarSlidingSelector(
                options = listOf("周课表", "按天查看"), selectedIndex = view,
                onSelected = { view = it }, modifier = Modifier.fillMaxWidth(),
            )
            Text("按星期展示全部安排，实际授课周次请查看课程详情。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (validCourses.size != courses.size) {
                Text("部分课程的时间不完整，暂未放入课表。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (view == 0) {
                Text("左右滑动查看整周 · 点击课程查看详情", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                WeekCourseGrid(validCourses, onSelect = { selected = it })
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..7).forEach { weekday ->
                        Column(
                            Modifier.widthIn(min = 52.dp).clip(RoundedCornerShape(14.dp))
                                .background(if (day == weekday) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = .3f))
                                .selectable(day == weekday, role = Role.Tab, onClick = { day = weekday })
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(weekdayLabel(weekday), style = MaterialTheme.typography.labelLarge)
                            Text("${validCourses.count { it.weekday == weekday }} 项", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                val daily = validCourses.filter { it.weekday == day }.sortedBy { it.startMinute }
                if (daily.isEmpty()) Text("${weekdayLabel(day)}没有课程安排", Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                daily.forEach { course ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { selected = course }.padding(vertical = 12.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column {
                            Text(courseClock(course.startMinute), style = MaterialTheme.typography.titleSmall)
                            Text(courseClock(course.endMinute), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(course.name, style = MaterialTheme.typography.titleMedium)
                            Text(course.location.ifBlank { "教室待补充" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (course.weeks.isNotBlank()) Text(course.weeks, style = MaterialTheme.typography.bodySmall)
                            if (course.hasTimeOverlap(validCourses)) Text("时间重叠 · 请核对周次", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                }
            }
        }
    }
    selected?.let { course ->
        var confirmRemoval by remember(course.id) { mutableStateOf(false) }
        SpectraDialog(onDismissRequest = { selected = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(course.name, style = MaterialTheme.typography.headlineSmall)
                Text("${weekdayLabel(course.weekday)}  ${courseClock(course.startMinute)}–${courseClock(course.endMinute)}", style = MaterialTheme.typography.titleMedium)
                Text("教室：${course.location.ifBlank { "未填写" }}")
                Text("教师：${course.teacher.ifBlank { "未填写" }}")
                Text("周次：${course.weeks.ifBlank { "未填写，请核对原始课表" }}")
                if (course.hasTimeOverlap(validCourses)) Text("与同一天的其他课程时间重叠，请确认是否属于不同教学周。", color = MaterialTheme.colorScheme.error)
                Row(Modifier.align(Alignment.End)) {
                    if (onRemove != null) TextButton(onClick = {
                        if (confirmRemoval) { onRemove(course); selected = null } else confirmRemoval = true
                    }) { Text(if (confirmRemoval) "确认删除课程" else "删除课程", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { selected = null }) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun WeekCourseGrid(courses: List<CourseSchedule>, onSelect: (CourseSchedule) -> Unit) {
    val placements = remember(courses) { (1..7).map { day -> placeDayCourses(courses.filter { it.weekday == day }) } }
    val startHour = minOf(8, (courses.minOfOrNull { it.startMinute } ?: 480) / 60)
    val lastVisualMinute = courses.maxOfOrNull { maxOf(it.endMinute, it.startMinute + 40) } ?: 1200
    val endHour = maxOf(20, ((courses.maxOfOrNull { it.endMinute } ?: 1200) + 59) / 60)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val minuteHeight = (1.2f * fontScale).dp
    val laneWidth = (112 * fontScale).dp
    val dayWidths = placements.map { laneWidth * ((it.maxOfOrNull { entry -> entry.lane } ?: 0) + 1) }
    val bodyHeight = minuteHeight * (maxOf(endHour * 60, lastVisualMinute) - startHour * 60) + 20.dp
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width((48 * fontScale).dp)) {
            Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) { Text("时间", style = MaterialTheme.typography.labelSmall) }
            Box(Modifier.height(360.dp).verticalScroll(vertical)) {
                Box(Modifier.height(bodyHeight).fillMaxWidth()) {
                    (startHour..endHour).forEach { hour ->
                        Text(courseClock(hour * 60), Modifier.offset(y = minuteHeight * ((hour - startHour) * 60)).fillMaxWidth(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        Column(Modifier.weight(1f).horizontalScroll(horizontal)) {
            Row {
                dayWidths.forEachIndexed { index, width ->
                    Box(Modifier.width(width).height(48.dp), contentAlignment = Alignment.Center) {
                        Text(weekdayLabel(index + 1), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            Row(Modifier.height(360.dp).verticalScroll(vertical)) {
                placements.forEachIndexed { index, entries ->
                    Box(Modifier.width(dayWidths[index]).height(bodyHeight)) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(lineColor))
                        (startHour..endHour).forEach { hour ->
                            HorizontalDivider(Modifier.offset(y = minuteHeight * ((hour - startHour) * 60)), color = lineColor)
                        }
                        entries.forEach { (course, lane) ->
                            val color = when ((course.name.hashCode() and Int.MAX_VALUE) % 3) {
                                0 -> MaterialTheme.colorScheme.primaryContainer
                                1 -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.tertiaryContainer
                            }
                            val foreground = when ((course.name.hashCode() and Int.MAX_VALUE) % 3) {
                                0 -> MaterialTheme.colorScheme.onPrimaryContainer
                                1 -> MaterialTheme.colorScheme.onSecondaryContainer
                                else -> MaterialTheme.colorScheme.onTertiaryContainer
                            }
                            Column(
                                Modifier.offset(x = laneWidth * lane + 3.dp, y = minuteHeight * (course.startMinute - startHour * 60))
                                    .width(laneWidth - 6.dp).height(minuteHeight * maxOf(40, course.endMinute - course.startMinute))
                                    .clip(RoundedCornerShape(10.dp)).background(color)
                                    .border(1.dp, foreground.copy(alpha = .12f), RoundedCornerShape(10.dp))
                                    .clickable(role = Role.Button, onClick = { onSelect(course) })
                                    .semantics { contentDescription = "${weekdayLabel(course.weekday)}，${course.name}，${courseClock(course.startMinute)}至${courseClock(course.endMinute)}，${course.location}" }
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(course.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(course.location.ifBlank { courseClock(course.startMinute) }, style = MaterialTheme.typography.labelSmall, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (course.endMinute - course.startMinute >= 80) Text("${courseClock(course.startMinute)}–${courseClock(course.endMinute)}", style = MaterialTheme.typography.labelSmall, color = foreground)
                            }
                        }
                    }
                }
            }
        }
    }
}
