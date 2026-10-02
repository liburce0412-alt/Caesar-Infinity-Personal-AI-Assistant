package com.campusai.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
    val expanded: Boolean = false,
) {
    fun validated(): CourseDraft? {
        val start = parseClockOrNull(startText) ?: return null
        val end = parseClockOrNull(endText) ?: return null
        return draft.takeIf { it.name.isNotBlank() && end > start && reviewed }?.copy(startMinute = start, endMinute = end)
    }
}

@Composable
internal fun SchedulePreviewDialog(initial: List<CourseDraft>, onDismiss: () -> Unit, onConfirm: (List<CourseDraft>) -> Unit,
    isSaving: Boolean = false, saveError: String? = null) {
    var entries by remember(initial) { mutableStateOf(initial.mapIndexed { index, draft -> CoursePreviewEntry(index, draft, expanded = initial.size == 1) }) }
    val periods = remember(initial) { initial.flatMap { listOfNotNull(it.periodStart, it.periodEnd) }.distinct().sorted() }
    var periodTimes by remember(initial) { mutableStateOf(periods.associateWith { "" to "" }) }
    var showPeriods by remember(initial) { mutableStateOf(false) }
    var timetableReviewed by remember(initial) { mutableStateOf(false) }
    var periodTimesDirty by remember(initial) { mutableStateOf(false) }
    fun update(entry: CoursePreviewEntry) {
        entries = entries.map { previous ->
            if (previous.key != entry.key) previous else {
                val contentChanged = previous.draft != entry.draft || previous.startText != entry.startText || previous.endText != entry.endText
                entry.copy(reviewed = if (contentChanged && entry.draft.reviewNote.isNotBlank()) false else entry.reviewed)
            }
        }
    }
    SpectraDialog(onDismissRequest = { if (!isSaving) onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("确认课程（${entries.size}）", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("${entries.count { it.validated() != null }} 项已就绪 · ${entries.count { it.startText.isBlank() || it.endText.isBlank() }} 项待设置时间", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("schedule-import-list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    val notice = "核对课程名、教室和周次，展开课程可修改；重复安排会自动跳过。" +
                        if (entries.any { it.draft.reviewNote.contains("截断") }) "截图中省略的文字需要手动补全。" else ""
                    Text(notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (periods.isNotEmpty()) item(key = "period-setup") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("这张截图只有节次，没有钟点", style = MaterialTheme.typography.titleSmall)
                        Text("设置一次学校作息，即可为所有课程填写时间。", style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !isSaving, onClick = { showPeriods = !showPeriods }) { Text(if (showPeriods) "收起节次时间" else "设置节次时间") }
                        if (showPeriods) {
                            TextButton(enabled = !isSaving, onClick = {
                                periodTimes = periods.associateWith { period -> examplePeriodTimes.getOrNull(period - 1) ?: ("" to "") }
                                timetableReviewed = false
                                periodTimesDirty = true
                            }) { Text("填入示例作息（需核对）") }
                            Text("示例时间不是识别结果，请改成你学校的实际作息。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (showPeriods) {
                    items(periods, key = { "period-$it" }) { period ->
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("第 $period 节", style = MaterialTheme.typography.labelLarge)
                                val value = periodTimes.getValue(period)
                                ImportTimeFields(value.first, value.second,
                                    { periodTimes = periodTimes + (period to (it to value.second)); timetableReviewed = false; periodTimesDirty = true },
                                    { periodTimes = periodTimes + (period to (value.first to it)); timetableReviewed = false; periodTimesDirty = true },
                                    "第${period}节开始", "第${period}节结束", enabled = !isSaving)
                            }
                        }
                    }
                    item(key = "period-confirmation") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ImportReviewRow(timetableReviewed, { timetableReviewed = it }, "学校作息已核对", enabled = !isSaving)
                            val validTimes = periodTimes.values.all { (start, end) ->
                                val s = parseClockOrNull(start); val e = parseClockOrNull(end)
                                s != null && e != null && e > s
                            } && periodTimes.toSortedMap().values.zipWithNext().all { (a, b) ->
                                (parseClockOrNull(a.second) ?: Int.MAX_VALUE) <= (parseClockOrNull(b.first) ?: -1)
                            }
                            Button(enabled = !isSaving && timetableReviewed && validTimes, onClick = {
                                entries = entries.map { entry ->
                                    val start = entry.draft.periodStart?.let { periodTimes[it]?.first }
                                    val end = entry.draft.periodEnd?.let { periodTimes[it]?.second }
                                    if (start != null && end != null) entry.copy(startText = start, endText = end, reviewed = entry.draft.reviewNote.isBlank()) else entry
                                }
                                showPeriods = false
                                periodTimesDirty = false
                            }, modifier = Modifier.fillMaxWidth()) { Text("应用已核对的作息") }
                        }
                    }
                }
                items(entries, key = { it.key }) { entry ->
                    val draft = entry.draft
                    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(draft.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    val periodLabel = draft.periodStart?.let { " · 第 $it–${draft.periodEnd ?: it} 节" }.orEmpty()
                                    Text("周${"一二三四五六日"[draft.weekday.coerceIn(1, 7) - 1]}$periodLabel", style = MaterialTheme.typography.bodySmall)
                                    if (entry.startText.isNotBlank() && entry.endText.isNotBlank()) Text("${entry.startText} – ${entry.endText}", style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(enabled = !isSaving, onClick = { update(entry.copy(expanded = !entry.expanded)) }) {
                                    Icon(if (entry.expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = if (entry.expanded) "收起${draft.name}" else "编辑${draft.name}")
                                }
                            }
                            if (entry.expanded) {
                                SpectraTextField(draft.name, { update(entry.copy(draft = draft.copy(name = it))) }, enabled = !isSaving, label = { Text("课程名") }, singleLine = true, isError = draft.name.isBlank(), modifier = Modifier.fillMaxWidth())
                                SpectraTextField(draft.location, { update(entry.copy(draft = draft.copy(location = it))) }, enabled = !isSaving, label = { Text("教室（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                SpectraTextField(draft.teacher, { update(entry.copy(draft = draft.copy(teacher = it))) }, enabled = !isSaving, label = { Text("教师（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                SpectraTextField(draft.weeks, { update(entry.copy(draft = draft.copy(weeks = it))) }, enabled = !isSaving, label = { Text("周次与日期规则") }, maxLines = 8, modifier = Modifier.fillMaxWidth())
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    items(7) { index ->
                                        FilterChip(enabled = !isSaving, selected = draft.weekday == index + 1, onClick = { update(entry.copy(draft = draft.copy(weekday = index + 1))) }, label = { Text("周${"一二三四五六日"[index]}") })
                                    }
                                }
                                ImportTimeFields(entry.startText, entry.endText,
                                    { update(entry.copy(startText = it)) }, { update(entry.copy(endText = it)) }, "开始 HH:mm", "结束 HH:mm", showErrors = true, enabled = !isSaving)
                                TextButton(enabled = !isSaving, onClick = { entries = entries.filterNot { it.key == entry.key } }) { Text("移除这条") }
                            } else if (draft.location.isNotBlank()) {
                                Text(draft.location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (draft.reviewNote.isNotBlank()) {
                                if (entry.expanded) Text(draft.reviewNote, style = MaterialTheme.typography.bodySmall)
                                if (entry.copy(reviewed = true).validated() != null) {
                                    ImportReviewRow(entry.reviewed, { update(entry.copy(reviewed = it)) }, "已核对课程、星期与时间", enabled = !isSaving)
                                }
                            }
                            if (entry.expanded && entry.copy(reviewed = true).validated() == null) Text("请填写课程名和有效时间，结束须晚于开始。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (entries.size > 1 && entries.any { it.draft.reviewNote.isNotBlank() }) item {
                    ImportReviewRow(entries.all { it.reviewed }, { checked -> entries = entries.map { it.copy(reviewed = checked) } },
                        "已核对以上全部课程", enabled = !isSaving && !periodTimesDirty && entries.all { it.copy(reviewed = true).validated() != null })
                }
            }
            if (periodTimesDirty) Text("作息有未应用的修改，请核对并应用后再导入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (saveError != null) Text(saveError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = !isSaving, onClick = onDismiss) { Text("取消") }
                TextButton(enabled = !isSaving && !periodTimesDirty && entries.isNotEmpty() && entries.all { it.validated() != null }, onClick = { if (!isSaving) onConfirm(entries.mapNotNull { it.validated() }) }) { Text(if (isSaving) "正在保存…" else if (saveError != null) "重试导入" else "确认导入") }
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

@Composable
private fun ImportReviewRow(checked: Boolean, onChange: (Boolean) -> Unit, label: String, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ImportTimeFields(start: String, end: String, onStart: (String) -> Unit, onEnd: (String) -> Unit,
    startLabel: String, endLabel: String, showErrors: Boolean = false, enabled: Boolean = true) {
    val stacked = LocalDensity.current.fontScale >= 1.3f || LocalConfiguration.current.screenWidthDp < 360
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    @Composable fun field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
        SpectraTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier, enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); keyboard?.hide() }),
            isError = showErrors && parseClockOrNull(value) == null)
    }
    if (stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        field(start, onStart, startLabel, Modifier.fillMaxWidth())
        field(end, onEnd, endLabel, Modifier.fillMaxWidth())
    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        field(start, onStart, startLabel, Modifier.weight(1f))
        field(end, onEnd, endLabel, Modifier.weight(1f))
    }
}

// An explicitly labeled example only. OCR drafts keep unresolved times until the user applies it.
private val examplePeriodTimes = listOf(
    "08:00" to "08:45", "08:55" to "09:40", "10:00" to "10:45", "10:55" to "11:40",
    "11:50" to "12:35", "14:00" to "14:45", "14:55" to "15:40", "15:50" to "16:35",
    "16:45" to "17:30", "19:00" to "19:45", "19:55" to "20:40", "20:50" to "21:35",
)
