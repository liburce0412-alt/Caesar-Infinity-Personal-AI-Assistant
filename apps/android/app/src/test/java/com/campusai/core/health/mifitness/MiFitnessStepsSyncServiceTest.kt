package com.campusai.core.health.mifitness

import com.campusai.core.health.HealthMetricKey
import com.campusai.core.health.HealthMetricStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest

@RunWith(RobolectricTestRunner::class)
class MiFitnessStepsSyncServiceTest {
    @Test fun `default day window uses Beijing despite a different clock and device zone`() {
        val original = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
            val storage = RecordingSecretStorage()
            val now = Instant.parse("2026-08-26T16:01:00Z")
            val service = MiFitnessStepsSyncService(
                MiFitnessCredentialStore(storage), FakeTransport(), MiFitnessStepsCache(storage),
                Clock.fixed(now, java.time.ZoneId.of("Pacific/Kiritimati")),
            )
            val window = service.todayWindow()
            assertEquals(LocalDate.of(2026, 8, 27), window.localDate)
            assertEquals(Instant.parse("2026-08-26T16:00:00Z").toEpochMilli(), window.period.startEpochMillis)
            assertEquals(Instant.parse("2026-08-27T16:00:00Z").toEpochMilli() - 1, window.period.endEpochMillis)
        } finally { java.util.TimeZone.setDefault(original) }
    }

    @Test
    fun `sync reads one local day follows pages and stores only vendor aggregate`() = runTest {
        val storage = RecordingSecretStorage()
        val credentialStore = MiFitnessCredentialStore(storage)
        assertTrue(credentialStore.save("12345", "synthetic-pass-token").isSuccess)
        storage.writes.clear()
        val start = LocalDate.of(2026, 8, 27).atStartOfDay().toEpochSecond(ZoneOffset.ofHours(8))
        val transport = FakeTransport(
            page(emptyList(), hasMore = true, nextKey = "cursor-one"),
            page(listOf(start to 3_210L), hasMore = false),
        )
        val cache = MiFitnessStepsCache(storage)
        val service = service(credentialStore, transport, cache)

        val summary = service.syncToday().getOrThrow()

        assertEquals(3_210L, summary.steps)
        assertEquals(1, summary.recordCount)
        assertEquals(LocalDate.of(2026, 8, 27), summary.localDate)
        assertEquals(start * 1_000L, summary.period.startEpochMillis)
        assertEquals((start + 86_400L) * 1_000L - 1L, summary.period.endEpochMillis)
        assertEquals(listOf("", "cursor-one"), transport.calls.map(FetchCall::nextKey))
        assertTrue(
            transport.calls.all {
                it.startEpochSeconds == start && it.endEpochSecondsExclusive == start + 86_400L
            },
        )
        assertEquals(summary, cache.read(summary.period, summary.localDate, summary.accountScope))
        assertEquals(1, storage.writes.size)
        assertTrue(storage.writes.single().second.contains("\"steps\":3210"))
        assertFalse(storage.writes.single().second.contains("data_list"))
        assertFalse(storage.writes.single().second.contains("synthetic-pass-token"))
    }

    @Test
    fun `successful sync safely rotates a refreshed pass token after caching`() = runTest {
        val storage = RecordingSecretStorage()
        val store = MiFitnessCredentialStore(storage)
        store.save("12345", "original-pass-token")
        val start = LocalDate.of(2026, 8, 27).atStartOfDay().toEpochSecond(ZoneOffset.ofHours(8))
        val transport = FakeTransport(page(listOf(start to 123L), hasMore = false)).apply {
            refreshedPassToken = "rotated-pass-token"
        }
        val cache = MiFitnessStepsCache(storage)
        val service = service(store, transport, cache)
        storage.writes.clear()

        val summary = service.syncToday().getOrThrow()

        assertEquals("rotated-pass-token", store.read()?.passToken)
        assertEquals(2, storage.writes.size)
        assertTrue(storage.writes[0].second.contains("\"steps\":123"))
        assertFalse(storage.writes[0].second.contains("rotated-pass-token"))
        assertTrue(storage.writes[1].second.contains("\"passToken\":\"rotated-pass-token\""))
        assertEquals(summary, cache.read(summary.period, summary.localDate, summary.accountScope))
    }

    @Test
    fun `one read authentication failure reauthenticates and retries once`() = runTest {
        val storage = RecordingSecretStorage()
        val store = MiFitnessCredentialStore(storage)
        store.save("12345", "synthetic-pass-token")
        val start = LocalDate.of(2026, 8, 27).atStartOfDay().toEpochSecond(ZoneOffset.ofHours(8))
        val transport = FakeTransport(page(listOf(start to 321L), hasMore = false)).apply {
            authenticationFailuresRemaining = 1
        }

        val summary = service(store, transport, MiFitnessStepsCache(storage)).syncToday().getOrThrow()

        assertEquals(321L, summary.steps)
        assertEquals(2, transport.exchangeCount)
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun `failed refreshed credential write restores old visible state`() = runTest {
        val storage = RecordingSecretStorage()
        val store = MiFitnessCredentialStore(storage)
        store.save("12345", "original-pass-token")
        val oldCredential = checkNotNull(store.read())
        val start = LocalDate.of(2026, 8, 27).atStartOfDay().toEpochSecond(ZoneOffset.ofHours(8))
        val transport = FakeTransport(page(listOf(start to 999L), hasMore = false)).apply {
            refreshedPassToken = "rotated-pass-token"
        }
        val cache = MiFitnessStepsCache(storage)
        val service = service(store, transport, cache)
        val window = service.todayWindow()
        val oldSummary = MiFitnessStepsSummary(
            period = window.period,
            localDate = window.localDate,
            accountScope = oldCredential.accountScope,
            steps = 42L,
            recordCount = 1,
            observedAt = FIXED_CLOCK.millis(),
            lastSyncAt = FIXED_CLOCK.millis(),
        )
        cache.save(oldSummary)
        storage.writes.clear()
        storage.rejectCredentialWrites = true

        val result = service.syncToday()

        val error = result.exceptionOrNull() as MiFitnessStepsSyncException
        assertEquals("credential_write_failed", error.code)
        assertEquals("original-pass-token", store.read()?.passToken)
        assertEquals(oldSummary, cache.read(window.period, window.localDate, oldCredential.accountScope))
        assertFalse(error.toString().contains("rotated-pass-token"))
        assertTrue(
            storage.writes
                .filter { (_, value) -> value.contains("\"steps\"") }
                .none { (_, value) -> value.contains("rotated-pass-token") },
        )
    }

    @Test
    fun `repeated cursor fails closed without replacing cache`() = runTest {
        val fixture = fixture(
            page(emptyList(), hasMore = true, nextKey = "repeat"),
            page(emptyList(), hasMore = true, nextKey = "repeat"),
        )
        fixture.transport.refreshedPassToken = "rotated-pass-token"

        val result = fixture.service.syncToday()

        assertEquals("cursor_repeated", (result.exceptionOrNull() as MiFitnessStepsSyncException).code)
        assertEquals(2, fixture.transport.calls.size)
        assertEquals("synthetic-pass-token", fixture.store.read()?.passToken)
        assertTrue(fixture.storage.writes.isEmpty())
    }

    @Test
    fun `pagination stops at the hard page limit without caching partial data`() = runTest {
        val pages = Array(10) { index ->
            page(emptyList(), hasMore = true, nextKey = "cursor-${index + 1}")
        }
        val fixture = fixture(*pages)

        val result = fixture.service.syncToday()

        assertEquals("page_limit", (result.exceptionOrNull() as MiFitnessStepsSyncException).code)
        assertEquals(10, fixture.transport.calls.size)
        assertTrue(fixture.storage.writes.isEmpty())
    }

    @Test
    fun `empty step aggregate persists explicit metric states and refreshed token`() = runTest {
        val storage = RecordingSecretStorage()
        val store = MiFitnessCredentialStore(storage)
        store.save("12345", "synthetic-pass-token")
        val credential = checkNotNull(store.read())
        val cache = MiFitnessStepsCache(storage)
        val transport = FakeTransport(page(emptyList(), hasMore = false)).apply {
            refreshedPassToken = "rotated-pass-token"
        }
        val service = service(store, transport, cache)
        val window = service.todayWindow()
        val oldSummary = MiFitnessStepsSummary(
            period = window.period,
            localDate = window.localDate,
            accountScope = credential.accountScope,
            steps = 42L,
            recordCount = 1,
            observedAt = FIXED_CLOCK.millis() - 1_000L,
            lastSyncAt = FIXED_CLOCK.millis() - 1_000L,
        )
        cache.save(oldSummary)
        storage.writes.clear()

        val summary = service.syncToday().getOrThrow()

        assertEquals(null, summary.steps)
        assertEquals(0, summary.recordCount)
        assertEquals(HealthMetricStatus.EMPTY, summary.metricValues[HealthMetricKey.STEPS]?.status)
        assertEquals("rotated-pass-token", store.read()?.passToken)
        assertEquals(summary, cache.read(window.period, window.localDate, credential.accountScope))
        assertEquals(2, storage.writes.size)
        assertTrue(storage.writes.last().second.contains("\"passToken\":\"rotated-pass-token\""))
    }

    @Test
    fun `missing steps do not prevent other verified metrics from being cached`() = runTest {
        val storage = RecordingSecretStorage()
        val store = MiFitnessCredentialStore(storage)
        store.save("12345", "synthetic-pass-token")
        val cache = MiFitnessStepsCache(storage)
        val start = LocalDate.of(2026, 8, 27).atStartOfDay().toEpochSecond(ZoneOffset.ofHours(8))
        val transport = FakeTransport(page(emptyList(), hasMore = false)).apply {
            aggregateResponses["sleep"] = aggregatePage(
                key = "sleep",
                time = start,
                value = "{\"total_duration\":421}",
            )
        }

        val summary = service(store, transport, cache).syncToday().getOrThrow()

        assertEquals(null, summary.steps)
        assertEquals(HealthMetricStatus.EMPTY, summary.metricValues[HealthMetricKey.STEPS]?.status)
        assertEquals(421.0, summary.metricValues[HealthMetricKey.SLEEP_MINUTES]?.value ?: -1.0, 0.0)
        assertEquals(HealthMetricStatus.AVAILABLE, summary.metricValues[HealthMetricKey.SLEEP_MINUTES]?.status)
        assertTrue("sleep" in transport.aggregateCalls)
    }

    @Test
    fun `step trend follows pages deduplicates identical points and stays separate from daily total`() = runTest {
        val storage = RecordingSecretStorage()
        val store = MiFitnessCredentialStore(storage)
        store.save("12345", "synthetic-pass-token")
        val cache = MiFitnessStepsCache(storage)
        val start = LocalDate.of(2026, 8, 27).atStartOfDay().toEpochSecond(ZoneOffset.ofHours(8))
        val transport = FakeTransport(page(listOf(start to 974L), hasMore = false)).apply {
            seriesResponses.addLast(seriesPage(
                listOf(start + 3_600L to 120L, start + 1_800L to 40L),
                hasMore = true,
                nextKey = "series-next",
            ))
            seriesResponses.addLast(seriesPage(
                listOf(start + 3_600L to 120L, start + 7_200L to 16L),
                hasMore = false,
            ))
        }

        val summary = service(store, transport, cache).syncToday().getOrThrow()

        assertEquals(974L, summary.steps)
        val series = checkNotNull(summary.metricTimeSeries[HealthMetricKey.STEPS])
        assertEquals(HealthMetricStatus.AVAILABLE, series.status)
        assertEquals(listOf(40.0, 120.0, 16.0), series.points.map { it.value })
        assertEquals(listOf("", "series-next"), transport.seriesCalls)
        assertEquals(summary, cache.read(summary.period, summary.localDate, summary.accountScope))
    }

    @Test
    fun `partial step trend never changes authoritative daily aggregate`() = runTest {
        val storage = RecordingSecretStorage()
        val store = MiFitnessCredentialStore(storage)
        store.save("12345", "synthetic-pass-token")
        val start = LocalDate.of(2026, 8, 27).atStartOfDay().toEpochSecond(ZoneOffset.ofHours(8))
        val transport = FakeTransport(page(listOf(start to 974L), hasMore = false)).apply {
            seriesResponses.addLast(seriesPage(listOf(start + 1_800L to 40L), true, "repeat"))
            seriesResponses.addLast(seriesPage(listOf(start + 3_600L to 120L), true, "repeat"))
        }

        val summary = service(store, transport, MiFitnessStepsCache(storage)).syncToday().getOrThrow()

        assertEquals(974L, summary.steps)
        val series = checkNotNull(summary.metricTimeSeries[HealthMetricKey.STEPS])
        assertEquals(HealthMetricStatus.PARTIAL, series.status)
        assertEquals("cursor_repeated", series.reasonCode)
        assertEquals(listOf(40.0, 120.0), series.points.map { it.value })
    }

    @Test
    fun `out of window step trend is isolated as an error`() = runTest {
        val storage = RecordingSecretStorage()
        val store = MiFitnessCredentialStore(storage)
        store.save("12345", "synthetic-pass-token")
        val start = LocalDate.of(2026, 8, 27).atStartOfDay().toEpochSecond(ZoneOffset.ofHours(8))
        val transport = FakeTransport(page(listOf(start to 974L), hasMore = false)).apply {
            seriesResponses.addLast(seriesPage(listOf(start - 1L to 99L), hasMore = false))
        }

        val summary = service(store, transport, MiFitnessStepsCache(storage)).syncToday().getOrThrow()

        assertEquals(974L, summary.steps)
        val series = checkNotNull(summary.metricTimeSeries[HealthMetricKey.STEPS])
        assertEquals(HealthMetricStatus.ERROR, series.status)
        assertEquals("record_out_of_window", series.reasonCode)
        assertTrue(series.points.isEmpty())
    }

    @Test
    fun `empty metric snapshot and rejected refreshed token restore credentials and cache`() = runTest {
        val storage = RecordingSecretStorage()
        val store = MiFitnessCredentialStore(storage)
        store.save("12345", "synthetic-pass-token")
        val credential = checkNotNull(store.read())
        val cache = MiFitnessStepsCache(storage)
        val transport = FakeTransport(page(emptyList(), hasMore = false)).apply {
            refreshedPassToken = "rotated-pass-token"
        }
        val service = service(store, transport, cache)
        val window = service.todayWindow()
        val oldSummary = MiFitnessStepsSummary(
            period = window.period,
            localDate = window.localDate,
            accountScope = credential.accountScope,
            steps = 42L,
            recordCount = 1,
            observedAt = FIXED_CLOCK.millis() - 1_000L,
            lastSyncAt = FIXED_CLOCK.millis() - 1_000L,
        )
        cache.save(oldSummary)
        storage.writes.clear()
        storage.rejectCredentialWrites = true

        val result = service.syncToday()

        val error = result.exceptionOrNull() as MiFitnessStepsSyncException
        assertEquals("credential_write_failed", error.code)
        assertEquals("synthetic-pass-token", store.read()?.passToken)
        assertEquals(oldSummary, cache.read(window.period, window.localDate, credential.accountScope))
        assertFalse(error.toString().contains("rotated-pass-token"))
    }

    @Test
    fun `missing credentials and unexpected transport failures expose no secret`() = runTest {
        val emptyStorage = RecordingSecretStorage()
        val unusedTransport = FakeTransport()
        val emptyService = service(
            MiFitnessCredentialStore(emptyStorage),
            unusedTransport,
            MiFitnessStepsCache(emptyStorage),
        )
        val missing = emptyService.syncToday()
        assertEquals("credentials_missing", (missing.exceptionOrNull() as MiFitnessStepsSyncException).code)
        assertEquals(0, unusedTransport.exchangeCount)

        val storage = RecordingSecretStorage()
        val store = MiFitnessCredentialStore(storage)
        store.save("12345", "synthetic-pass-token")
        storage.writes.clear()
        val failingTransport = FakeTransport().apply {
            exchangeFailure = IllegalStateException("synthetic-pass-token raw-response")
        }
        val failed = service(store, failingTransport, MiFitnessStepsCache(storage)).syncToday()
        val error = failed.exceptionOrNull() as MiFitnessStepsSyncException
        assertEquals("sync_failed", error.code)
        assertFalse(error.message.orEmpty().contains("synthetic-pass-token"))
        assertFalse(error.toString().contains("raw-response"))
        assertTrue(storage.writes.isEmpty())
    }

    private fun fixture(vararg pages: String): Fixture {
        val storage = RecordingSecretStorage()
        val store = MiFitnessCredentialStore(storage)
        store.save("12345", "synthetic-pass-token")
        storage.writes.clear()
        val transport = FakeTransport(*pages)
        val service = service(store, transport, MiFitnessStepsCache(storage))
        return Fixture(storage, store, transport, service)
    }

    private fun service(
        store: MiFitnessCredentialStore,
        transport: MiFitnessStepsTransport,
        cache: MiFitnessStepsCache,
    ) = MiFitnessStepsSyncService(store, transport, cache, FIXED_CLOCK, ZoneOffset.ofHours(8))

    private data class Fixture(
        val storage: RecordingSecretStorage,
        val store: MiFitnessCredentialStore,
        val transport: FakeTransport,
        val service: MiFitnessStepsSyncService,
    )

    private data class FetchCall(
        val startEpochSeconds: Long,
        val endEpochSecondsExclusive: Long,
        val nextKey: String,
    )

    private class FakeTransport(vararg responses: String) : MiFitnessStepsTransport {
        private val responses = ArrayDeque(responses.toList())
        val calls = mutableListOf<FetchCall>()
        val aggregateCalls = mutableListOf<String>()
        val aggregateResponses = mutableMapOf<String, String>()
        val seriesCalls = mutableListOf<String>()
        val seriesResponses = ArrayDeque<String>()
        var exchangeCount = 0
        var exchangeFailure: RuntimeException? = null
        var refreshedPassToken: String? = null
        var authenticationFailuresRemaining = 0

        override suspend fun exchangePassToken(credential: MiFitnessCredential): MiFitnessSession {
            exchangeCount += 1
            exchangeFailure?.let { throw it }
            return MiFitnessSession(
                userId = credential.userId,
                cUserId = "synthetic-c-user",
                serviceToken = "synthetic-service-token",
                ssecurityBase64 = "c3NlY3VyaXR5LXZlY3Rvcg==",
                deviceId = "synthetic-device",
                tokenRefreshed = refreshedPassToken != null,
                refreshedPassToken = refreshedPassToken,
            )
        }

        override suspend fun fetchSteps(
            session: MiFitnessSession,
            startEpochSeconds: Long,
            endEpochSecondsExclusive: Long,
            nextKey: String,
        ): String {
            calls += FetchCall(startEpochSeconds, endEpochSecondsExclusive, nextKey)
            if (authenticationFailuresRemaining > 0) {
                authenticationFailuresRemaining -= 1
                throw MiFitnessAuthenticationException("synthetic expired session")
            }
            return checkNotNull(responses.removeFirstOrNull()) { "Unexpected fetch" }
        }

        override suspend fun fetchDailyAggregate(
            session: MiFitnessSession,
            metric: String,
            startEpochSeconds: Long,
            endEpochSecondsExclusive: Long,
            nextKey: String,
        ): String {
            if (metric == "steps") return fetchSteps(
                session,
                startEpochSeconds,
                endEpochSecondsExclusive,
                nextKey,
            )
            aggregateCalls += metric
            return aggregateResponses[metric]
                ?: "{\"code\":0,\"result\":{\"data_list\":[],\"has_more\":false,\"next_key\":\"\"}}"
        }

        override suspend fun fetchStepSeries(
            session: MiFitnessSession,
            startEpochSeconds: Long,
            endEpochSecondsExclusive: Long,
            nextKey: String,
        ): String {
            seriesCalls += nextKey
            return seriesResponses.removeFirstOrNull()
                ?: "{\"code\":0,\"result\":{\"data_list\":[],\"has_more\":false,\"next_key\":\"\"}}"
        }
    }

    private class RecordingSecretStorage : MiFitnessSecretStorage {
        val values = linkedMapOf<String, String>()
        val writes = mutableListOf<Pair<String, String>>()
        var rejectCredentialWrites = false

        override fun read(key: String): String = values[key].orEmpty()

        override fun write(key: String, value: String): Boolean {
            writes += key to value
            if (rejectCredentialWrites && value.contains("\"passToken\"")) return false
            if (value.isEmpty()) values.remove(key) else values[key] = value
            return true
        }
    }

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-27T04:00:00Z"), ZoneOffset.UTC)

        fun page(
            records: List<Pair<Long, Long>>,
            hasMore: Boolean,
            nextKey: String = "",
        ): String {
            val items = records.joinToString(",") { (time, steps) ->
                """{"tag":"daily_report","key":"steps","time":$time,"zone_offset":28800,"sid":"synthetic-aggregate","zone_name":"Asia/Shanghai","source_sid_list":["synthetic-source"],"value":"{\"steps\":$steps,\"distance\":${steps / 2},\"calories\":${steps / 20}}"}"""
            }
            return """{"code":0,"result":{"data_list":[$items],"has_more":$hasMore,"next_key":"$nextKey"}}"""
        }

        fun aggregatePage(key: String, time: Long, value: String): String =
            """{"code":0,"result":{"data_list":[{"tag":"daily_report","key":"$key","time":$time,"zone_offset":28800,"source_sid_list":["synthetic-source"],"value":$value}],"has_more":false,"next_key":""}}"""

        fun seriesPage(
            points: List<Pair<Long, Long>>,
            hasMore: Boolean,
            nextKey: String = "",
        ): String {
            val items = points.joinToString(",") { (time, steps) ->
                """{"time":$time,"key":"steps","value":"{\"steps\":$steps}"}"""
            }
            return """{"code":0,"result":{"data_list":[$items],"has_more":$hasMore,"next_key":"$nextKey"}}"""
        }
    }
}
