package com.campusai.core.health.mifitness

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.campusai.core.health.CacheFirstHealthGateway
import com.campusai.core.health.HealthAvailability
import com.campusai.core.health.HealthFreshness
import com.campusai.core.health.HealthGatewayFactory
import com.campusai.core.health.HealthMetricKey
import com.campusai.core.health.HealthMetricStatus
import com.campusai.core.health.HealthMetricTimeSeries
import com.campusai.core.health.HealthMetricUnit
import com.campusai.core.health.HealthPeriod
import com.campusai.core.health.HealthTimeSeriesPoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25])
class MiFitnessSummaryHealthGatewayTest {
    @Test fun `real Beijing daily cache can be disclosed only with current turn consent and within its day`() = runTest {
        val storage = MemorySecretStorage()
        val credentials = MiFitnessCredentialStore(storage)
        credentials.save("123456789", "unit-pass-token-one").getOrThrow()
        val cache = MiFitnessStepsCache(storage)
        val now = Instant.parse("2026-10-18T06:47:00Z").toEpochMilli()
        val date = LocalDate.of(2026, 10, 18)
        val start = date.atStartOfDay(com.campusai.core.time.CaesarTime.zone).toInstant().toEpochMilli()
        val dayBucket = HealthPeriod(start, start + 86_400_000L - 1L, "today")
        cache.save(MiFitnessStepsSummary(dayBucket, date, requireNotNull(credentials.read()).accountScope,
            steps = 3210, recordCount = 4, observedAt = now - 1000, lastSyncAt = now - 1000)).getOrThrow()
        val gateway = MiFitnessSummaryHealthGateway(credentials, cache, { now })
        val snapshot = gateway.snapshot(com.campusai.core.health.HealthPeriods.parse("today", now)).getOrThrow()
        assertEquals(dayBucket, snapshot.period)
        assertTrue(snapshot.period.endEpochMillis > now)
        val included = com.campusai.features.ai.currentCloudHealthDisclosure(snapshot, true, now)
            as com.campusai.core.ai.CloudHealthDisclosure.Included
        assertEquals("2026-10-18", included.summary.localDate)
        assertEquals(3210L, included.summary.steps)
        assertEquals(com.campusai.core.ai.CloudHealthDisclosure.Excluded,
            com.campusai.features.ai.currentCloudHealthDisclosure(snapshot, false, now))
        assertEquals(com.campusai.core.ai.CloudHealthDisclosure.Excluded,
            com.campusai.features.ai.currentCloudHealthDisclosure(snapshot, true, start + 86_400_000L))
    }

