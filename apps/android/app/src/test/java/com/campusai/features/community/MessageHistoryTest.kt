package com.campusai.features.community

import com.campusai.core.model.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageHistoryTest {
    private class Repository : CampusRepository() {
        val rows = (1..201).map { CampusMessage("00000000-0000-0000-0000-%012d".format(it),"conversation","peer","$it","2026-09-22T08:00:00Z") }
        val read = mutableListOf<String>()
        var fail = false
        override suspend fun loadMessages(conversationId: String, before: CampusMessage?): Result<List<CampusMessage>> =
            if (fail) Result.failure(IllegalStateException("offline"))
            else Result.success(rows.filter { before == null || it.id < before.id }.takeLast(200))
        override suspend fun loadConversations() = Result.success(emptyList<ConversationSummary>())
        override suspend fun markConversationRead(conversationId: String, messageId: String): Result<Unit> {
            read += messageId
            return Result.success(Unit)
        }
        override suspend fun sendMessage(conversationId: String, body: String) = Result.success(
            CampusMessage("sent",conversationId,"self",body,"2026-09-22T09:00:00Z"))
    }

    @Test fun `latest 200 display first and earlier pagination preserves the newest message`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = Repository()
            val vm = CampusViewModel(repository)
            vm.openMessageThread("conversation"); advanceUntilIdle()
            assertEquals("201", (vm.state.value.messages as UiState.Data).value.last().body)
            assertTrue(repository.read.isEmpty())
            vm.markMessageVisible("conversation", repository.rows.last().id); advanceUntilIdle()
            assertEquals(listOf(repository.rows.last().id), repository.read)
            vm.loadEarlierMessages(); advanceUntilIdle()
            assertEquals(201, (vm.state.value.messages as UiState.Data).value.size)
            vm.sendMessage("conversation", "outgoing"); advanceUntilIdle()
            vm.markMessageVisible("conversation", "sent"); advanceUntilIdle()
            assertEquals(1, repository.read.size) // Sending does not acknowledge an unfetched gap.
            val parameters = messagePageParameters("conversation", repository.rows[1])
            assertEquals("created_at.desc,id.desc", parameters["order"])
            assertTrue(parameters.getValue("or").contains("id.lt.${repository.rows[1].id}"))
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `failed load and closed conversation never advance read watermark`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = Repository().apply { fail = true }
            val vm = CampusViewModel(repository)
            vm.openMessageThread("conversation"); advanceUntilIdle()
            assertTrue(vm.state.value.messages is UiState.Error)
            vm.markMessageVisible("conversation", repository.rows.last().id); advanceUntilIdle()
            assertTrue(repository.read.isEmpty())
            repository.fail = false
            vm.openMessageThread("conversation"); advanceUntilIdle()
            vm.closeMessageThread()
            vm.markMessageVisible("conversation", repository.rows.last().id); advanceUntilIdle()
            assertTrue(repository.read.isEmpty())
        } finally { Dispatchers.resetMain() }
    }
}
