package com.campusai.features.ai

import com.campusai.core.ai.AiRequest
import com.campusai.core.ai.CloudAiProvider
import com.campusai.core.ai.CloudDailyHealthSummary
import com.campusai.core.ai.CloudHealthDisclosure
import com.campusai.core.health.HealthFreshness
import com.campusai.core.health.HealthMetrics
import com.campusai.core.health.HealthPeriods
import com.campusai.core.health.HealthSnapshot
import com.campusai.core.model.AiConversationMessage
import com.campusai.core.model.AiMode
import com.campusai.core.network.OpenAiCompatibleRequestFactory
import com.campusai.core.network.cloudFreePayload
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiHealthEmptySummaryTest {
    private fun request(summary: CloudDailyHealthSummary) = AiRequest(
        mode = AiMode.FAST,
        messages = listOf(AiConversationMessage("user", "总结本次健康并查询课程")),
        ownerUserId = "alice",
        cloudHealthDisclosure = CloudHealthDisclosure.Included(summary),
    )

    @Test fun `empty and entirely invalid summaries are omitted by every cloud payload factory`() {
        val empty = CloudDailyHealthSummary("2026-10-18")
        val invalid = empty.copy(steps = -1, distanceMeters = Double.NaN,
            activeCaloriesKcal = Double.POSITIVE_INFINITY, activityMinutes = -1, sleepMinutes = -1,
            averageHeartRateBpm = 0.0, averageOxygenSaturationPercent = 101.0,
            averageStressScore = -1.0, workoutCount = -1)
        for (summary in listOf(empty, invalid)) {
            assertFalse(summary.hasShareableMetrics)
            assertNull(summary.toAllowedJsonOrNull())
            assertFalse(cloudFreePayload(request(summary)).getJSONObject("context").has("consentedDailyHealthSummary"))
            for (provider in CloudAiProvider.entries) {
                val payload = OpenAiCompatibleRequestFactory.prepare(provider, request(summary), "test-model").payload
                assertFalse(payload.toString().contains("<health_summary>"))
                assertTrue(payload.toString().contains("总结本次健康并查询课程"))
            }
        }
    }

    @Test fun `current day empty metrics require no upload while measured zero remains valid with consent`() {
        val now = Instant.parse("2026-10-18T07:00:00Z").toEpochMilli()
        val empty = HealthSnapshot(originPackages = setOf("test.source"),
            period = HealthPeriods.parse("today", now), observedAt = now, lastSyncAt = now,
            freshness = HealthFreshness.FRESH, metrics = HealthMetrics(), missingFields = emptySet(), confidence = 1.0)
        assertEquals(CloudHealthDisclosure.Excluded, currentCloudHealthDisclosure(empty, true, now))
        val measured = empty.copy(metrics = HealthMetrics(steps = 0, heartRateAverageBpm = 0))
        assertEquals(CloudHealthDisclosure.Excluded, currentCloudHealthDisclosure(measured, false, now))
        val included = currentCloudHealthDisclosure(measured, true, now) as CloudHealthDisclosure.Included
        assertTrue(included.summary.hasShareableMetrics)
        val summary = cloudFreePayload(request(included.summary)).getJSONObject("context").getJSONObject("consentedDailyHealthSummary")
        assertEquals(0L, summary.getLong("steps"))
        assertFalse(summary.has("averageHeartRateBpm"))
        val personal = OpenAiCompatibleRequestFactory.prepare(CloudAiProvider.DEEPSEEK, request(included.summary), "test-model").payload
        assertTrue(personal.toString().contains("<health_summary>"))
    }
}