    @Test fun `default Beijing gateway rejects a legacy foreign day cache without rewriting it`() = runTest {
        val storage = MemorySecretStorage()
        val credentials = MiFitnessCredentialStore(storage)
        credentials.save("123456789", "unit-pass-token-one").getOrThrow()
        val scope = requireNotNull(credentials.read()).accountScope
        val cache = MiFitnessStepsCache(storage)
        val now = Instant.parse("2026-08-27T08:00:00Z").toEpochMilli()
        val legacyDate = LocalDate.of(2026, 8, 27)
        val legacyPeriod = HealthPeriod(Instant.parse("2026-08-27T00:00:00Z").toEpochMilli(), now, "today")
        val legacy = MiFitnessStepsSummary(
            period = legacyPeriod, localDate = legacyDate, accountScope = scope, steps = 3210,
            recordCount = 4, observedAt = now - 1000, lastSyncAt = now - 1000,
        )
        cache.save(legacy).getOrThrow()
        val original = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
            val gateway = MiFitnessSummaryHealthGateway(credentials, cache, { now })
            val requested = com.campusai.core.health.HealthPeriods.parse("today", now)
            assertTrue(gateway.snapshot(requested).isFailure)
            assertEquals(legacy, cache.read(legacyPeriod, legacyDate, scope))
            val beijing = legacy.copy(period = requested)
            cache.save(beijing).getOrThrow()
            assertEquals(3210L, gateway.snapshot(requested).getOrThrow().metrics.steps)
        } finally { java.util.TimeZone.setDefault(original) }
    }

    @Test
    fun `API 25 reads matching Mi Fitness vendor aggregate cache as typed snapshot`() = runTest {
        val fixture = fixture()
        val requested = fixture.period.copy(endEpochMillis = fixture.period.endEpochMillis + 5_000L)

        val snapshot = fixture.gateway.snapshot(requested).getOrThrow()

        assertEquals(setOf(MiFitnessSummaryHealthGateway.SOURCE_ID), snapshot.originPackages)
        assertEquals(fixture.period.startEpochMillis, snapshot.period.startEpochMillis)
        assertEquals("today", snapshot.period.key)
        assertEquals(3_210L, snapshot.metrics.steps)
        assertNull(snapshot.metrics.distanceMeters)
        assertEquals(HealthFreshness.FRESH, snapshot.freshness)
        assertEquals(MiFitnessSummaryHealthGateway.VENDOR_AGGREGATE_CONFIDENCE, snapshot.confidence, 0.0)
        assertTrue("steps" !in snapshot.missingFields)
        assertTrue("sleep_minutes" in snapshot.missingFields)
        val stepSeries = checkNotNull(snapshot.metricTimeSeries[HealthMetricKey.STEPS])
        assertEquals(HealthMetricStatus.AVAILABLE, stepSeries.status)
        assertEquals(listOf(40.0, 120.0), stepSeries.points.map { it.value })
    }

    @Test
    fun `cache fails closed for a different account or non-today period`() = runTest {
        val fixture = fixture()
        fixture.credentials.save("987654321", "unit-pass-token-two").getOrThrow()

        assertTrue(fixture.gateway.snapshot(fixture.period).isFailure)

        val weekly = fixture.period.copy(key = "week")
        assertTrue(fixture.gateway.snapshot(weekly).isFailure)
    }

    @Test
    fun `today request from another system zone resolves the canonical China cache day`() = runTest {
        val fixture = fixture()
        val systemZonePeriod = HealthPeriod(
            startEpochMillis = Instant.parse("2026-08-26T10:00:00Z").toEpochMilli(),
            endEpochMillis = fixture.period.endEpochMillis,
            key = "today",
        )

        val storage = MemorySecretStorage()
        val credentials = MiFitnessCredentialStore(storage)
        credentials.save("123456789", "unit-pass-token-one").getOrThrow()
        val accountScope = requireNotNull(credentials.read()).accountScope
        val cache = MiFitnessStepsCache(storage)
        val now = Instant.parse("2026-08-27T08:00:00Z").toEpochMilli()
        val chinaDate = LocalDate.of(2026, 8, 27)
        val chinaStart = chinaDate.atStartOfDay(ZoneOffset.ofHours(8)).toInstant().toEpochMilli()
        val chinaPeriod = HealthPeriod(chinaStart, chinaStart + 86_400_000L - 1L, "today")
        cache.save(
            MiFitnessStepsSummary(
                period = chinaPeriod,
                localDate = chinaDate,
                accountScope = accountScope,
                steps = 3_210L,
                recordCount = 4,
                observedAt = now - 1_000L,
                lastSyncAt = now - 60_000L,
            ),
        ).getOrThrow()
        val gateway = MiFitnessSummaryHealthGateway(
            credentials,
            cache,
            { now },
            ZoneOffset.ofHours(8),
            { ZoneOffset.ofHours(14) },
        )

        val snapshot = gateway.snapshot(systemZonePeriod).getOrThrow()

        assertEquals(chinaPeriod, snapshot.period)
        assertEquals(3_210L, snapshot.metrics.steps)
    }

    @Test
    fun `today label cannot relabel an arbitrary historical window`() = runTest {
        val fixture = fixture()
        val relabeled = fixture.period.copy(
            startEpochMillis = fixture.period.startEpochMillis - 86_400_000L,
        )

        assertTrue(fixture.gateway.snapshot(relabeled).isFailure)
    }

    @Test
    fun `credentials without a matching cache are not reported as available`() {
        val storage = MemorySecretStorage()
        val credentials = MiFitnessCredentialStore(storage)
        credentials.save("123456789", "unit-pass-token-one").getOrThrow()
        val now = Instant.parse("2026-08-27T08:00:00Z").toEpochMilli()
        val gateway = MiFitnessSummaryHealthGateway(
            credentials,
            MiFitnessStepsCache(storage),
            { now },
            ZoneOffset.UTC,
        )

        assertEquals(HealthAvailability.Unsupported, gateway.availability())
    }

    @Test
    fun `factory keeps cache-first gateway loadable while Health Connect is unsupported on API 25`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val composite = HealthGatewayFactory.create(context)
        val healthConnect = HealthGatewayFactory.createHealthConnectOnly(context)

        assertTrue(composite is CacheFirstHealthGateway)
        assertEquals(HealthAvailability.Unsupported, composite.availability())
        assertEquals(HealthAvailability.Unsupported, healthConnect.availability())
    }

    private fun fixture(): Fixture {
        val storage = MemorySecretStorage()
        val credentials = MiFitnessCredentialStore(storage)
        credentials.save("123456789", "unit-pass-token-one").getOrThrow()
        val accountScope = requireNotNull(credentials.read()).accountScope
        val cache = MiFitnessStepsCache(storage)
        val date = LocalDate.of(2026, 8, 27)
        val start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val now = Instant.parse("2026-08-27T08:00:00Z").toEpochMilli()
        val period = HealthPeriod(start, now, "today")
        cache.save(
            MiFitnessStepsSummary(
                period = period,
                localDate = date,
                accountScope = accountScope,
                steps = 3_210L,
                recordCount = 4,
                observedAt = now - 1_000L,
                lastSyncAt = now - 60_000L,
                metricTimeSeries = mapOf(
                    HealthMetricKey.STEPS to HealthMetricTimeSeries(
                        unit = HealthMetricUnit.COUNT,
                        status = HealthMetricStatus.AVAILABLE,
                        points = listOf(
                            HealthTimeSeriesPoint(start + 1_800_000L, 40.0),
                            HealthTimeSeriesPoint(start + 3_600_000L, 120.0),
                        ),
                        provenance = MiFitnessMetricRegistry.stepSeriesProvenance(2),
                    ),
                ),
            ),
        ).getOrThrow()
        return Fixture(
            credentials = credentials,
            period = period,
            gateway = MiFitnessSummaryHealthGateway(
                credentials,
                cache,
                { now },
                ZoneOffset.UTC,
                { ZoneOffset.UTC },
            ),
        )
    }

    private data class Fixture(
        val credentials: MiFitnessCredentialStore,
        val period: HealthPeriod,
        val gateway: MiFitnessSummaryHealthGateway,
    )

    private class MemorySecretStorage : MiFitnessSecretStorage {
        private val values = mutableMapOf<String, String>()

        override fun read(key: String): String = values[key].orEmpty()

        override fun write(key: String, value: String): Boolean {
            if (value.isEmpty()) values.remove(key) else values[key] = value
            return true
        }
    }
}
