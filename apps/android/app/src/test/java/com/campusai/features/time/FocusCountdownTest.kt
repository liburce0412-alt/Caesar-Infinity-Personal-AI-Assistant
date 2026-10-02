package com.campusai.features.time

import org.junit.Assert.*
import org.junit.Test

class FocusCountdownTest {
    @Test fun `a delayed update accounts for all time spent in background`() {
        val started = FocusCountdown(300_000L, 301_000L)
        assertEquals(180_000L, started.at(121_000L).remainingMillis)
        assertTrue(started.at(121_000L).running)
    }

    @Test fun `pause preserves subsecond time and resume excludes paused interval`() {
        val paused = FocusCountdown(60_000L, 61_000L).pause(1_550L)
        assertEquals(59_450L, paused.at(50_000L).remainingMillis)
        assertFalse(paused.running)
        val resumed = paused.resume(50_000L)
        assertEquals(58_450L, resumed.at(51_000L).remainingMillis)
    }

    @Test fun `restored deadline catches up and completed timer cannot restart`() {
        val restored = FocusCountdown(59_000L, 61_000L)
        val ended = restored.at(100_000L)
        assertTrue(ended.completed)
        assertFalse(ended.running)
        assertEquals(ended, ended.resume(150_000L))
    }
}
