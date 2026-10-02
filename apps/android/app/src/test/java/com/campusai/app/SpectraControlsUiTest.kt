package com.campusai.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.campusai.core.designsystem.*
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
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpectraControlsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `dock exposes all destinations and fits selected label at double text size`() {
        var destination by mutableStateOf(MainDestination.MARKET)
        compose.setContent {
            CampusTheme(ThemeMode.DARK) {
                ProvideSpectraExperience(SpectraVisualStyle.FLUID) {
                    ProvideSpectraTokens(DefaultSpectraTokens.copy(motion = SpectraMotion().disabled())) {
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                                SpectraDock(destination, motionEnabled = false) { destination = it }
                            }
                        }
                    }
                }
            }
        }
        for (entry in MainDestination.entries) {
            compose.onNodeWithTag("main-nav-${entry.name.lowercase()}")
                .assertHasClickAction().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("心愿墙", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("Selected destination must fit at 200% text", layouts.single().hasVisualOverflow)
        compose.onRoot().captureRoboImage("../../../artifacts/frontend-dock-large-dark.png")
        compose.onNodeWithTag("main-nav-time").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(MainDestination.TIME, destination) }
    }

    @Test fun `selector gives scaled labels room and keeps native selection semantics`() {
        var selected by mutableIntStateOf(0)
        compose.setContent {
            CampusTheme(ThemeMode.LIGHT) {
                ProvideSpectraTokens(DefaultSpectraTokens.copy(motion = SpectraMotion().disabled())) {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                            CaesarSlidingSelector(listOf("周课程表", "按天查看"), selected, { selected = it })
                        }
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("周课程表", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        compose.onRoot().captureRoboImage("../../../artifacts/frontend-selector-large-light.png")
        assertFalse("Selector label must fit at 200% text: size=${layout.size}, constraints=${layout.layoutInput.constraints}, lineCount=${layout.lineCount}, width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}", layout.hasVisualOverflow)
        compose.onNodeWithText("按天查看").assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(1, selected) }
        compose.onRoot().captureRoboImage("../../../artifacts/frontend-selector-large-light.png")
    }

    @Test fun `canceling a dock drag preserves the committed destination`() {
        var destination by mutableStateOf(MainDestination.HOME)
        var navigationCount = 0
        compose.setContent {
            CampusTheme(ThemeMode.LIGHT) {
                SpectraDock(destination, motionEnabled = true) {
                    destination = it
                    navigationCount++
                }
            }
        }
        compose.onNodeWithTag("main-nav-home").performTouchInput {
            down(center)
            moveTo(Offset(center.x + width * 2.2f, center.y), delayMillis = 160)
            cancel()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("main-nav-home").assertIsSelected()
        compose.runOnIdle {
            assertEquals(MainDestination.HOME, destination)
            assertEquals(0, navigationCount)
        }
        compose.onNodeWithTag("main-nav-market").performClick().assertIsSelected()
        compose.onNodeWithTag("main-nav-time").performClick().assertIsSelected()
    }
}
