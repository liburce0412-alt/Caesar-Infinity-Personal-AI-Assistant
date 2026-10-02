package com.campusai.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.campusai.core.designsystem.CampusTheme
import com.campusai.core.model.CourseSchedule
import com.campusai.core.model.ThemeMode
import com.campusai.features.schedule.CourseDraft
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CourseTimetableUiTest {
    @get:Rule val compose = createComposeRule()
    private val courses = listOf(
        CourseSchedule(name = "高等数学", weekday = 1, startMinute = 480, endMinute = 580, location = "教学楼 A201", teacher = "王老师", weeks = "1–16 周（单周）", sourceHash = "math"),
        CourseSchedule(name = "大学英语", weekday = 2, startMinute = 540, endMinute = 640, location = "外语楼 302", weeks = "1–16 周", sourceHash = "english"),
        CourseSchedule(name = "程序设计实验", weekday = 3, startMinute = 600, endMinute = 720, location = "实验楼 405", sourceHash = "coding"),
        CourseSchedule(name = "体育", weekday = 7, startMinute = 840, endMinute = 940, location = "体育馆", sourceHash = "sport"),
    )

    private fun show(theme: ThemeMode = ThemeMode.LIGHT, fontScale: Float = 1f) {
        compose.setContent {
            CampusTheme(theme) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                        CourseTimetable(courses) {}
                    }
                }
            }
        }
    }

    @Test fun `weekly grid opens complete course details`() {
        show()
        compose.onRoot().captureRoboImage("../../../artifacts/timetable-light.png")
        compose.onNodeWithContentDescription("周一，高等数学，08:00至09:40，教学楼 A201").performClick()
        compose.onNodeWithText("教师：王老师").assertIsDisplayed()
        compose.onNodeWithText("周次：1–16 周（单周）").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("教师：王老师").assertDoesNotExist()
        compose.onNodeWithText("周末 1 门").performClick()
        compose.onNodeWithText("周日").performScrollTo()
        compose.onNodeWithContentDescription("周日，体育，14:00至15:40，体育馆").performScrollTo().performClick()
        compose.onNodeWithText("教室：体育馆").assertIsDisplayed()
    }

    @Test fun `day selection includes weekends and empty days`() {
        show(ThemeMode.DARK)
        compose.onNodeWithText("按天查看").performClick()
        compose.onNodeWithText("周一").performClick()
        compose.onNodeWithText("高等数学").assertIsDisplayed()
        compose.onRoot().captureRoboImage("../../../artifacts/timetable-day-dark.png")
        compose.onNodeWithText("周日").performScrollTo().performClick()
        compose.onNodeWithText("体育").assertIsDisplayed()
        compose.onNodeWithText("周六").performScrollTo().performClick()
        compose.onNodeWithText("周六没有课程安排").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w320dp-h851dp-xxhdpi")
    fun `large text grid still exposes complete details`() {
        show(ThemeMode.DARK, fontScale = 2f)
        compose.onNodeWithText("周一").performClick()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("08:00").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        compose.onRoot().captureRoboImage("../../../artifacts/timetable-large-text.png")
        assertEquals(1, layouts.single().lineCount)
        assertFalse("Time label bounds: ${layouts.single().size}, width overflow: ${layouts.single().didOverflowWidth}, height overflow: ${layouts.single().didOverflowHeight}", layouts.single().hasVisualOverflow)
        compose.onNodeWithText("高等数学").performClick()
        compose.onNodeWithText("教师：王老师").assertExists()
    }

    @Test fun `course can be removed after explicit confirmation`() {
        var removed: CourseSchedule? = null
        compose.setContent { CampusTheme(ThemeMode.LIGHT) {
            CourseTimetable(courses, onRemove = { removed = it }, onImport = {})
        } }
        compose.onNodeWithContentDescription("周一，高等数学，08:00至09:40，教学楼 A201").performClick()
        compose.onNodeWithText("删除课程").performClick()
        compose.runOnIdle { assertNull(removed) }
        compose.onNodeWithText("确认删除课程").performClick()
        compose.runOnIdle { assertEquals(courses.first(), removed) }
    }

    @Test fun `weekdays fit on phone and overlaps do not widen a column`() {
        val sample = (1..5).flatMap { day ->
            listOf(
                CourseSchedule(id = day, name = "软件工程", weekday = day, startMinute = 480, endMinute = 580, location = "三教3206", sourceHash = "s$day"),
                CourseSchedule(id = day + 10, name = "数据库开发", weekday = day, startMinute = 840, endMinute = 940, location = "实训楼S402", sourceHash = "a$day"),
            )
        } + CourseSchedule(id = 40, name = "概率论", weekday = 1, startMinute = 480, endMinute = 580, sourceHash = "conflict")
        compose.setContent { CampusTheme(ThemeMode.LIGHT) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) { CourseTimetable(sample) {} }
        } }
        compose.onNodeWithText("周一").assertIsDisplayed()
        compose.onNodeWithText("周五").assertIsDisplayed()
        compose.onRoot().captureRoboImage("../../../artifacts/timetable-compact-week.png")
        compose.onNodeWithContentDescription("周一，2 门重叠安排，点击查看").performClick()
        compose.onNodeWithText("重叠的课程安排").assertIsDisplayed()
        compose.onNode(hasText("概率论") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("周次：未填写，请核对原始课表").assertIsDisplayed()
    }

    @Test fun `bulk cleanup requires selection and second confirmation`() {
        val removed = mutableListOf<CourseSchedule>()
        val sample = courses.mapIndexed { index, course -> course.copy(id = index + 1) }
        compose.setContent { CampusTheme(ThemeMode.LIGHT) { CourseTimetable(sample, onRemove = { removed.add(it) }, onImport = {}) } }
        compose.onNodeWithText("管理课程").performClick()
        compose.onNodeWithText("删除选中 (0)").assertIsNotEnabled()
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("删除选中 (4)").performClick()
        compose.runOnIdle { assertTrue(removed.isEmpty()) }
        compose.onNodeWithText("确认删除 4 门").performClick()
        compose.runOnIdle { assertEquals(sample, removed) }
    }

    @Test fun `calendar import requires review and missing OCR times stay empty`() {
        var saved = false
        compose.setContent { CampusTheme(ThemeMode.LIGHT) {
            SchedulePreviewDialog(listOf(CourseDraft("数学", 1, -1, -1,
                weeks = "DTSTART:20260922T080000", reviewNote = "未识别到时间轴，请核对。")), {}, { saved = true })
        } }
        compose.onNodeWithText("确认导入").assertIsNotEnabled()
        compose.onNodeWithText("开始 HH:mm").performScrollTo().performTextReplacement("08:30")
        compose.onNodeWithText("结束 HH:mm").performTextReplacement("09:20")
        compose.onNodeWithText("确认导入").assertIsNotEnabled()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onRoot().captureRoboImage("../../../artifacts/timetable-import-review.png")
        compose.onNodeWithText("确认导入").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(saved) }
    }

    @Test fun `invalid visible time cannot import an older valid value`() {
        var saved: List<CourseDraft>? = null
        compose.setContent { CampusTheme(ThemeMode.LIGHT) {
            SchedulePreviewDialog(listOf(CourseDraft("数学", 1, 480, 580)), {}, { saved = it })
        } }
        compose.onNodeWithText("开始 HH:mm").performScrollTo().performTextReplacement("oops")
        compose.onNodeWithText("确认导入").assertIsNotEnabled()
        compose.onNodeWithText("开始 HH:mm").performTextReplacement("10:00")
        compose.onNodeWithText("确认导入").assertIsNotEnabled()
        compose.onNodeWithText("开始 HH:mm").performTextReplacement("08:30")
        compose.onNodeWithText("确认导入").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(510, saved!!.single().startMinute) }
    }

    @Test fun `eleven screenshot shaped courses present a compact weekday overview`() {
        // Example clock times only: the source screenshot supplies periods, not a school timetable.
        val input = listOf(
            Triple(1, 480 to 580, "数据库开发…"), Triple(1, 840 to 940, "Java 程…"), Triple(1, 950 to 1050, "体育3"),
            Triple(2, 600 to 700, "英语视听说"), Triple(2, 840 to 995, "数据库系统…"), Triple(2, 1140 to 1295, "Pytho…"),
            Triple(3, 480 to 580, "Java 程…"), Triple(4, 480 to 580, "软件工程经…"),
            Triple(4, 840 to 940, "软件工程"), Triple(4, 950 to 1050, "会计学"), Triple(5, 840 to 995, "概率论"),
        ).mapIndexed { i, (day, time, name) -> CourseSchedule(id = i + 1, name = name, weekday = day,
            startMinute = time.first, endMinute = time.second, location = "三教3206", weeks = "请核对原图授课周次", sourceHash = "fixture-$i") }
        compose.setContent { CampusTheme(ThemeMode.LIGHT) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                androidx.compose.material3.Text("截图课程预览 · 示例作息", style = MaterialTheme.typography.bodySmall)
                CourseTimetable(input) {}
            }
        } }
        compose.onNodeWithText("周五").assertIsDisplayed()
        compose.onRoot().captureRoboImage("../../../artifacts/timetable-screenshot-preview.png")
    }
}
