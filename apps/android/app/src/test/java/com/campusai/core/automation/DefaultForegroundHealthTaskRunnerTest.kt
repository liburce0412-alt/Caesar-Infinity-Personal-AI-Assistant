package com.campusai.core.automation

import com.campusai.core.ai.CloudAiProvider
import com.campusai.core.ai.CloudDailyHealthSummary
import com.campusai.core.health.HealthFreshness
import com.campusai.core.health.HealthMetricKey
import com.campusai.core.health.HealthMetricProvenance
import com.campusai.core.health.HealthMetricStatus
import com.campusai.core.health.HealthMetricUnit
import com.campusai.core.health.HealthMetricValue
import com.campusai.core.health.HealthMetrics
import com.campusai.core.health.HealthPeriod
import com.campusai.core.health.HealthSnapshot
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultForegroundHealthTaskRunnerTest {
    @Test fun health_summary_and_saved_messages_reach_progress_in_order() = runTest {
        val stages = mutableListOf<Pair<HealthTaskStage, String?>>()
        val progress = object : HealthTaskProgressPublisher {
            override suspend fun publish(stage: HealthTaskStage, preview: String?) { stages += stage to preview }
            override fun clear() = Unit
        }
        val fixture = fixture(progress = progress)
        fixture.runner.run(TASK_ID)
        assertEquals(listOf(HealthTaskStage.SYNCING, HealthTaskStage.REFRESHED,
            HealthTaskStage.GENERATING, HealthTaskStage.SENDING, HealthTaskStage.COMPLETE), stages.map { it.first })
        assertTrue(stages[1].second!!.contains("974"))
        assertEquals(fixture.writer.batches.single().joinToString("\n"), stages[3].second)
    }

    @Test fun sync_only_requests_device_and_refreshes_without_ai_conversation_or_messages() = runTest {
        val fixture = fixture()
        fixture.store.config = fixture.store.config.copy(includeHealthSummary = false,
            options = HealthTaskOptions(syncOnly = true))
        assertEquals(ForegroundHealthTaskRunResult.Synced, fixture.runner.run(TASK_ID))
        assertEquals(1, fixture.source.deviceRequests)
        assertTrue(fixture.source.requestedBeforeRead)
        assertEquals(0, fixture.ai.calls)
        assertTrue(fixture.writer.batches.isEmpty())
        assertTrue(fixture.notifier.batches.isEmpty())
        assertEquals(ScheduledTaskRunStatus.SYNCED, fixture.store.config.lastStatus)
    }

    @Test fun free_cloud_and_local_use_their_selected_route_without_personal_cloud_fallback() = runTest {
        for (mode in listOf(HealthTaskAiMode.CAESAR_CLOUD_FREE, HealthTaskAiMode.LOCAL)) {
            val fixture = fixture()
            fixture.store.config = fixture.store.config.copy(
                modelId = if (mode == HealthTaskAiMode.LOCAL) "qwen3.5-2b-mnn" else ScheduledTaskConfig.CLOUD_FREE_MODEL,
                includeHealthSummary = mode != HealthTaskAiMode.LOCAL,
                options = HealthTaskOptions(aiMode = mode, cloudFreeOwnerUserId = "owner"),
            )
            assertTrue(fixture.runner.run(TASK_ID) is ForegroundHealthTaskRunResult.Updated)
            assertEquals(1, fixture.source.deviceRequests)
            assertTrue(fixture.source.requestedBeforeRead)
            assertEquals(0, fixture.ai.calls)
            assertEquals(if (mode == HealthTaskAiMode.LOCAL) 1 else 0, fixture.ai.localCalls)
            assertEquals(if (mode == HealthTaskAiMode.CAESAR_CLOUD_FREE) 1 else 0, fixture.ai.freeCalls)
        }
    }

    @Test fun changing_to_sync_only_before_free_cloud_submission_revokes_the_old_send() = runTest {
        val fixture = fixture()
        fixture.store.config = fixture.store.config.copy(modelId = ScheduledTaskConfig.CLOUD_FREE_MODEL,
            options = HealthTaskOptions(aiMode = HealthTaskAiMode.CAESAR_CLOUD_FREE, cloudFreeOwnerUserId = "owner"))
        fixture.ai.beforeSubmission = {
            fixture.store.config = fixture.store.config.copy(options = fixture.store.config.options.copy(syncOnly = true))
        }
        assertEquals(ForegroundHealthTaskRunResult.Superseded, fixture.runner.run(TASK_ID))
        assertEquals(0, fixture.ai.submissions)
        assertTrue(fixture.writer.batches.isEmpty())
        assertTrue(fixture.notifier.batches.isEmpty())
    }

    @Test fun explicit_cloud_only_setting_skips_device_request() = runTest {
        val fixture = fixture()
        fixture.store.config = fixture.store.config.copy(options = HealthTaskOptions(syncBeforeMessage = false))
        fixture.runner.run(TASK_ID)
        assertEquals(0, fixture.source.deviceRequests)
        assertEquals(1, fixture.source.calls)
    }

    @Test
    fun changed_revision_generates_batch_and_persists_revision() = runTest {
        val fixture = fixture()

        val result = fixture.runner.run(TASK_ID)

        assertTrue(result is ForegroundHealthTaskRunResult.Updated)
        assertEquals(1, fixture.ai.calls)
        assertEquals(listOf(2), fixture.writer.batches.map { it.size })
        assertEquals(listOf(2), fixture.notifier.batches.map { it.size })
        assertEquals(fixture.source.observation.revision.value, fixture.store.config.lastSourceRevision)
        assertEquals(ScheduledTaskRunStatus.UPDATED, fixture.store.config.lastStatus)
    }

    @Test
    fun unchanged_revision_skips_ai_and_sends_one_safe_status() = runTest {
        val fixture = fixture(lastRevision = observation().revision.value)

        val result = fixture.runner.run(TASK_ID, force = true)

        assertTrue(result is ForegroundHealthTaskRunResult.Unchanged)
        assertEquals(0, fixture.ai.calls)
        assertEquals(1, fixture.writer.batches.single().size)
        assertFalse(fixture.writer.batches.single().single().contains("健康提醒"))
        assertEquals(ScheduledTaskRunStatus.UNCHANGED, fixture.store.config.lastStatus)
    }

    @Test
    fun not_due_does_not_touch_cloud() = runTest {
        val fixture = fixture(lastAttemptAt = NOW - 60_000L)

        assertTrue(fixture.runner.run(TASK_ID) is ForegroundHealthTaskRunResult.NotDue)
        assertEquals(0, fixture.source.calls)
        assertEquals(0, fixture.ai.calls)
    }

    @Test
    fun provider_failure_does_not_advance_revision_or_switch_provider() = runTest {
        val fixture = fixture(aiFailure = HealthTaskException("task_model_unavailable", "unavailable"))

        val result = fixture.runner.run(TASK_ID)

        assertTrue(result is ForegroundHealthTaskRunResult.Failed)
        assertEquals(CloudAiProvider.GOOGLE_GEMINI, fixture.ai.lastProvider)
        assertEquals("gemini-2.5-flash", fixture.ai.lastModel)
        assertEquals(null, fixture.store.config.lastSourceRevision)
        assertEquals("task_model_unavailable", fixture.store.config.lastErrorCode)
        assertEquals(1, fixture.writer.batches.single().size)
    }

    @Test
    fun transient_optional_metric_failure_never_advances_revision_or_calls_ai() = runTest {
        val previousRevision = "a".repeat(64)
        val base = observation()
        val failedMetric = base.snapshot.metricValues.getValue(HealthMetricKey.STEPS).copy(
            value = null,
            status = HealthMetricStatus.ERROR,
            reasonCode = "rate_limited",
        )
        val partial = base.copy(
            snapshot = base.snapshot.copy(
                metrics = HealthMetrics(),
                metricValues = mapOf(HealthMetricKey.STEPS to failedMetric),
            ),
        )
        val fixture = fixture(lastRevision = previousRevision, sourceObservation = partial)

        val result = fixture.runner.run(TASK_ID, force = true)

        assertTrue(result is ForegroundHealthTaskRunResult.Failed)
        assertEquals("rate_limited", (result as ForegroundHealthTaskRunResult.Failed).code)
        assertEquals(0, fixture.ai.calls)
        assertEquals(previousRevision, fixture.store.config.lastSourceRevision)
        assertEquals(ScheduledTaskRunStatus.ERROR, fixture.store.config.lastStatus)
    }

    @Test
    fun a_missing_optional_field_keeps_available_daily_metrics_usable() = runTest {
        val base = observation()
        val partialRestingHeartRate = HealthMetricValue(
            value = null,
            unit = HealthMetricUnit.BEATS_PER_MINUTE,
            status = HealthMetricStatus.PARTIAL,
            provenance = base.snapshot.metricValues.getValue(HealthMetricKey.STEPS).provenance,
            reasonCode = "field_missing",
        )
        val observation = base.copy(
            snapshot = base.snapshot.copy(
                metricValues = base.snapshot.metricValues +
                    (HealthMetricKey.RESTING_HEART_RATE_BPM to partialRestingHeartRate),
            ),
        )
        val fixture = fixture(sourceObservation = observation)

        val result = fixture.runner.run(TASK_ID, force = true)

        assertTrue(result is ForegroundHealthTaskRunResult.Updated)
        assertEquals(1, fixture.ai.calls)
        assertEquals(observation.revision.value, fixture.store.config.lastSourceRevision)
    }

    @Test
    fun concurrent_runs_are_serialized() = runTest {
        val fixture = fixture(sourceYield = true)

        val first = async { fixture.runner.run(TASK_ID, force = true) }
        val second = async { fixture.runner.run(TASK_ID, force = true) }
        first.await()
        second.await()

        assertEquals(1, fixture.source.maxActive)
    }

    @Test
    fun configChangedDuringRunIsNeverOverwrittenByOldResult() = runTest {
        val fixture = fixture(sourceYield = true)
        fixture.source.afterRefresh = {
            fixture.store.config = fixture.store.config.copy(enabled = false)
        }

        val result = fixture.runner.run(TASK_ID, force = true)

        assertTrue(result is ForegroundHealthTaskRunResult.Superseded)
        assertFalse(fixture.store.config.enabled)
        assertTrue(fixture.writer.batches.isEmpty())
        assertTrue(fixture.notifier.batches.isEmpty())
    }

    @Test
    fun disabling_after_model_validation_prevents_http_submission() = runTest {
        val fixture = fixture()
        fixture.ai.beforeSubmission = {
            fixture.store.mutateWithRetry(TASK_ID, transform = ::mergeDisabledHealthTaskConfig)
                .getOrThrow()
        }

        val result = fixture.runner.run(TASK_ID, force = true)

        assertTrue(result is ForegroundHealthTaskRunResult.Superseded)
        assertEquals(0, fixture.ai.submissions)
        assertFalse(fixture.store.config.enabled)
        assertTrue(fixture.writer.batches.isEmpty())
        assertTrue(fixture.notifier.batches.isEmpty())
    }

    @Test
    fun disabling_after_submission_does_not_wait_for_the_provider_response() = runTest {
        val fixture = fixture()
        val submitted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        fixture.ai.afterSubmission = {
            submitted.complete(Unit)
            releaseResponse.await()
        }

        val running = async { fixture.runner.run(TASK_ID, force = true) }
        submitted.await()
        fixture.store.mutateWithRetry(TASK_ID, transform = ::mergeDisabledHealthTaskConfig)
            .getOrThrow()

        assertFalse(fixture.store.config.enabled)
        releaseResponse.complete(Unit)
        assertTrue(running.await() is ForegroundHealthTaskRunResult.Superseded)
        assertTrue(fixture.writer.batches.isEmpty())
        assertTrue(fixture.notifier.batches.isEmpty())
    }

    @Test
    fun separate_runner_instances_share_one_process_serialization_lock() = runTest {
        val fixture = fixture(sourceYield = true)
        val secondRunner = DefaultForegroundHealthTaskRunner(
            fixture.store,
            fixture.source,
            fixture.ai,
            fixture.writer,
            fixture.notifier,
        ) { NOW }

        val first = async { fixture.runner.run(TASK_ID, force = true) }
        val second = async { secondRunner.run(TASK_ID, force = true) }
        first.await()
        second.await()

        assertEquals(1, fixture.source.maxActive)
    }

    @Test
    fun lifecycleStopAfterFinalCommitFinishesRoomWriteButDoesNotNotifyOrRepeatChangedBatch() = runTest {
        val fixture = fixture()
        val writerEntered = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        fixture.writer.beforeAppend = {
            writerEntered.complete(Unit)
            releaseWriter.await()
        }

        val job = launch { fixture.runner.run(TASK_ID, force = true) }
        writerEntered.await()
        job.cancel()
        releaseWriter.complete(Unit)
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(listOf(2), fixture.writer.batches.map(List<String>::size))
        assertTrue(fixture.notifier.batches.isEmpty())
        assertEquals(ScheduledTaskRunStatus.UPDATED, fixture.store.config.lastStatus)
        assertEquals(fixture.source.observation.revision.value, fixture.store.config.lastSourceRevision)
    }

    @Test
    fun disablingBetweenNotificationMessagesStopsTheRemainingDelivery() = runTest {
        val fixture = fixture()
        fixture.notifier.afterMessage = { index ->
            if (index == 0) {
                fixture.store.mutateWithRetry(TASK_ID, transform = ::mergeDisabledHealthTaskConfig)
                    .getOrThrow()
            }
        }

        val result = fixture.runner.run(TASK_ID, force = true)

        assertTrue(result is ForegroundHealthTaskRunResult.Superseded)
        assertEquals(listOf(1), fixture.notifier.batches.map(List<String>::size))
        assertFalse(fixture.store.config.enabled)
    }

    @Test
    fun unexpectedExceptionIsRecoveredToErrorWithoutLosingThePreviousRevision() = runTest {
        val previousRevision = "e".repeat(64)
        val fixture = fixture(lastRevision = previousRevision)
        fixture.source.thrownFailure = IllegalStateException("unexpected")

        val result = fixture.runner.run(TASK_ID, force = true)

        assertTrue(result is ForegroundHealthTaskRunResult.Failed)
        assertEquals("task_failed", (result as ForegroundHealthTaskRunResult.Failed).code)
        assertEquals(ScheduledTaskRunStatus.ERROR, fixture.store.config.lastStatus)
        assertEquals(previousRevision, fixture.store.config.lastSourceRevision)
    }

    @Test
    fun writerThrowAfterFinalCommitBecomesRecoverableErrorAndDoesNotNotify() = runTest {
        val fixture = fixture()
        fixture.writer.thrownFailure = IllegalStateException("room unavailable")

        val result = fixture.runner.run(TASK_ID, force = true)

        assertTrue(result is ForegroundHealthTaskRunResult.Failed)
        assertEquals("task_conversation_write_failed", (result as ForegroundHealthTaskRunResult.Failed).code)
        assertEquals(ScheduledTaskRunStatus.ERROR, fixture.store.config.lastStatus)
        assertEquals(null, fixture.store.config.lastSourceRevision)
        assertTrue(fixture.notifier.batches.isEmpty())
    }

    private fun fixture(
        lastRevision: String? = null,
        lastAttemptAt: Long? = null,
        aiFailure: Throwable? = null,
        sourceYield: Boolean = false,
        sourceObservation: HealthCloudObservation = observation(),
        progress: HealthTaskProgressPublisher = HealthTaskProgressPublisher.None,
    ): Fixture {
        val config = task(lastRevision, lastAttemptAt)
        val store = FakeStore(config)
        val source = FakeSource(sourceObservation, sourceYield)
        val ai = FakeAi(aiFailure)
        val writer = FakeWriter()
        val notifier = FakeNotifier()
        return Fixture(
            runner = DefaultForegroundHealthTaskRunner(store, source, ai, writer, notifier, progress) { NOW },
            store = store,
            source = source,
            ai = ai,
            writer = writer,
            notifier = notifier,
        )
    }

    private fun task(lastRevision: String?, lastAttemptAt: Long?) = ScheduledTaskConfig(
        id = TASK_ID,
        type = ScheduledTaskType.HEALTH_CLOUD_STATUS,
        enabled = true,
        intervalMinutes = 5,
        provider = CloudAiProvider.GOOGLE_GEMINI,
        modelId = "gemini-2.5-flash",
        includeHealthSummary = true,
        lastAttemptAt = lastAttemptAt,
        lastSourceRevision = lastRevision,
    )

    private fun observation(): HealthCloudObservation {
        val metric = HealthMetricValue(
            value = 974.0,
            unit = HealthMetricUnit.COUNT,
            status = HealthMetricStatus.AVAILABLE,
            provenance = HealthMetricProvenance(
                sourceId = "mi_fitness_cloud_cn",
                endpoint = "/aggregate",
                aggregation = "vendor_daily_aggregate",
                vendorKey = "steps",
                sourceCount = 1,
            ),
        )
        return HealthCloudObservation(
            localDate = "2026-08-28",
            snapshot = HealthSnapshot(
                originPackages = setOf("mi_fitness_cloud_cn"),
                period = HealthPeriod(0L, 86_399_999L, "today"),
                observedAt = NOW,
                lastSyncAt = NOW,
                freshness = HealthFreshness.LIVE,
                metrics = HealthMetrics(steps = 974L),
                missingFields = emptySet(),
                confidence = .95,
                metricValues = mapOf(HealthMetricKey.STEPS to metric),
            ),
        )
    }

    private class FakeStore(var config: ScheduledTaskConfig) : ScheduledTaskStore {
        override suspend fun read(taskId: String) = config.takeIf { it.id == taskId }
        override suspend fun readAll() = listOf(config)
        override suspend fun save(config: ScheduledTaskConfig): Result<Unit> {
            this.config = config
            return Result.success(Unit)
        }
        override suspend fun delete(taskId: String) = Result.success(Unit)
    }

    private class FakeSource(
        val observation: HealthCloudObservation,
        private val shouldYield: Boolean,
    ) : ForegroundHealthCloudSource {
        var afterRefresh: (() -> Unit)? = null
        var thrownFailure: RuntimeException? = null
        var calls = 0
        var deviceRequests = 0
        var requestedBeforeRead = false
        var maxActive = 0
        private val active = AtomicInteger()

        override suspend fun requestDeviceSync(withSubmissionLease: suspend (() -> Unit) -> Boolean): com.campusai.core.health.mifitness.MiFitnessDeviceSyncStatus {
            withSubmissionLease { deviceRequests++ }
            return com.campusai.core.health.mifitness.MiFitnessDeviceSyncStatus.REQUESTED
        }

        override suspend fun refreshToday(): Result<HealthCloudObservation> {
            requestedBeforeRead = deviceRequests > 0
            thrownFailure?.let { throw it }
            calls++
            maxActive = maxOf(maxActive, active.incrementAndGet())
            if (shouldYield) repeat(5) { yield() }
            active.decrementAndGet()
            afterRefresh?.invoke()
            return Result.success(observation)
        }
    }

    private class FakeAi(private val failure: Throwable?) : HealthAutoMessageClient {
        var freeCalls = 0
        var localCalls = 0
        var calls = 0
        var submissions = 0
        var lastProvider: CloudAiProvider? = null
        var lastModel: String? = null
        var beforeSubmission: suspend () -> Unit = {}
        var afterSubmission: suspend () -> Unit = {}
        override suspend fun generateCaesarCloudFree(ownerUserId: String, summary: CloudDailyHealthSummary,
            withSubmissionLease: suspend (() -> Unit) -> Boolean): Result<AutoMessageBatch> {
            freeCalls++
            assertEquals("owner", ownerUserId)
            return alternateBatch(withSubmissionLease)
        }
        override suspend fun generateLocal(modelId: String, summary: CloudDailyHealthSummary,
            withSubmissionLease: suspend (() -> Unit) -> Boolean): Result<AutoMessageBatch> {
            localCalls++
            assertEquals("qwen3.5-2b-mnn", modelId)
            return alternateBatch(withSubmissionLease)
        }
        private suspend fun alternateBatch(lease: suspend (() -> Unit) -> Boolean): Result<AutoMessageBatch> {
            beforeSubmission()
            if (!lease { submissions++ }) return Result.failure(HealthTaskException("request_superseded", "superseded"))
            return Result.success(AutoMessageBatch(listOf("今天走得不少呀", "继续保持就好")))
        }
        override suspend fun validate(provider: CloudAiProvider, modelId: String): Result<Unit> =
            failure?.let { Result.failure(it) } ?: Result.success(Unit)

        override suspend fun generate(
            provider: CloudAiProvider,
            modelId: String,
            summary: CloudDailyHealthSummary,
            withSubmissionLease: suspend (submit: () -> Unit) -> Boolean,
        ): Result<AutoMessageBatch> {
            calls++
            lastProvider = provider
            lastModel = modelId
            failure?.let { return Result.failure(it) }
            beforeSubmission()
            if (!withSubmissionLease { submissions++ }) {
                return Result.failure(HealthTaskException("request_superseded", "superseded"))
            }
            afterSubmission()
            return Result.success(AutoMessageBatch(listOf("今天走得不少呀", "继续保持就好")))
        }
    }

    private class FakeWriter : HealthTaskConversationWriter {
        val batches = mutableListOf<List<String>>()
        var beforeAppend: suspend () -> Unit = {}
        var thrownFailure: RuntimeException? = null
        override suspend fun appendAssistantMessages(
            task: ScheduledTaskConfig,
            messages: List<String>,
            createdAt: Long,
            execution: com.campusai.core.ai.ResolvedExecution?,
        ): Result<String> {
            beforeAppend()
            thrownFailure?.let { throw it }
            batches += messages
            return Result.success(HealthTaskNotificationContract.conversationId(task.id))
        }
    }

    private class FakeNotifier : HealthTaskNotificationPublisher {
        val batches = mutableListOf<List<String>>()
        var afterMessage: suspend (Int) -> Unit = {}
        override suspend fun publish(
            taskId: String,
            messages: List<String>,
            emittedAt: Long,
            deliveryId: String,
            withValidityLease: suspend (() -> Unit) -> Boolean,
        ): HealthTaskNotificationDelivery {
            val delivered = mutableListOf<String>()
            messages.forEachIndexed { index, message ->
                if (!withValidityLease { delivered += message }) {
                    batches += delivered
                    return HealthTaskNotificationDelivery.Superseded
                }
                afterMessage(index)
            }
            batches += delivered
            return HealthTaskNotificationDelivery.Delivered
        }
    }

    private data class Fixture(
        val runner: ForegroundHealthTaskRunner,
        val store: FakeStore,
        val source: FakeSource,
        val ai: FakeAi,
        val writer: FakeWriter,
        val notifier: FakeNotifier,
    )

    private companion object {
        const val TASK_ID = "daily"
        const val NOW = 2_000_000L
    }
}
