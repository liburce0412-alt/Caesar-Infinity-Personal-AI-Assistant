package com.campusai.features.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSpeechResultGuardTest {
    @Test
    fun `permission result belongs to the initiating owner and request`() {
        val guard = AiSpeechResultGuard()
        val ticket = guard.begin("owner-a")
        assertTrue(guard.accepts(ticket, "owner-a"))
        assertFalse(guard.accepts(ticket, "owner-b"))
        assertFalse(guard.accepts(null, "owner-a"))
    }

    @Test
    fun `cancelled request cannot restart recognition or write a system transcript`() {
        val guard = AiSpeechResultGuard()
        val ticket = guard.begin("owner-a")
        guard.cancel()
        assertFalse(guard.accepts(ticket, "owner-a"))
        assertTrue(guard.accepts(guard.begin("owner-a"), "owner-a"))
    }

    @Test
    fun `a new attempt rejects a late callback from the previous attempt`() {
        val guard = AiSpeechResultGuard()
        val old = guard.begin("owner-a")
        val current = guard.begin("owner-a")
        assertFalse(guard.accepts(old, "owner-a"))
        assertTrue(guard.accepts(current, "owner-a"))
    }

    @Test
    fun `disposed page rejects all delayed activity and permission results`() {
        val guard = AiSpeechResultGuard()
        val old = guard.begin("owner-a")
        guard.close()
        assertFalse(guard.accepts(old, "owner-a"))
        assertFalse(guard.accepts(guard.begin("owner-a"), "owner-a"))
    }
}
