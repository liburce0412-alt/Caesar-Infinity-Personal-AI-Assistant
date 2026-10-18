package com.campusai.features.ai

import com.campusai.core.ai.CloudHealthDisclosure
import com.campusai.core.health.HealthFreshness
import com.campusai.core.health.HealthMetrics
import com.campusai.core.health.HealthPeriod
import com.campusai.core.health.HealthPeriods
import com.campusai.core.health.HealthSnapshot
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiHealthDisclosureTest {
    private val midnight = Instant.parse("2026-10-17T16:00:00Z").toEpochMilli()

    private fun snapshot(now: Long) = HealthSnapshot(
        originPackages = setOf("private.source"),
        period = HealthPeriods.parse("today", now),
        observedAt = now,
        lastSyncAt = now,
        freshness = HealthFreshness.FRESH,
        metrics = HealthMetrics(steps = 123),
        missingFields = emptySet(),
        confidence = 1.0,
    )

    @Test fun `previous Beijing day is excluded even when this turn allows disclosure`() {
        assertEquals(CloudHealthDisclosure.Excluded, currentCloudHealthDisclosure(snapshot(midnight - 1), true, midnight + 1))
        val current = currentCloudHealthDisclosure(snapshot(midnight + 1), true, midnight + 1) as CloudHealthDisclosure.Included
        assertEquals("2026-10-18", current.summary.localDate)
        assertEquals(123L, current.summary.steps)
    }

    @Test fun `consent is required and an invalid snapshot period cannot be disclosed`() {
        val today = snapshot(midnight + 1)
        assertEquals(CloudHealthDisclosure.Excluded, currentCloudHealthDisclosure(today, false, midnight + 1))
        assertEquals(CloudHealthDisclosure.Excluded, currentCloudHealthDisclosure(null, true, midnight + 1))
        val future = today.copy(period = HealthPeriod(midnight, midnight + 10_000, "today"))
        assertEquals(CloudHealthDisclosure.Excluded, currentCloudHealthDisclosure(future, true, midnight + 1))
    }

    @Test fun `full day exception rejects unknown sources and future or missing observations`() {
        val now = midnight + 60_000L
        val bucket = snapshot(now).copy(
            originPackages = setOf(com.campusai.core.health.mifitness.MiFitnessSummaryHealthGateway.SOURCE_ID),
            period = HealthPeriod(midnight, midnight + 86_400_000L - 1L, "today"),
        )
        for (invalid in listOf(bucket.copy(originPackages = setOf("unknown")), bucket.copy(observedAt = now + 1),
            bucket.copy(lastSyncAt = now + 1), bucket.copy(lastSyncAt = null), bucket.copy(observedAt = midnight - 1),
            bucket.copy(period = bucket.period.copy(endEpochMillis = now + 1)))) {
            assertEquals(CloudHealthDisclosure.Excluded, currentCloudHealthDisclosure(invalid, true, now))
        }
    }

    @Test fun `a previous day UI snapshot and success notice are not labeled as today`() {
        val previous = CaesarHealthUiState(snapshot = snapshot(midnight - 1), miFitnessLastSyncAt = midnight - 1,
            miFitnessStatus = MiFitnessUiStatus.SUCCESS, actionMessage = "Mi Fitness 今日健康已同步。")
        val current = previous.forCurrentHealthDay(midnight + 1)
        assertNull(current.snapshot)
        assertNull(current.miFitnessLastSyncAt)
        assertNull(current.actionMessage)
        assertEquals(MiFitnessUiStatus.IDLE, current.miFitnessStatus)
        val today = previous.copy(snapshot = snapshot(midnight + 1), miFitnessLastSyncAt = midnight + 1)
        assertEquals(today, today.forCurrentHealthDay(midnight + 1))
    }
}
