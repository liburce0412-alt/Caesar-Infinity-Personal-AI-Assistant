package com.campusai.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.campusai.core.designsystem.CampusTheme
import com.campusai.core.model.ThemeMode
import com.campusai.features.community.CommunityPost
import com.campusai.features.community.MarketplaceListing
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class ExperienceUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `existing public post can be hidden and saved without scrolling to footer`() {
        var saved: Boolean? = null
        compose.setContent {
            CampusTheme(ThemeMode.LIGHT) {
                PostComposer(false, {}, { _, _, _, _, visible, _ -> saved = visible },
                    initial = CommunityPost("post", "self", "我", "", "已经发表的话", "", "", false, 0, false, 0, "", true))
            }
        }
        compose.onNodeWithText("分享给其他人").performClick()
        compose.onNodeWithText("保存").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(false, saved) }
    }

    @Test fun `existing private wish can be shared in dark editor`() {
        var saved: Boolean? = null
        compose.setContent {
            CampusTheme(ThemeMode.DARK) {
                ListingComposer(false, {}, { _, _, _, _, _, visible, _, _ -> saved = visible },
                    initial = MarketplaceListing("wish", "self", "我", "想去看日出", "", null, "", "", "active", "pending", "", false))
            }
        }
        compose.onNodeWithText("分享给其他人").performClick()
        compose.onNodeWithText("保存").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, saved) }
    }

    @Test fun `collapsed component restores its content with one tap`() {
        compose.setContent {
            var collapsed by remember { mutableStateOf(true) }
            CampusTheme(ThemeMode.LIGHT) {
                CollapsibleComponent(OptionalComponent.HEALTH, collapsed, { collapsed = false }) {
                    androidx.compose.material3.Text("健康详情")
                }
            }
        }
        compose.onNodeWithText("健康详情").assertDoesNotExist()
        compose.onNodeWithText("展开").performClick()
        compose.onNodeWithText("健康详情").assertIsDisplayed()
    }

    @Test fun `guide can move forward backward and skip without waiting for animation`() {
        var finished = false
        compose.setContent { CampusTheme(ThemeMode.LIGHT) { WelcomeGuide { finished = true } } }
        compose.onNodeWithText("继续").performClick()
        compose.onNodeWithText("界面，由你做减法").assertExists()
        compose.onNodeWithText("上一步").performClick()
        compose.onNodeWithText("给今天，留一点从容").assertExists()
        compose.onNodeWithText("跳过引导").performClick()
        compose.runOnIdle { assertTrue(finished) }
    }
}
