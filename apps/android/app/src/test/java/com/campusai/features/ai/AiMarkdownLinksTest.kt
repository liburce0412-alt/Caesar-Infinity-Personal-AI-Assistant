package com.campusai.features.ai

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
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
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiMarkdownLinksTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `selectable markdown exposes real link actions and opens only the clicked URL`() {
        val opened = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened += uri }
            }) {
                CampusTheme(ThemeMode.LIGHT) {
                    AiMarkdownText("[Android Developers](https://developer.android.com/) 和 [指南](https://developer.android.com/guide)")
                }
            }
        }
        compose.runOnIdle { assertEquals(emptyList<String>(), opened) }
        val links = compose.onAllNodes(hasClickAction(), useUnmergedTree = true)
        links.assertCountEquals(2)
        links[0].assertIsDisplayed().performClick()
        links[1].assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf("https://developer.android.com/", "https://developer.android.com/guide"), opened)
        }
    }

    @Test fun `stream completion creates a clickable link and unsafe replacement removes it`() {
        val opened = mutableListOf<String>()
        val markdown = mutableStateOf("查看 [文档](https://developer.android.com")
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened += uri }
            }) {
                CampusTheme(ThemeMode.LIGHT) { AiMarkdownText(markdown.value) }
            }
        }
        compose.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { markdown.value += "/)" }
        compose.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(hasClickAction(), useUnmergedTree = true).performTouchInput { click() }
        compose.runOnIdle { markdown.value = "查看 [文档](javascript:alert)" }
        compose.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("查看 文档").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("https://developer.android.com/"), opened) }
    }
}
