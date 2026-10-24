package com.campusai.core.automation

import com.campusai.core.ai.CloudAiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledTaskIntervalTest {
    @Test fun everyWholeMinuteFromThreeToSixtyIsAcceptedWithBackgroundEnabled() {
        for (minutes in 3..60) {
            val config = task(minutes)
            assertEquals(minutes, config.intervalMinutes)
            assertEquals(maxOf(15, minutes), config.backgroundIntervalMinutes)
        }
        assertThrows(IllegalArgumentException::class.java) { task(2) }
        assertThrows(IllegalArgumentException::class.java) { task(61) }
    }

    @Test fun foregroundUsesSelectedIntervalEvenWhenBackgroundHasLongerMinimum() {
        val config = task(3).copy(lastAttemptAt = 1_000L)
        assertFalse(config.isDue(180_999L))
        assertTrue(config.isDue(181_000L))
        assertFalse(config.copy(enabled = false).isDue(181_000L))
    }

    private fun task(minutes: Int) = ScheduledTaskConfig(
        id = HealthTaskDefaults.TASK_ID,
        type = ScheduledTaskType.HEALTH_CLOUD_STATUS,
        enabled = true,
        intervalMinutes = minutes,
        provider = CloudAiProvider.GOOGLE_GEMINI,
        modelId = ScheduledTaskConfig.CLOUD_FREE_MODEL,
        includeHealthSummary = false,
        options = HealthTaskOptions(syncOnly = true, runInBackground = true,
            aiMode = HealthTaskAiMode.CAESAR_CLOUD_FREE),
    )
}
