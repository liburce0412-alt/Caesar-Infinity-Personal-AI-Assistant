package com.campusai.features.ai

import com.campusai.core.ai.AiEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AiStreamUpdatesTest {
    @Test fun `bursts keep order and final text precedes terminal event`() = runTest {
        val output = flow {
            repeat(500) { emit(AiEvent.Delta("$it,")) }
            emit(AiEvent.Done(10))
        }.batchTextUpdates().toList()
        assertEquals((0 until 500).joinToString("") { "$it," }, output.filterIsInstance<AiEvent.Delta>().joinToString("") { it.text })
        assertTrue(output.last() is AiEvent.Done)
        assertTrue(output.size < 20)
    }

    @Test fun `single token is published during an upstream gap and flush precedes errors`() = runTest {
        val output = mutableListOf<AiEvent>()
        flow {
            emit(AiEvent.Delta("first"))
            delay(50)
            assertEquals(listOf(AiEvent.Delta("first")), output)
            emit(AiEvent.Delta("last"))
            emit(AiEvent.Error("interrupted", "stopped"))
        }.batchTextUpdates().collect { output += it }
        assertEquals(listOf(AiEvent.Delta("first"), AiEvent.Delta("last"), AiEvent.Error("interrupted", "stopped")), output)
    }

    @Test fun `shell state is stable through successive response tokens`() {
        val first = AiUiState(streaming = true, messages = listOf(com.campusai.core.model.AiConversationMessage("assistant", "a")))
        val next = first.copy(messages = listOf(com.campusai.core.model.AiConversationMessage("assistant", "abc")), elapsedMs = 900)
        assertEquals(first.runtimeState(), next.runtimeState())
    }

    @Test fun `a large delta is split without changing text and cancelled pending text stays cancelled`() = runTest {
        val text = "response".repeat(2000)
        val chunks = flow { emit(AiEvent.Delta(text)) }.batchTextUpdates(maxPendingChars = 4096).toList()
        assertEquals(text, chunks.filterIsInstance<AiEvent.Delta>().joinToString("") { it.text })
        assertTrue(chunks.filterIsInstance<AiEvent.Delta>().all { it.text.length <= 4096 })

        val cancelledOutput = mutableListOf<AiEvent>()
        val job = launch {
            flow { emit(AiEvent.Delta("cancel me")); awaitCancellation() }
                .batchTextUpdates().collect { cancelledOutput += it }
        }
        runCurrent()
        job.cancel()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(cancelledOutput.isEmpty())
    }
}
