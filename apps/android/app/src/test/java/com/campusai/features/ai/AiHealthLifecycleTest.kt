package com.campusai.features.ai

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiHealthLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `visible health refresh follows actual lifecycle restart and does not run while hidden`() {
        val owner = Owner()
        val visible = mutableStateOf(true)
        var reads = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                RefreshHealthStatusWhenVisible(visible.value) { reads++ }
            }
        }
        compose.runOnIdle { assertEquals(1, reads); owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, reads); owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(2, reads); visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(2, reads); visible.value = true }
        compose.runOnIdle { assertEquals(3, reads) }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
