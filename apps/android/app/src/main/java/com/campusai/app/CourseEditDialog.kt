package com.campusai.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.campusai.core.designsystem.SpectraDialog
import com.campusai.core.model.CourseSchedule
import com.campusai.features.schedule.courseClock
import com.campusai.features.schedule.parseCourseClock
import com.campusai.features.schedule.validateCourseEdit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CourseEditDialog(course: CourseSchedule, onDismiss: () -> Unit, onSave: suspend (CourseSchedule) -> Unit) {
    var name by rememberSaveable(course.id) { mutableStateOf(course.name) }
    var location by rememberSaveable(course.id) { mutableStateOf(course.location) }
    var teacher by rememberSaveable(course.id) { mutableStateOf(course.teacher) }
    var weeks by rememberSaveable(course.id) { mutableStateOf(course.weeks) }
    var weekday by rememberSaveable(course.id) { mutableStateOf(course.weekday.toString()) }
    var first by rememberSaveable(course.id) { mutableStateOf(course.periodStart.takeIf { it > 0 }?.toString().orEmpty()) }
    var last by rememberSaveable(course.id) { mutableStateOf(course.periodEnd.takeIf { it > 0 }?.toString().orEmpty()) }
    var start by rememberSaveable(course.id) { mutableStateOf(if (course.startMinute >= 0) courseClock(course.startMinute) else "") }
    var end by rememberSaveable(course.id) { mutableStateOf(if (course.endMinute >= 0) courseClock(course.endMinute) else "") }
    var axis by rememberSaveable(course.id) { mutableStateOf(course.periodStartTimes.split(',').map { it.toIntOrNull() }.joinToString(", ") { if (it != null && it >= 0) courseClock(it) else "" }) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    SpectraDialog(onDismissRequest = { if (!saving) onDismiss() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("编辑课程", style = MaterialTheme.typography.headlineSmall)
            Text("教室和教师可以稍后补充，保存后立即更新课表。", style = MaterialTheme.typography.bodySmall)
            @Composable fun field(label: String, value: String, change: (String) -> Unit) {
                OutlinedTextField(value, change, label = { Text(label) }, enabled = !saving, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            field("课程名称", name) { name = it }
            field("校区 / 教学楼 / 教室 / 位置（可选）", location) { location = it }
            field("任课教师（可选）", teacher) { teacher = it }
            field("周次（例如 第5周 或 1–16周）", weeks) { weeks = it }
            field("星期（1 至 7，7 为周日）", weekday) { weekday = it }
            field("开始节次（按时间上课可留空）", first) { first = it }
            field("结束节次", last) { last = it }
            field("开始时间 HH:mm（按节次可留空）", start) { start = it }
            field("结束时间 HH:mm", end) { end = it }
            field("各节上课时间（逗号分隔，可选）", axis) { axis = it }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onDismiss, enabled = !saving) { Text("取消") }
                Button(enabled = !saving, onClick = {
                    error = null
                    val updated = try {
                        course.copy(name = name.trim(), location = location.trim(), teacher = teacher.trim(), weeks = weeks.trim(),
                            weekday = weekday.toIntOrNull() ?: 0,
                            periodStart = if (first.isBlank()) 0 else first.toIntOrNull() ?: -1,
                            periodEnd = if (last.isBlank()) 0 else last.toIntOrNull() ?: -1,
                            startMinute = parseCourseClock(start), endMinute = parseCourseClock(end),
                            periodStartTimes = if (axis.isBlank()) "" else axis.replace('，', ',').split(',').joinToString(",") { parseCourseClock(it).toString() },
                        ).also { validateCourseEdit(it) }
                    } catch (failure: IllegalArgumentException) { error = failure.message; null }
                    catch (failure: IllegalStateException) { error = failure.message; null }
                    if (updated != null) {
                        saving = true
                        scope.launch {
                            try { onSave(updated) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = "保存失败，修改已保留：${failure.message ?: "请重试"}" }
                            finally { saving = false }
                        }
                    }
                }) { Text(if (saving) "保存中…" else "保存") }
            }
        }
    }
}
