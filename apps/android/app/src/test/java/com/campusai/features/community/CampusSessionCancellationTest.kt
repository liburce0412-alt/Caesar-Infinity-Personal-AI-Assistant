package com.campusai.features.community

import com.campusai.core.model.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CampusSessionCancellationTest {
    @Test fun `signout cancels queued edits and reads before they touch the next session`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = CampusViewModel()
            var completed = false
            val postRead = viewModel.openPostComments("old-post")
            val messageRead = viewModel.openMessageThread("old-thread")
            val publish = viewModel.publishListing("old-owner", "draft", "", null, "", null) { completed = true }
            viewModel.setSession(false, "")
            advanceUntilIdle()
            assertTrue(postRead.isCancelled)
            assertTrue(messageRead.isCancelled)
            assertTrue(publish.isCancelled)
            assertFalse(completed)
            assertNull(viewModel.state.value.activePostId)
            assertNull(viewModel.state.value.activeConversationId)
            assertNull(viewModel.state.value.operationError)
            assertFalse(viewModel.state.value.operationBusy)
            assertTrue(viewModel.state.value.posts is UiState.Error)
        } finally { Dispatchers.resetMain() }
    }
}
