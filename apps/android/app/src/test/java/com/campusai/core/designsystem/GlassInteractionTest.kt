package com.campusai.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.campusai.core.model.ThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GlassInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `drag moves optics and release rebounds without moving content or stealing clicks`() {
        val owner = OpticalGlassRegistry.nextRendererOwnerId()
        OpticalGlassRegistry.claimRenderer(owner)
        OpticalGlassRegistry.beginRouteHost("test:glass-interaction")
        var clicks = 0
        fun region() = OpticalGlassRegistry.snapshot(owner, 0f, 0f, 4000f, 4000f).single()
        try {
            compose.setContent {
                CampusTheme(ThemeMode.DARK) {
                    GlassPanel(Modifier.size(240.dp, 120.dp).testTag("glass"), onClick = { clicks++ }) {
                        Box(Modifier.size(48.dp).testTag("fixed-control")) { Text("稳定文字") }
                    }
                }
            }
            val label = compose.onNodeWithText("稳定文字", useUnmergedTree = true)
            val control = compose.onNodeWithTag("fixed-control", useUnmergedTree = true)
            val labelBounds = label.fetchSemanticsNode().boundsInRoot
            val controlBounds = control.fetchSemanticsNode().boundsInRoot
            compose.mainClock.autoAdvance = false
            val glass = compose.onNodeWithTag("glass")
            glass.performTouchInput { down(Offset(width * .2f, height * .3f)) }
            compose.mainClock.advanceTimeBy(240)
            compose.runOnIdle {
                assertTrue(region().interaction > .8f)
                assertEquals(.2f, region().touch.x, .02f)
            }
            glass.performTouchInput { moveTo(Offset(width * .8f, height * .6f)) }
            compose.mainClock.advanceTimeBy(32)
            compose.runOnIdle { assertEquals(.8f, region().touch.x, .02f) }
            assertEquals(labelBounds, label.fetchSemanticsNode().boundsInRoot)
            assertEquals(controlBounds, control.fetchSemanticsNode().boundsInRoot)
            glass.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(240)
            compose.runOnIdle { assertTrue("Spring should overshoot on release", region().interaction < 0f) }
            compose.mainClock.advanceTimeBy(1000)
            compose.runOnIdle { assertEquals(0f, region().interaction, .01f) }
            assertEquals(labelBounds, label.fetchSemanticsNode().boundsInRoot)
            assertEquals(controlBounds, control.fetchSemanticsNode().boundsInRoot)
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { clicks = 0 }
            glass.performTouchInput { click() }
            compose.runOnIdle { assertEquals(1, clicks) }
        } finally {
            OpticalGlassRegistry.releaseRenderer(owner)
        }
    }
}
