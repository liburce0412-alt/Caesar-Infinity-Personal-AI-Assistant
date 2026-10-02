package com.campusai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.campusai.core.designsystem.BrandMark
import com.campusai.core.designsystem.CampusTheme
import com.campusai.core.designsystem.DefaultSpectraTokens
import com.campusai.core.designsystem.ProvideSpectraExperience
import com.campusai.core.designsystem.SpectraMotion
import com.campusai.core.designsystem.SpectraStatus
import com.campusai.core.designsystem.SpectraStatusTone
import com.campusai.core.designsystem.SpectraTheme
import com.campusai.core.designsystem.SpectraVisualStyle
import com.campusai.core.designsystem.SpectraWidthClass
import com.campusai.core.designsystem.TelemetryChip
import com.campusai.core.designsystem.spectraWidthClassFor
import com.campusai.core.designsystem.spectraTokensForStyle
import com.campusai.core.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AdaptiveSpectraTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `default tokens keep touch and motion contracts`() {
        assertEquals(48.dp, DefaultSpectraTokens.sizes.minimumTouchTarget)
        assertEquals(120, DefaultSpectraTokens.motion.microMillis)
        assertEquals(200, DefaultSpectraTokens.motion.shortMillis)
        assertEquals(420, DefaultSpectraTokens.motion.longMillis)

        val motionOff = SpectraMotion().disabled()
        assertFalse(motionOff.enabled)
        assertEquals(0, motionOff.resolve(420))
    }

    @Test
    fun `width class uses material adaptive breakpoints`() {
        assertEquals(SpectraWidthClass.COMPACT, spectraWidthClassFor(599.dp))
        assertEquals(SpectraWidthClass.MEDIUM, spectraWidthClassFor(600.dp))
        assertEquals(SpectraWidthClass.MEDIUM, spectraWidthClassFor(839.dp))
        assertEquals(SpectraWidthClass.EXPANDED, spectraWidthClassFor(840.dp))
    }

    @Test
    fun `fluid is a complete layout and material system`() {
        val fluidTokens = spectraTokensForStyle(style = SpectraVisualStyle.FLUID)
        assertEquals(22.dp, fluidTokens.radii.card)
        assertEquals(28.dp, fluidTokens.radii.hero)
        assertEquals(64.dp, fluidTokens.sizes.navigationDock)

        var capturedStyle = SpectraVisualStyle.CLASSIC
        var capturedHorizontal = 0.dp
        compose.setContent {
            ProvideSpectraExperience(SpectraVisualStyle.FLUID) {
                capturedStyle = SpectraTheme.visualStyle
                capturedHorizontal = SpectraTheme.layout.pageHorizontalPadding
                Box(Modifier.size(1.dp))
            }
        }

        compose.runOnIdle {
            assertEquals(SpectraVisualStyle.FLUID, capturedStyle)
            assertEquals(16.dp, capturedHorizontal)
        }
    }

    @Test
    fun `campus theme provides spectra tokens`() {
        var captured = DefaultSpectraTokens.copy()
        compose.setContent {
            CampusTheme(ThemeMode.LIGHT) {
                captured = SpectraTheme.tokens
                Box(Modifier.size(1.dp))
            }
        }

        compose.runOnIdle { assertSame(DefaultSpectraTokens, captured) }
    }

    @Test
    fun `uncontained text inherits dark theme foreground`() {
        var expected = Color.Unspecified
        compose.setContent {
            CampusTheme(ThemeMode.DARK) {
                expected = MaterialTheme.colorScheme.onBackground
                Text("默认正文")
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("默认正文")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(expected, layouts.single().layoutInput.style.color)
    }

    @Test
    fun `telemetry compatibility action is selected clickable and touch sized`() {
        var clicks = 0
        compose.setContent {
            CampusTheme(ThemeMode.LIGHT) {
                TelemetryChip(text = "QUALITY", selected = true, onClick = { clicks++ })
            }
        }

        compose.onNodeWithText("QUALITY")
            .assertIsSelected()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun `passive status does not expose a click action`() {
        compose.setContent {
            CampusTheme(ThemeMode.LIGHT) {
                SpectraStatus("Ready", tone = SpectraStatusTone.SUCCESS)
            }
        }

        compose.onNodeWithText("Ready")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun `brand mark supports explicit label and decorative semantics`() {
        compose.setContent {
            CampusTheme(ThemeMode.LIGHT) {
                Box {
                    BrandMark(
                        modifier = Modifier.size(32.dp),
                        decorative = false,
                        contentDescription = "Caesar brand",
                    )
                    BrandMark(
                        modifier = Modifier.size(24.dp),
                        decorative = true,
                        contentDescription = "Decorative brand",
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Caesar brand").assertExists()
        compose.onNodeWithContentDescription("Decorative brand").assertDoesNotExist()
    }
}
