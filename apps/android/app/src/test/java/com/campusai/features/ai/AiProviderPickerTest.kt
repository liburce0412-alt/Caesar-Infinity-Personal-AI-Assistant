package com.campusai.features.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.campusai.core.designsystem.CampusTheme
import com.campusai.core.model.AiProvider
import com.campusai.core.model.AiMode
import com.campusai.core.model.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiProviderPickerTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `full provider names remain selectable in light theme`() = checkPicker(ThemeMode.LIGHT, 1f)
    @Test fun `large text dark picker scrolls to the last provider`() = checkPicker(ThemeMode.DARK, 1.5f)

    @Test fun `free cloud displays fast model with no deep entry even for old deep state`() {
        compose.setContent {
            CampusTheme(ThemeMode.LIGHT) {
                Column {
                    AiCloudAnswerMode(AiProvider.CAESAR_CLOUD_FREE, AiMode.DEEP,
                        enabled = true, motionEnabled = false, onSelect = {})
                }
            }
        }
        compose.onNodeWithText("快速回答 · GLM-4-Flash-250414").assertIsDisplayed()
        compose.onNodeWithText("深度").assertDoesNotExist()
    }

    @Test fun `personal cloud keeps its selectable deep mode`() {
        val mode = mutableStateOf(AiMode.FAST)
        compose.setContent {
            CampusTheme(ThemeMode.LIGHT) {
                Column {
                    AiCloudAnswerMode(AiProvider.DEEPSEEK, mode.value,
                        enabled = true, motionEnabled = false, onSelect = { mode.value = it })
                }
            }
        }
        compose.onNodeWithText("深度").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(AiMode.DEEP, mode.value) }
    }

    private fun checkPicker(theme: ThemeMode, scale: Float) {
        val selected = mutableStateOf(AiProvider.CAESAR_CLOUD_FREE)
        val previous = System.getProperty("roborazzi.test.record")
        System.setProperty("roborazzi.test.record", "true")
        try {
            RuntimeEnvironment.setFontScale(scale)
            compose.setContent {
                CampusTheme(theme) {
                    Surface {
                        Column(Modifier.fillMaxSize().padding(20.dp)) {
                            AiProviderPicker(selected.value, { selected.value = it })
                        }
                    }
                }
            }
            compose.onNodeWithText("Caesar cloud free").assertIsDisplayed().performClick()
            compose.onNode(hasText("Caesar cloud free") and isSelectable()).assertIsDisplayed().assertIsSelected()
            val output = File("../../../artifacts/cloud-free-picker-${theme.name.lowercase()}.png")
            output.parentFile?.mkdirs()
            compose.onNodeWithText("选择 AI").onParent().captureRoboImage(output.absolutePath)
            compose.onNodeWithText("Google Gemini").performScrollTo().performClick()
            compose.onNodeWithText("选择 AI").assertDoesNotExist()
            compose.onNodeWithText("Google Gemini").assertIsDisplayed()
        } finally {
            RuntimeEnvironment.setFontScale(1f)
            if (previous == null) System.clearProperty("roborazzi.test.record")
            else System.setProperty("roborazzi.test.record", previous)
        }
    }
}
