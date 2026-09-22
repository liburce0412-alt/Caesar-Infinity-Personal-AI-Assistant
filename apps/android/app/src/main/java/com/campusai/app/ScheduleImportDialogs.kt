package com.campusai.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.campusai.core.designsystem.*
import com.campusai.features.schedule.CourseDraft

@Composable
internal fun ImportScheduleSourceDialog(onDismiss:()->Unit,onImage:()->Unit,onIcs:()->Unit,onManual:()->Unit) {
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

private data class CoursePreviewEntry(
    val key: Int,
    val draft: CourseDraft,
    val startText: String = if (draft.startMinute >= 0) formatClock(draft.startMinute) else "",
    val endText: String = if (draft.endMinute >= 0) formatClock(draft.endMinute) else "",
    val reviewed: Boolean = draft.reviewNote.isBlank(),
) {
    fun validated(): CourseDraft? {
        val start = parseClockOrNull(startText) ?: return null
        val end = parseClockOrNull(endText) ?: return null
        return draft.takeIf { it.name.isNotBlank() && end > start && reviewed }?.copy(startMinute = start, endMinute = end)
    }
}

@Composable
internal fun SchedulePreviewDialog(initial: List<CourseDraft>, onDismiss: () -> Unit, onConfirm: (List<CourseDraft>) -> Unit) {
    var entries by remember(initial) { mutableStateOf(initial.mapIndexed { index, draft -> CoursePreviewEntry(index, draft) }) }
    fun update(entry: CoursePreviewEntry) { entries = entries.map { if (it.key == entry.key) entry else it } }
    SpectraDialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("确认课程（${entries.size}）", style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("请核对课程、星期和时间；重复课程会自动跳过。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.62f)) }
                items(entries, key = { it.key }) { entry ->
                    val draft = entry.draft
                    GlassPanel(Modifier.fillMaxWidth(), radius = 16) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SpectraTextField(draft.name, { update(entry.copy(draft = draft.copy(name = it))) }, label = { Text("课程名") }, singleLine = true, isError = draft.name.isBlank(), modifier = Modifier.fillMaxWidth())
                            SpectraTextField(draft.location, { update(entry.copy(draft = draft.copy(location = it))) }, label = { Text("教室（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            SpectraTextField(draft.teacher, { update(entry.copy(draft = draft.copy(teacher = it))) }, label = { Text("教师（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            SpectraTextField(draft.weeks, { update(entry.copy(draft = draft.copy(weeks = it))) }, label = { Text("周次与日期规则") }, maxLines = 8, modifier = Modifier.fillMaxWidth())
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(7) { index ->
                                    FilterChip(selected = draft.weekday == index + 1, onClick = { update(entry.copy(draft = draft.copy(weekday = index + 1))) }, label = { Text("周${"一二三四五六日"[index]}") })
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SpectraTextField(entry.startText, { update(entry.copy(startText = it)) }, label = { Text("开始 HH:mm") }, singleLine = true, modifier = Modifier.weight(1f), isError = parseClockOrNull(entry.startText) == null)
                                SpectraTextField(entry.endText, { update(entry.copy(endText = it)) }, label = { Text("结束 HH:mm") }, singleLine = true, modifier = Modifier.weight(1f), isError = parseClockOrNull(entry.endText) == null)
                            }
                            if (draft.reviewNote.isNotBlank()) {
                                Text(draft.reviewNote, style = MaterialTheme.typography.bodySmall)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(entry.reviewed, { update(entry.copy(reviewed = it)) })
                                    Text("已核对日期、星期与时间", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (entry.copy(reviewed = true).validated() == null) Text("请填写课程名和有效时间，结束须晚于开始。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { entries = entries.filterNot { it.key == entry.key } }) { Text("移除这条") }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(enabled = entries.isNotEmpty() && entries.all { it.validated() != null }, onClick = { onConfirm(entries.mapNotNull { it.validated() }) }) { Text("确认导入") }
            }
        }
    }
}

private fun formatClock(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

private fun parseClockOrNull(value: String): Int? {
    val match = Regex("^(\\d{1,2}):([0-5]\\d)$").matchEntire(value.trim()) ?: return null
    val hour = match.groupValues[1].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    return hour * 60 + match.groupValues[2].toInt()
}
