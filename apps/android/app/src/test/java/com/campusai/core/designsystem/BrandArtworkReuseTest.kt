package com.campusai.core.designsystem

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.campusai.core.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrandArtworkReuseTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `remounting a brand mark reuses the launch host original bitmap`() {
        var mounted by mutableStateOf(true)
        val observed = mutableListOf<ImageBitmap>()
        compose.setContent {
            CampusTheme(ThemeMode.LIGHT) {
                BrandLaunchHost(visible = false, ready = true, onFinished = {}) {
                    if (mounted) {
                        val bitmap = brandMetalBitmap()
                        SideEffect { observed += bitmap }
                        BrandMark(Modifier.size(54.dp))
                    }
                }
            }
        }
        compose.runOnIdle { mounted = false }
        compose.waitForIdle()
        compose.runOnIdle { mounted = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(2, observed.size)
            assertSame(observed.first(), observed.last())
        }
    }
}
