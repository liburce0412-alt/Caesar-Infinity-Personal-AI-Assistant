package com.campusai.features.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.campusai.core.designsystem.CaesarGlassOrb
import com.campusai.core.designsystem.CampusTheme
import com.campusai.core.model.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiOrbLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `both composers keep a visible hero above the heading and voice never sends text`() {
        var preset by mutableStateOf(AiVisualPreset.CLASSIC)
        var dark by mutableStateOf(false)
        var keyboard by mutableStateOf(false)
        var anchor by mutableStateOf(Rect.Zero)
        var root by mutableStateOf(Rect.Zero)
        var taps = 0
        var sends = 0
        val previous = System.getProperty("roborazzi.test.record")
        System.setProperty("roborazzi.test.record", "true")
        try {
            compose.setContent {
                CampusTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                    val density = LocalDensity.current
                    Box(Modifier.width(if (keyboard) 320.dp else 393.dp).height(if (keyboard) 380.dp else 680.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .then(Modifier.onGloballyPositionedForTest { root = it })) {
                        Column {
                            QuietEmptyState("本机模型已就绪", false, preset, Modifier.weight(1f), "现在，想做什么？", true,
                                onOrbPositioned = { anchor = it.boundsInWindow() })
                            AiComposer("可编辑的转写", {}, false, false, preset, emptyList(), false, {}, {}, {}, false,
                                onVoice = { taps++ }, onAction = { sends++ })
                        }
                        if (anchor.width > 0) CaesarGlassOrb(
                            Modifier.offset { IntOffset((anchor.left - root.left).roundToInt(), (anchor.top - root.top).roundToInt()) },
                            size = with(density) { anchor.width.toDp() }, motionEnabled = false)
                    }
                }
            }
            for (style in AiVisualPreset.entries) for (night in listOf(false, true)) for (ime in listOf(false, true)) {
                compose.runOnIdle { preset = style; dark = night; keyboard = ime }
                compose.waitForIdle()
                val heading = compose.onNodeWithText("现在，想做什么？").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
                assertTrue("Orb stays above native heading", anchor.bottom <= heading.top)
                assertTrue("Orb remains within available viewport", anchor.top >= root.top && anchor.right <= root.right)
                compose.onNode(hasText("语音") or hasContentDescription("语音")).assertIsDisplayed().performClick()
                assertEquals(0, sends)
                val output = File("../../../artifacts/caesar-orb-20261013/layout-${style.name}-$night-$ime.png")
                output.parentFile?.mkdirs()
                compose.onRoot().captureRoboImage(output.absolutePath)
            }
            assertEquals(8, taps)
        } finally {
            if (previous == null) System.clearProperty("roborazzi.test.record") else System.setProperty("roborazzi.test.record", previous)
        }
    }
}

private fun Modifier.onGloballyPositionedForTest(update: (Rect) -> Unit) = this.then(
    Modifier.onGloballyPositioned { update(it.boundsInWindow()) },
)
