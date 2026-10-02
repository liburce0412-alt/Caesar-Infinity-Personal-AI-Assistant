package com.campusai.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.campusai.core.designsystem.CampusTheme
import com.campusai.core.model.ThemeMode
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
class TimeRecordSaveUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `failed save retains manual input and pending save disables changes and duplicate submit`() {
        var saving by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var requests = 0
        var savedTitle = ""
        var savedNote = ""
        compose.setContent { CampusTheme(ThemeMode.LIGHT) {
            AddTimeRecordDialog(null, {}, { title, _, _, note ->
                requests++; savedTitle = title; savedNote = note; saving = true
            }, saving = saving, saveError = error)
        } }
        compose.onNodeWithText("做了什么").performTextInput("阅读笔记")
        compose.onNodeWithText("描述（可选）").performScrollTo().performTextInput("第三章")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.onNodeWithText("正在保存…").assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsNotEnabled()
        compose.onNodeWithText("做了什么").assertIsNotEnabled()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, requests); saving = false; error = "保存失败，已保留你的修改" }
        compose.onNodeWithText("做了什么").assertTextContains("阅读笔记")
        compose.onNodeWithText("描述（可选）").assertTextContains("第三章")
        compose.onNodeWithText("重试保存").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, requests); assertEquals("阅读笔记", savedTitle); assertEquals("第三章", savedNote) }
    }
}
