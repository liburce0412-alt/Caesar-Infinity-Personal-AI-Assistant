package com.campusai.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.campusai.core.designsystem.CampusTheme
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
// Compose 1.7's prefetch budget uses System.nanoTime against Android Choreographer time.
// Instrument that package so both clocks use Robolectric time when scrolling a real dialog.
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi", instrumentedPackages = ["androidx.compose.foundation.lazy.layout"])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScheduleImportPreviewUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `period times require explicit school confirmation and course review`() {
        var saved: List<CourseDraft>? = null
        compose.setContent { CampusTheme(ThemeMode.LIGHT) {
            SchedulePreviewDialog(listOf(CourseDraft("数据库开发…", 1, -1, -1, location = "麦庐园校区数字经济实训大楼…",
                reviewNote = "原图文字已截断，请补全课程名与教室。", periodStart = 1, periodEnd = 2)), {}, { saved = it })
        } }
        compose.onNodeWithText("确认导入").assertIsNotEnabled()
        compose.onNodeWithText("设置节次时间").performClick()
        compose.onNodeWithText("填入示例作息（需核对）").performScrollTo().performClick()
        compose.onNodeWithText("应用已核对的作息").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("学校作息已核对").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText("应用已核对的作息").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("确认导入").assertIsNotEnabled()
        compose.onNodeWithText("已核对课程、星期与时间").performScrollTo()
        compose.onNodeWithText("已核对课程、星期与时间").performClick().assertIsOn()
        compose.onRoot().captureRoboImage("../../../artifacts/timetable-period-import.png")
        compose.onNodeWithText("确认导入").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(480, saved!!.single().startMinute)
            assertEquals(580, saved!!.single().endMinute)
            assertEquals("数据库开发…", saved!!.single().name)
        }
    }

    @Test fun `different courses share one period configuration`() {
        compose.setContent { CampusTheme(ThemeMode.DARK) {
            SchedulePreviewDialog(listOf(
                CourseDraft("数学", 1, -1, -1, periodStart = 1, periodEnd = 2),
                CourseDraft("英语", 2, -1, -1, periodStart = 1, periodEnd = 2),
            ), {}, {})
        } }
        compose.onNodeWithText("设置节次时间").performClick()
        compose.onAllNodesWithText("第1节开始").assertCountEquals(1)
        compose.onAllNodesWithText("第2节结束").assertCountEquals(1)
        compose.onNodeWithText("填入示例作息（需核对）").performClick()
        compose.onNodeWithText("第2节开始").performScrollTo().performTextReplacement("08:20")
        compose.onNodeWithText("学校作息已核对").performScrollTo()
        compose.onNodeWithText("学校作息已核对").performClick()
        compose.onNodeWithText("应用已核对的作息").performScrollTo().assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "w320dp-h851dp-xxhdpi")
    fun `large text period editor remains scrollable with full width fields`() {
        compose.setContent { CampusTheme(ThemeMode.DARK) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                SchedulePreviewDialog(listOf(CourseDraft("数学", 1, -1, -1, periodStart = 1, periodEnd = 2)), {}, {})
            }
        } }
        compose.onNodeWithText("设置节次时间").performScrollTo().performClick()
        compose.onNodeWithText("第1节开始").performScrollTo().performTextReplacement("08:00")
        compose.onNodeWithText("第1节结束").performScrollTo().performTextReplacement("08:45")
        compose.onNodeWithText("第1节结束").performImeAction()
        compose.onNodeWithText("第1节结束").assertIsNotFocused().assertTextContains("08:45")
        compose.onRoot().captureRoboImage("../../../artifacts/timetable-period-large-text.png")
        compose.onNodeWithTag("schedule-import-list").performScrollToNode(hasText("应用已核对的作息"))
        compose.onNodeWithText("应用已核对的作息").performScrollTo().assertExists().assertIsNotEnabled()
    }

    @Test fun `editing a course revokes its batch confirmation`() {
        compose.setContent { CampusTheme(ThemeMode.LIGHT) {
            SchedulePreviewDialog(listOf(
                CourseDraft("数学", 1, 480, 580, reviewNote = "请核对课程"),
                CourseDraft("英语", 2, 480, 580, reviewNote = "请核对课程"),
            ), {}, {})
        } }
        compose.onNodeWithText("已核对以上全部课程").performScrollTo().performClick()
        compose.onNodeWithText("确认导入").assertIsEnabled()
        compose.onNodeWithContentDescription("编辑数学").performScrollTo().performClick()
        compose.onNodeWithText("课程名").performScrollTo().performTextReplacement("高等数学")
        compose.onNodeWithText("确认导入").assertIsNotEnabled()
    }

    @Test fun `save pending prevents duplicates and a failure preserves edits for retry`() {
        var saving by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var requests = 0
        var savedName = ""
        compose.setContent { CampusTheme(ThemeMode.LIGHT) {
            SchedulePreviewDialog(listOf(CourseDraft("数学", 1, 480, 580)), {}, {
                requests++; savedName = it.single().name; saving = true
            }, isSaving = saving, saveError = error)
        } }
        compose.onNodeWithText("课程名").performScrollTo().performTextReplacement("高等数学")
        compose.onNodeWithText("确认导入").performClick()
        compose.onNodeWithText("正在保存…").assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsNotEnabled()
        compose.onNodeWithText("课程名").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, requests); saving = false; error = "保存失败，已保留你的修改" }
        compose.onNodeWithText("保存失败，已保留你的修改").assertExists()
        compose.onNodeWithText("课程名").assertTextContains("高等数学")
        compose.onNodeWithText("重试导入").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, requests); assertEquals("高等数学", savedName) }
    }

    @Test fun `changing an applied school timetable blocks saving stale course times`() {
        var saved: List<CourseDraft>? = null
        compose.setContent { CampusTheme(ThemeMode.LIGHT) {
            SchedulePreviewDialog(listOf(CourseDraft("数学", 1, 480, 580, periodStart = 1, periodEnd = 2)), {}, { saved = it })
        } }
        compose.onNodeWithText("设置节次时间").performClick()
        compose.onNodeWithText("填入示例作息（需核对）").performScrollTo().performClick()
        compose.onNodeWithText("学校作息已核对").performScrollTo().performClick()
        compose.onNodeWithText("应用已核对的作息").performScrollTo().performClick()
        compose.onNodeWithText("确认导入").assertIsEnabled()
        compose.onNodeWithText("设置节次时间").performScrollTo().performClick()
        compose.onNodeWithText("第1节开始").performScrollTo().performTextReplacement("08:10")
        compose.onNodeWithText("第1节开始").performImeAction()
        compose.onNodeWithText("确认导入").assertIsNotEnabled()
        compose.onNodeWithText("作息有未应用的修改，请核对并应用后再导入。").assertExists()
        compose.onNodeWithText("学校作息已核对").performScrollTo().performClick()
        compose.onNodeWithText("应用已核对的作息").performScrollTo().performClick()
        compose.onNodeWithText("确认导入").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(490, saved!!.single().startMinute) }
    }
}
