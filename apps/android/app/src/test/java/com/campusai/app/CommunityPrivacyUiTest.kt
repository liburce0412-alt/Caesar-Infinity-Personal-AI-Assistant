package com.campusai.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import com.campusai.core.designsystem.SpectraBackdrop
import com.campusai.core.model.SpectraEnvironment
import com.campusai.core.model.MotionMode
import com.campusai.core.model.RenderQuality
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.campusai.core.designsystem.CampusTheme
import com.campusai.core.model.ThemeMode
import com.campusai.features.community.MarketplaceListing
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
class CommunityPrivacyUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `wish can be saved without a price and starts private`() {
        var saved = false
        compose.setContent {
            CampusTheme(ThemeMode.LIGHT) {
                ListingComposer(false, {}, { _, _, price, _, image, isPublic, _, _ ->
                    assertNull(price)
                    assertNull(image)
                    assertFalse(isPublic)
                    saved = true
                })
            }
        }
        compose.onNodeWithText("心愿标题").performTextInput("周末去看一场日出")
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(saved) }
    }

    @Test fun `tree hole only becomes public through opt in`() {
        var publiclySaved = false
        compose.setContent {
            CampusTheme(ThemeMode.LIGHT) {
                PostComposer(false, {}, { _, _, anonymous, _, isPublic, _ ->
                    assertFalse(anonymous)
                    publiclySaved = isPublic
                })
            }
        }
        compose.onNodeWithText("保存").assertExists()
        compose.onNodeWithText("今天有什么想被记住？").performTextInput("今天走过了很长的一段路。")
        compose.onNodeWithText("分享给其他人").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertTrue(publiclySaved) }
    }

    @Test fun `unillustrated wishes render text with no fake image or zero price`() = textCard(ThemeMode.LIGHT)
    @Test fun `text card remains readable in dark mode`() = textCard(ThemeMode.DARK)
    @Test fun `text card wraps at double font scale`() = textCard(ThemeMode.LIGHT, 2f)

    private fun textCard(theme: ThemeMode, fontScale: Float = 1f) {
        var opened = false
        compose.setContent {
            CampusTheme(theme) {
                Box(Modifier.fillMaxSize()) {
                SpectraBackdrop(SpectraEnvironment.ORIGINAL, RenderQuality.LOW, MotionMode.OFF)
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                Column(Modifier.padding(20.dp)) {
                    VisibilityChoice(false, {})
                    ListingCardView(MarketplaceListing(
                        id = "sample", sellerId = "self", seller = "我",
                        title = "周末，想去看一场日出",
                        description = "不赶时间，带一本书。\n想把普通的一天，过得特别一点。",
                        priceCents = null, location = "", mediaUrl = "", status = "active",
                        moderationStatus = "pending", createdAt = "", isPublic = false,
                    )) { opened = true }
                }
                }
                }
            }
        }
        compose.onNodeWithContentDescription("心愿图片").assertDoesNotExist()
        compose.onNodeWithText("¥0.00").assertDoesNotExist()
        compose.onNodeWithText("仅自己可见").assertDoesNotExist()
        compose.onNodeWithText("周末，想去看一场日出").performClick()
        compose.runOnIdle { assertTrue(opened) }
        compose.onRoot().captureRoboImage("../../../artifacts/glass-text-card-${theme.name.lowercase()}-$fontScale.png")
    }
}
