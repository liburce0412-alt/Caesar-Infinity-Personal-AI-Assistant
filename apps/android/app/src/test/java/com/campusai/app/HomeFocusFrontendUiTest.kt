package com.campusai.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.campusai.core.designsystem.CampusTheme
import com.campusai.core.model.ThemeMode
import com.campusai.core.model.UiState
import com.campusai.core.model.TimeRecord
import com.campusai.features.ai.CaesarHealthUiState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeFocusFrontendUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `home offers readable primary action and health details`() = home(ThemeMode.LIGHT)
    @Test @Config(qualifiers = "w320dp-h851dp-xxhdpi")
    fun `home supports small screen and double text`() = home(ThemeMode.DARK, 2f)

    private fun home(theme: ThemeMode, fontScale: Float = 1f) {
        var started = false
        compose.setContent { CampusTheme(theme) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    HomeScreen(records = emptyList(), displayName = "同学", avatarUrl = "", dailyText = "先完成一件最重要的小事",
                        announcements = UiState.Empty, onRefreshAnnouncements = {}, onStartRecord = { started = true }, onOpenAi = {},
                        healthState = CaesarHealthUiState(), onRefreshHealth = {}, onSyncMiFitnessSteps = {}, contentPadding = PaddingValues(0.dp))
                }
            }
        } }
        compose.onRoot().captureRoboImage("../../../artifacts/frontend-home-${theme.name.lowercase()}-$fontScale.png")
        compose.onNodeWithText("开始记录").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(started) }
        compose.onNodeWithContentDescription("查看健康数据详情").performScrollTo().performClick()
        compose.onNodeWithText("本机健康记录").assertExists()
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xxhdpi")
    fun `focus controls remain reachable with large text on short window`() {
        compose.setContent { CampusTheme(ThemeMode.DARK) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    FocusSessionScreen(50, false, false, {}, {})
                }
            }
        } }
        compose.onNodeWithText("暂停").performScrollTo().performClick()
        compose.onNodeWithText("继续").assertIsDisplayed()
        compose.onNodeWithText("结束专注").performScrollTo().assertIsDisplayed()
        compose.onRoot().captureRoboImage("../../../artifacts/frontend-focus-large-dark.png")
        compose.onNodeWithText("结束专注").performClick()
        compose.onNodeWithText("尚未满一分钟，退出将不生成时间记录。").assertExists()
        compose.onNodeWithText("继续专注").performScrollTo().performClick()
        compose.onNodeWithText("结束本次专注？").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xxhdpi")
    fun `time editor keeps save reachable with large text`() {
        var saved = false
        compose.setContent { CampusTheme(ThemeMode.LIGHT) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                AddTimeRecordDialog(null, {}, { _, _, _, _ -> saved = true })
            }
        } }
        compose.onNodeWithText("做了什么").performScrollTo().performTextInput("阅读论文")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(saved) }
    }

    @Test fun `editing a short focus record preserves its exact duration`() = preserveDuration(1L)
    @Test fun `editing a long record preserves its exact duration`() = preserveDuration(300L)

    private fun preserveDuration(minutes: Long) {
        var savedMinutes = -1L
        val record = TimeRecord(1, "阅读", "学习", 0L, minutes * 60_000L, minutes, "")
        compose.setContent { CampusTheme(ThemeMode.LIGHT) {
            AddTimeRecordDialog(record, {}, { _, _, duration, _ -> savedMinutes = duration })
        } }
        compose.onNodeWithText("做了什么").performTextReplacement("阅读笔记")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(minutes, savedMinutes) }
    }
}
