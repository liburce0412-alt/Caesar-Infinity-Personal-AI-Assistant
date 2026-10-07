package com.campusai.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.campusai.core.model.CourseSchedule
import com.campusai.features.schedule.courseClock
import com.campusai.features.schedule.courseTimeLabel

/** School timetable coordinates are teaching periods, including lunch/evening breaks. */
@Composable
internal fun PeriodCourseGrid(
    courses: List<CourseSchedule>,
    onSelect: (CourseSchedule) -> Unit,
    onOverlap: (List<CourseSchedule>) -> Unit,
    weekMonday: java.time.LocalDate? = null,
) {
    val periods = maxOf(12, courses.maxOfOrNull { it.periodEnd } ?: 12)
    val starts = remember(courses) {
        courses.maxByOrNull { it.periodStartTimes.count { char -> char == ',' } }
            ?.periodStartTimes?.split(',')?.map { it.toIntOrNull() }.orEmpty()
    }
    val groups = remember(courses) {
        (1..7).associateWith { day ->
            val result = mutableListOf<MutableList<CourseSchedule>>()
            var end = 0
            courses.filter { it.weekday == day }.sortedBy { it.periodStart }.forEach { course ->
                if (result.isEmpty() || course.periodStart > end) {
                    result.add(mutableListOf(course)); end = course.periodEnd
                } else {
                    result.last().add(course); end = maxOf(end, course.periodEnd)
                }
            }
            result
        }
    }
    val palette = listOf(0xFFD4E9FF, 0xFFE4D7F7, 0xFFF9DDC2, 0xFFD4E9DD, 0xFFF4D5E1, 0xFFE8E4BF)
    val line = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)
    val rowHeight = 42.dp
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("period-grid")) {
        val axis = 34.dp
        val column = (maxWidth - axis) / 7
        Column {
            Row(Modifier.height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("节次", Modifier.width(axis), style = MaterialTheme.typography.labelSmall)
                (1..7).forEach { day ->
                    Column(Modifier.width(column), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("周${"一二三四五六日"[day - 1]}", style = MaterialTheme.typography.labelSmall)
                        WeekDateLabel(weekMonday, day)
                    }
                }
            }
            Row(Modifier.height(rowHeight * periods)) {
                Column(Modifier.width(axis)) {
                    (1..periods).forEach { period ->
                        Column(Modifier.height(rowHeight).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                            Text(period.toString(), style = MaterialTheme.typography.labelMedium)
                            starts.getOrNull(period - 1)?.let { minute ->
                                Text(courseClock(minute), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
                (1..7).forEach { day ->
                    Box(Modifier.width(column).fillMaxHeight()) {
                        Box(Modifier.width(.5.dp).fillMaxHeight().background(line))
                        (0..periods).forEach { period -> HorizontalDivider(Modifier.offset(y = rowHeight * period), thickness = .5.dp, color = line) }
                        groups.getValue(day).forEach { group ->
                            val course = group.first()
                            val span = group.maxOf { it.periodEnd } - course.periodStart + 1
                            val tint = Color(palette[(course.name.hashCode() and Int.MAX_VALUE) % palette.size])
                            val ink = Color(0xFF182235)
                            val label = "周${"一二三四五六日"[day - 1]}，${course.name}，${courseTimeLabel(course)}，${course.location}" +
                                if (group.size > 1) "，另有 ${group.size - 1} 项安排" else ""
                            Column(
                                Modifier.offset(x = 1.dp, y = rowHeight * (course.periodStart - 1) + 1.dp)
                                    .width(column - 2.dp).height(rowHeight * span - 2.dp)
                                    .clip(RoundedCornerShape(6.dp)).background(tint.copy(alpha = .88f))
                                    .border(.5.dp, Color.White.copy(alpha = .55f), RoundedCornerShape(6.dp))
                                    .clickable(role = Role.Button) { if (group.size == 1) onSelect(course) else onOverlap(group) }
                                    .semantics(mergeDescendants = true) { contentDescription = label }
                                    .padding(horizontal = 3.dp, vertical = 5.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(course.name, style = MaterialTheme.typography.labelSmall, color = ink,
                                    fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text(if (group.size > 1) "${group.size} 项" else compactClassroom(course.location),
                                    style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = .8f),
                                    maxLines = if (span > 2) 4 else 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun compactClassroom(location: String): String =
    Regex("[\\[［]([^\\]］]+)[\\]］]").find(location)?.groupValues?.get(1) ?: location
