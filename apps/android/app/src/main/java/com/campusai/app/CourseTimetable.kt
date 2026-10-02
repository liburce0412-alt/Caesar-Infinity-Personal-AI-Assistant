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
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.campusai.features.schedule.groupDayCourses
import java.time.LocalDate

private fun weekdayLabel(day: Int) = "周${"一二三四五六日"[day - 1]}"

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun CourseTimetable(courses: List<CourseSchedule>, onRemove: ((CourseSchedule) -> Unit)? = null, onImport: () -> Unit) {
    val largeText = LocalDensity.current.fontScale > 1.4f
    var view by rememberSaveable { mutableIntStateOf(if (largeText) 1 else 0) }
    var showWeekend by rememberSaveable { mutableStateOf(false) }
    var showManage by rememberSaveable { mutableStateOf(false) }
    var overlapSelection by remember { mutableStateOf<List<CourseSchedule>?>(null) }
    var day by rememberSaveable { mutableIntStateOf(LocalDate.now().dayOfWeek.value) }
    var selected by remember { mutableStateOf<CourseSchedule?>(null) }
    val validCourses = remember(courses) {
        courses.filter { it.weekday in 1..7 && it.startMinute in 0..1439 && it.endMinute in 1..1440 && it.endMinute > it.startMinute }
    }
    SpectraSurface(modifier = Modifier.fillMaxWidth(), mood = PageMood.FOCUS, contentPadding = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("课程表", style = MaterialTheme.typography.titleLarge)
                    Text("${courses.size} 门安排", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onImport) { Text(if (largeText) "导入" else "添加 / 导入") }
            }
            CaesarSlidingSelector(
                options = listOf("周课表", "按天查看"), selectedIndex = view,
                onSelected = { view = it }, modifier = Modifier.fillMaxWidth(),
            )

            if (validCourses.size != courses.size) {
                Text("部分课程的时间不完整，暂未放入课表。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (view == 0) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("星期概览 · 周次见详情", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { showWeekend = !showWeekend }) {
                        Text(if (showWeekend) "收起周末" else "周末 ${validCourses.count { it.weekday >= 6 }} 门")
                    }
                }
                WeekCourseGrid(validCourses, showWeekend, onSelect = { selected = it }, onOverlap = { overlapSelection = it })
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
                        Column(Modifier.width(IntrinsicSize.Max)) {
                            Text(courseClock(course.startMinute), style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false)
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
            if (onRemove != null && courses.isNotEmpty()) {
                TextButton(onClick = { showManage = true }, modifier = Modifier.align(Alignment.End)) { Text("管理课程") }
            }
        }
    }
    overlapSelection?.let { overlapping ->
        SpectraDialog(onDismissRequest = { overlapSelection = null }) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(if (overlapping.any { it.hasTimeOverlap(overlapping) }) "重叠的课程安排" else "相邻的课程安排", style = MaterialTheme.typography.titleLarge)
                Text("点按查看每门课程的时间与授课周次。", style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(overlapping) { course ->
                        TextButton(onClick = { overlapSelection = null; selected = course }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(course.name, style = MaterialTheme.typography.titleMedium)
                                Text("${courseClock(course.startMinute)}–${courseClock(course.endMinute)} · ${course.location}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                TextButton(onClick = { overlapSelection = null }, Modifier.align(Alignment.End)) { Text("关闭") }
            }
        }
    }
    if (showManage && onRemove != null) ManageCoursesDialog(courses, { showManage = false }, onRemove)
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
                FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.End,
                    maxItemsInEachRow = if (largeText) 1 else 2) {
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
private fun WeekCourseGrid(
    courses: List<CourseSchedule>, showWeekend: Boolean,
    onSelect: (CourseSchedule) -> Unit, onOverlap: (List<CourseSchedule>) -> Unit,
) {
    val days = if (showWeekend) 1..7 else 1..5
    val groups = remember(courses) { (1..7).associateWith { day -> groupDayCourses(courses.filter { it.weekday == day }, minimumMinutes = 68) } }
    val startHour = minOf(8, (courses.minOfOrNull { it.startMinute } ?: 480) / 60)
    val endHour = maxOf(18, ((courses.maxOfOrNull { it.endMinute } ?: 1080) + 59) / 60)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val minuteHeight = .75.dp
    val lastVisibleMinute = maxOf(endHour * 60, courses.maxOfOrNull { maxOf(it.endMinute, it.startMinute + 68) } ?: endHour * 60)
    val bodyHeight = minuteHeight * (lastVisibleMinute - startHour * 60) + 24.dp
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("week-grid")) {
        val axisWidth = (32 * fontScale).dp
        val columnWidth = maxOf((maxWidth - axisWidth) / days.count(), (48 * fontScale).dp)
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(axisWidth)) {
                Box(Modifier.height(40.dp), contentAlignment = Alignment.CenterStart) { Text("时间", style = MaterialTheme.typography.labelSmall) }
                Box(Modifier.height(420.dp).verticalScroll(vertical)) {
                    Box(Modifier.height(bodyHeight).fillMaxWidth()) {
                        (startHour..endHour).forEach { hour ->
                            Text(courseClock(hour * 60), Modifier.offset(y = minuteHeight * ((hour - startHour) * 60)),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).horizontalScroll(horizontal)) {
                Row {
                    days.forEach { day ->
                        Box(Modifier.width(columnWidth).height(40.dp), contentAlignment = Alignment.Center) {
                            Text(weekdayLabel(day), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Row(Modifier.height(420.dp).verticalScroll(vertical)) {
                    days.forEach { day ->
                        Box(Modifier.width(columnWidth).height(bodyHeight)) {
                            Box(Modifier.width(.5.dp).fillMaxHeight().background(lineColor))
                            (startHour..endHour).forEach { hour ->
                                HorizontalDivider(Modifier.offset(y = minuteHeight * ((hour - startHour) * 60)), color = lineColor, thickness = .5.dp)
                            }
                            groups.getValue(day).forEach { group ->
                                val course = group.first()
                                val colors = when ((course.name.hashCode() and Int.MAX_VALUE) % 3) {
                                    0 -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                                    1 -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                                    else -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                                }
                                val visualEnd = group.maxOf { maxOf(it.endMinute, it.startMinute + 68) }
                                val label = if (group.size == 1) "${weekdayLabel(course.weekday)}，${course.name}，${courseClock(course.startMinute)}至${courseClock(course.endMinute)}，${course.location}"
                                    else "${weekdayLabel(day)}，${group.size} 门${if (group.any { it.hasTimeOverlap(group) }) "重叠" else "相邻"}安排，点击查看"
                                Column(
                                    Modifier.offset(x = 2.dp, y = minuteHeight * (course.startMinute - startHour * 60))
                                        .width(columnWidth - 4.dp).height(minuteHeight * (visualEnd - course.startMinute) - 2.dp)
                                        .clip(RoundedCornerShape(8.dp)).background(colors.first)
                                        .border(.5.dp, colors.second.copy(alpha = .12f), RoundedCornerShape(8.dp))
                                        .clickable(role = Role.Button) { if (group.size == 1) onSelect(course) else onOverlap(group) }
                                        .semantics(mergeDescendants = true) { contentDescription = label }
                                        .padding(horizontal = 4.dp, vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Text(course.name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                                        color = colors.second, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    if (group.size > 1) Text("共 ${group.size} 门", style = MaterialTheme.typography.labelSmall, color = colors.second)
                                    else if (visualEnd - course.startMinute >= 75 && course.location.isNotBlank()) {
                                        Text(course.location, style = MaterialTheme.typography.labelSmall, color = colors.second.copy(alpha = .85f),
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
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
private fun ManageCoursesDialog(courses: List<CourseSchedule>, onDismiss: () -> Unit, onRemove: (CourseSchedule) -> Unit) {
    var chosen by remember { mutableStateOf(setOf<Int>()) }
    var confirm by remember { mutableStateOf(false) }
    SpectraDialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("管理课程", style = MaterialTheme.typography.titleLarge)
            Text("勾选需要移除的安排，可清理之前识别错误的条目。", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { chosen = if (chosen.size == courses.size) emptySet() else courses.map { it.id }.toSet(); confirm = false }) {
                Text(if (chosen.size == courses.size) "取消全选" else "全选")
            }
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(courses, key = { it.id }) { course ->
                    Row(Modifier.fillMaxWidth().selectable(course.id in chosen, role = Role.Checkbox, onClick = {
                        chosen = if (course.id in chosen) chosen - course.id else chosen + course.id; confirm = false
                    }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = course.id in chosen, onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text(course.name, style = MaterialTheme.typography.bodyLarge)
                            Text("${weekdayLabel(course.weekday.coerceIn(1, 7))} · ${courseClock(course.startMinute)}–${courseClock(course.endMinute)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (confirm) Text("将删除选中的 ${chosen.size} 门安排，请再次确认。", color = MaterialTheme.colorScheme.error)
            Button(onClick = {
                if (confirm) { courses.filter { it.id in chosen }.forEach(onRemove); onDismiss() } else confirm = true
            }, enabled = chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(if (confirm) "确认删除 ${chosen.size} 门" else "删除选中 (${chosen.size})")
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
        }
    }
}
