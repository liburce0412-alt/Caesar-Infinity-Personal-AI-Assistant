package com.campusai.core.designsystem

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.campusai.app.HomeScreen
import com.campusai.core.model.ThemeMode
import com.campusai.core.model.UiState
import com.campusai.features.ai.CaesarHealthUiState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the production launch host over the actual HomeScreen; no replacement animation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrandLaunchUiTest {
    @get:Rule val compose = createComposeRule()
    private var visible by mutableStateOf(false)
    private var ready by mutableStateOf(true)
    private var finishes = 0
    private var starts = 0
    private var previousRecord: String? = null
    private val brandPane = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Caesar∞")

    @Before fun configureFrameCapture() {
        previousRecord = System.getProperty("roborazzi.test.record")
        System.setProperty("roborazzi.test.record", "true")
        compose.mainClock.autoAdvance = false
    }

    @After fun restoreCaptureSetting() {
        if (previousRecord == null) System.clearProperty("roborazzi.test.record")
        else System.setProperty("roborazzi.test.record", previousRecord)
    }

    @Test fun `light entrance moves through real frames then restores home semantics and touch`() =
        entrance(ThemeMode.LIGHT)

    @Test
    @Config(qualifiers = "w393dp-h851dp-night-xhdpi")
    fun `dark entrance moves through real frames then restores home semantics and touch`() =
        entrance(ThemeMode.DARK)

    @Test fun `early entrance preserves the shared background supplied outside the host`() {
        val sharedBackground = Color(0xFF164F64)
        showHome(ThemeMode.LIGHT, sharedBackground)
        updateHost { visible = true }
        compose.mainClock.advanceTimeBy(240)
        compose.onNode(brandPane).assertExists()
        compose.onNodeWithText("开始记录").assertDoesNotExist()
        val frame = requireNotNull(BitmapFactory.decodeFile(capture("brand-launch-shared-background-240ms").absolutePath))
        try {
            assertEquals("The entrance must reveal the external backdrop instead of its own solid fill",
                sharedBackground.toArgb(), frame.getPixel(8, 8))
            assertEquals("The shared backdrop must also remain visible below the artwork",
                sharedBackground.toArgb(), frame.getPixel(frame.width - 9, frame.height - 9))
        } finally { frame.recycle() }
    }

    @Test fun `missing platform readiness cannot leave the home screen blocked`() {
        showHome(ThemeMode.LIGHT)
        updateHost { ready = false; visible = true }
        compose.mainClock.advanceTimeByFrame()
        compose.onNode(brandPane).assertExists()
        compose.onNodeWithText("开始记录").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1_200)
        assertHomeRecovered()
    }

    @Test fun `removing the host overlay cancels its pending completion and releases touch immediately`() {
        showHome(ThemeMode.LIGHT)
        updateHost { visible = true }
        compose.mainClock.advanceTimeBy(80)
        compose.onNode(brandPane).assertExists()
        updateHost { visible = false }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("开始记录").assertHasClickAction().performTouchInput { click() }
        compose.mainClock.advanceTimeBy(2_350)
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals("An externally dismissed overlay must not finish again later", 0, finishes)
        }
        compose.onNode(brandPane).assertDoesNotExist()
    }

    private fun entrance(theme: ThemeMode) {
        val buttonCenter = showHome(theme)
        updateHost { visible = true }
        compose.mainClock.advanceTimeByFrame()
        compose.onNode(brandPane).assertExists()
        compose.onNodeWithText("开始记录").assertDoesNotExist()
        // A physical touch at the real home button must be consumed by the entrance overlay.
        compose.onRoot().performTouchInput { click(buttonCenter) }
        compose.runOnIdle { assertEquals(0, starts) }

        val prefix = "brand-launch-${theme.name.lowercase()}"
        compose.mainClock.advanceTimeBy(240)
        val assembling = capture("$prefix-240ms")
        compose.mainClock.advanceTimeBy(460)
        capture("$prefix-700ms")
        compose.mainClock.advanceTimeBy(480)
        val assembled = capture("$prefix-1180ms")
        assertFramesDiffer(assembling, assembled, theme)
        compose.mainClock.advanceTimeBy(420)
        capture("$prefix-1600ms")
        compose.mainClock.advanceTimeBy(300)
        capture("$prefix-1900ms")
        // The home stays inaccessible through the exit fade, not just the initial assembly.
        compose.onNode(brandPane).assertExists()
        compose.onNodeWithText("开始记录").assertDoesNotExist()
        compose.onRoot().performTouchInput { click(buttonCenter) }
        compose.runOnIdle { assertEquals(0, starts) }
        compose.mainClock.advanceTimeBy(450)
        assertHomeRecovered()
        capture("$prefix-home")
    }

    private fun showHome(theme: ThemeMode, sharedBackground: Color? = null): Offset {
        compose.setContent {
            CampusTheme(theme) {
                // Mirrors CampusApp's ownership: one persistent backdrop outside the launch host.
                Box(Modifier.fillMaxSize().background(sharedBackground ?: MaterialTheme.colorScheme.background)) {
                    BrandLaunchHost(visible, ready, onFinished = { finishes++; visible = false }) {
                        HomeScreen(records = emptyList(), displayName = "同学", avatarUrl = "",
                            dailyText = "先完成一件最重要的小事", announcements = UiState.Empty,
                            onRefreshAnnouncements = {}, onStartRecord = { starts++ }, onOpenAi = {},
                            healthState = CaesarHealthUiState(), onRefreshHealth = {}, onSyncMiFitnessSteps = {},
                            contentPadding = PaddingValues(0.dp))
                    }
                }
            }
        }
        return compose.onNodeWithText("开始记录").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center
    }

    private fun assertHomeRecovered() {
        // Drain Android's apply notifications before the manually driven composition frame.
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.onNode(brandPane).assertDoesNotExist()
        compose.onNodeWithText("开始记录").assertIsDisplayed().assertHasClickAction()
            .performTouchInput { click() }
        compose.runOnIdle {
            assertEquals("The entrance should complete once", 1, finishes)
            assertEquals("The actual HomeScreen button should receive the first post-entrance touch", 1, starts)
        }
    }

    private fun updateHost(update: () -> Unit) {
        compose.runOnIdle {
            update()
            // With autoAdvance disabled, Android's deferred global-snapshot notification can
            // otherwise arrive only after the requested frame, leaving the old host composed.
            Snapshot.sendApplyNotifications()
        }
    }

    private fun capture(name: String): File {
        val file = File("../../../artifacts/brand-launch/$name.png").absoluteFile
        requireNotNull(file.parentFile).mkdirs()
        compose.onRoot().captureRoboImage(file.absolutePath)
        assertTrue("The renderer must produce an inspectable PNG", file.isFile && file.length() > 0)
        return file
    }

    private fun assertFramesDiffer(first: File, second: File, theme: ThemeMode) {
        val before = requireNotNull(BitmapFactory.decodeFile(first.absolutePath))
        val after = requireNotNull(BitmapFactory.decodeFile(second.absolutePath))
        try {
            assertFalse("The entrance must render changing pixels rather than a static logo", before.sameAs(after))
            val background = before.getPixel(8, 8)
            val channels = listOf(android.graphics.Color.red(background), android.graphics.Color.green(background), android.graphics.Color.blue(background))
            if (theme == ThemeMode.DARK) assertTrue("The shared dark theme background should remain visible", channels.all { it < 100 })
            else assertTrue("The shared light theme background should remain visible", channels.all { it > 180 })
        } finally { before.recycle(); after.recycle() }
    }
}
