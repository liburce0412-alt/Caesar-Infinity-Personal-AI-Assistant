package com.campusai.app

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DestinationStateIsolationTest {
    @get:Rule val compose = createComposeRule()
    private var owner by mutableStateOf("alice")
    private var destination by mutableStateOf(MainDestination.CAMPUS)

    private fun show(): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val holder = rememberAccountDestinationState(owner)
            holder.SaveableStateProvider(destination.savedStateKey(owner)) {
                if (destination == MainDestination.CAMPUS) {
                    var draft by rememberSaveable { mutableStateOf("") }
                    BasicTextField(draft, { draft = it }, Modifier.testTag("draft"))
                } else Text("时间")
            }
        }
        return restoration
    }

    @Test fun `same account retains its draft across destinations and state restoration`() {
        val restoration = show()
        compose.onNodeWithTag("draft").performTextInput("我的未发布草稿")
        compose.runOnIdle { destination = MainDestination.TIME }
        compose.onNodeWithText("时间").assertExists()
        compose.runOnIdle { destination = MainDestination.CAMPUS }
        compose.onNodeWithTag("draft").assertTextEquals("我的未发布草稿")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("draft").assertTextEquals("我的未发布草稿")
    }

    @Test fun `account change drops cached and active drafts instead of reviving them on return`() {
        show()
        compose.onNodeWithTag("draft").performTextInput("Alice 的私人草稿")
        compose.runOnIdle { destination = MainDestination.TIME }
        compose.onNodeWithText("时间").assertExists()
        compose.runOnIdle { owner = "bob" }
        compose.waitForIdle()
        compose.runOnIdle { destination = MainDestination.CAMPUS }
        compose.onNodeWithTag("draft").assertTextEquals("")
        compose.onNodeWithTag("draft").performTextInput("Bob 的私人草稿")
        compose.runOnIdle { owner = "alice" }
        compose.onNodeWithTag("draft").assertTextEquals("")
        compose.runOnIdle { owner = "bob" }
        compose.onNodeWithTag("draft").assertTextEquals("")
    }
}
