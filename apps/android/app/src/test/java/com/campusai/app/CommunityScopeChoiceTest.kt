package com.campusai.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.campusai.core.designsystem.CampusTheme
import com.campusai.core.model.ThemeMode
import com.campusai.features.community.CommunityFeedScope
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommunityScopeChoiceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `scope control exposes and switches selected state in both directions`() {
        compose.setContent {
            var scope by remember { mutableStateOf(CommunityFeedScope.MINE) }
            CampusTheme(ThemeMode.LIGHT) { CommunityScopeChoice(scope, { scope = it }) }
        }
        compose.onNodeWithText("只看我的").assertIsSelected()
        compose.onNodeWithText("公开广场").performClick().assertIsSelected()
        compose.onNodeWithText("看看大家分享的公开内容").assertExists()
        compose.onNodeWithText("只看我的").performClick().assertIsSelected()
    }

    @Test fun `scope selection requires sign in`() {
        compose.setContent { CampusTheme(ThemeMode.LIGHT) { CommunityScopeChoice(CommunityFeedScope.MINE, {}, enabled = false) } }
        compose.onNodeWithText("公开广场").assertIsNotEnabled()
    }
}
