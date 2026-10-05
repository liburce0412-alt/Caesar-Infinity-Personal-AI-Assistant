package com.campusai.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.campusai.core.designsystem.CampusTheme
import com.campusai.core.model.CourseSchedule
import com.campusai.core.model.ThemeMode
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
class CourseEditUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `room and teacher can be added after screenshot import`() {
        val original = CourseSchedule(id = 7, name = "Java 程序设计", weekday = 3, startMinute = -1, endMinute = -1,
            sourceHash = "original", periodStart = 1, periodEnd = 2, weeks = "第5周（截图）")
        var saved: CourseSchedule? = null
        compose.setContent { CampusTheme(ThemeMode.LIGHT) { CourseEditDialog(original, {}, { saved = it }) } }
        compose.onNode(hasSetTextAction() and hasText("校区 / 教学楼 / 教室 / 位置（可选）")).performTextInput("三教 3206")
        compose.onNode(hasSetTextAction() and hasText("任课教师（可选）")).performScrollTo().performTextInput("王老师")
        compose.onRoot().captureRoboImage("../../../artifacts/update-v2.1.1/course-editor-demo.png")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.waitUntil { saved != null }
        assertEquals(original.copy(location = "三教 3206", teacher = "王老师"), saved)
    }

    @Test fun `achievement icons have varied recognizable artwork`() {
        compose.setContent { CampusTheme(ThemeMode.LIGHT) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("成就徽章 · 演示")
                buildAchievements(emptyList(), 0).chunked(4).forEach { group ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        group.forEach { item -> Column { AchievementBadge(item.copy(progress = item.target), Modifier.size(58.dp)); Text(item.name) } }
                    }
                }
            }
        } }
        compose.onRoot().captureRoboImage("../../../artifacts/update-v2.1.1/achievement-icons-demo.png")
        compose.onNodeWithText("光之收藏家").assertIsDisplayed()
    }
}
