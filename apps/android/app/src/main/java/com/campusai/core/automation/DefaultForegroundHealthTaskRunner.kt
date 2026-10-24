package com.campusai.core.automation

import com.campusai.core.ai.ResolvedExecution
import com.campusai.core.health.mifitness.MiFitnessStepsSyncException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DefaultForegroundHealthTaskRunner(
    private val store: ScheduledTaskStore,
    private val source: ForegroundHealthCloudSource,
    private val aiClient: HealthAutoMessageClient,
    private val conversationWriter: HealthTaskConversationWriter,
    private val notificationPublisher: HealthTaskNotificationPublisher,
    private val progress: HealthTaskProgressPublisher = HealthTaskProgressPublisher.None,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ForegroundHealthTaskRunner {
    private val statusRotation = AtomicInteger()

    override suspend fun run(taskId: String, force: Boolean, background: Boolean): ForegroundHealthTaskRunResult = PROCESS_MUTEX.withLock {
        val config = try {
            store.read(taskId)
        } catch (failure: Exception) {
            return@withLock failedStoreRead(failure)
        } ?: return@withLock ForegroundHealthTaskRunResult.Failed("task_not_found", "定时任务不存在。")
        if (!config.enabled) return@withLock ForegroundHealthTaskRunResult.Disabled
        if (background && !config.options.runInBackground) return@withLock ForegroundHealthTaskRunResult.Disabled
        if (config.type != ScheduledTaskType.HEALTH_CLOUD_STATUS) {
            return@withLock ForegroundHealthTaskRunResult.Failed("task_type_unsupported", "定时任务类型不受支持。")
        }
        val now = nowMillis()
        if (!force && !config.isDue(now)) return@withLock ForegroundHealthTaskRunResult.NotDue

        val running = config.copy(
            lastAttemptAt = now,
            lastStatus = ScheduledTaskRunStatus.RUNNING,
            lastErrorCode = null,
        )
        val started = store.saveIfCurrent(config, running).getOrElse { failure ->
            return@withLock failedStoreWrite(failure)
        }
        if (!started) return@withLock ForegroundHealthTaskRunResult.Superseded
        try {
            val result = execute(running, now)
            if (result == ForegroundHealthTaskRunResult.Superseded) progress.clear()
            else showProgress(if (result is ForegroundHealthTaskRunResult.Failed) HealthTaskStage.ERROR else HealthTaskStage.COMPLETE)
            result
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { progress.clear(); rollbackOwnedAttempt(running, config) }
            throw cancelled
        } catch (failure: Exception) {
            showProgress(HealthTaskStage.ERROR)
            withContext(NonCancellable) { recoverUnexpectedFailure(running, failure) }
        }
    }

    private suspend fun execute(
        running: ScheduledTaskConfig,
        now: Long,
    ): ForegroundHealthTaskRunResult {
        showProgress(HealthTaskStage.SYNCING)
        val deviceStatus = if (running.options.syncBeforeMessage || running.options.syncOnly) {
            if (!stillOwnsAttempt(running)) return ForegroundHealthTaskRunResult.Superseded
            source.requestDeviceSync { submit -> withOwnedSubmissionLease(running, submit) }.also {
                if (!stillOwnsAttempt(running)) return ForegroundHealthTaskRunResult.Superseded
            }
        } else null
        val observation = source.refreshToday().getOrElse { failure ->
            return fail(running, now, failureCode(failure), "小米云这次没连上")
        }
        if (!stillOwnsAttempt(running)) return ForegroundHealthTaskRunResult.Superseded
        observation.authoritativeRevisionFailureCode()?.let { code ->
            return fail(running, now, code, "小米云这次没同步完整")
        }
        val revision = observation.revision.value
        showProgress(HealthTaskStage.REFRESHED, healthTaskSummaryPreview(observation.allowedCloudSummary()))
        if (running.options.syncOnly) {
            val code = deviceStatus?.takeUnless {
                it == com.campusai.core.health.mifitness.MiFitnessDeviceSyncStatus.REQUESTED
            }?.let { "device_sync_${it.name.lowercase(java.util.Locale.ROOT)}" }
            return when (val commit = commitAttempt(running, revision, ScheduledTaskRunStatus.SYNCED, code)) {
                is AttemptCommit.Saved -> ForegroundHealthTaskRunResult.Synced
                AttemptCommit.Superseded -> ForegroundHealthTaskRunResult.Superseded
                is AttemptCommit.Failed -> commit.result
            }
        }
        if (revision == running.lastSourceRevision) {
            return deliverStatus(running, now, revision, unchangedMessage())
        }

        if (!running.includeHealthSummary && running.options.aiMode != HealthTaskAiMode.LOCAL) {
            return fail(running, now, "health_summary_not_authorized", "数据更新了，但还没授权摘要")
        }
        val summary = observation.allowedCloudSummary()
        if (!summary.hasAnyValue()) {
            return deliverStatus(running, now, revision, "小米云暂时还没有今天的数据")
        }
        showProgress(HealthTaskStage.GENERATING)
        val batch = (if (running.options.aiMode == HealthTaskAiMode.LOCAL) aiClient.generateLocal(
            modelId = running.modelId,
            summary = summary,
            withSubmissionLease = { submit -> withOwnedSubmissionLease(running, submit) },
        ) else if (running.options.aiMode == HealthTaskAiMode.CAESAR_CLOUD_FREE) aiClient.generateCaesarCloudFree(
            ownerUserId = running.options.cloudFreeOwnerUserId,
            summary = summary,
            withSubmissionLease = { submit -> withOwnedSubmissionLease(running, submit) },
        ) else aiClient.generate(
            provider = running.provider,
            modelId = running.modelId,
            summary = summary,
            withSubmissionLease = { submit -> withOwnedSubmissionLease(running, submit) },
        )).getOrElse { failure ->
            return fail(running, now, failureCode(failure), "数据更新了，AI 这次没接上")
        }
        if (!stillOwnsAttempt(running)) return ForegroundHealthTaskRunResult.Superseded

        val committed = when (
            val commit = commitAttempt(running, revision, ScheduledTaskRunStatus.UPDATED, null)
        ) {
            is AttemptCommit.Saved -> commit.config
            AttemptCommit.Superseded -> return ForegroundHealthTaskRunResult.Superseded
            is AttemptCommit.Failed -> return commit.result
        }
        val conversationId = appendCommitted(
            committed = committed,
            messages = batch.messages,
            createdAt = now,
            execution = batch.execution,
        ).getOrElse {
            return recoverConversationFailure(running, committed)
        } ?: return ForegroundHealthTaskRunResult.Superseded

        return when (publishCommitted(committed, batch.messages, now)) {
            HealthTaskNotificationDelivery.Delivered -> ForegroundHealthTaskRunResult.Updated(
                conversationId = conversationId,
                messageCount = batch.messages.size,
                notificationsEnabled = true,
            )
            HealthTaskNotificationDelivery.PermissionDisabled -> {
                markNotificationFailure(committed, "notification_permission_disabled")
                ForegroundHealthTaskRunResult.Updated(conversationId, batch.messages.size, false)
            }
            HealthTaskNotificationDelivery.Failed ->
                ForegroundHealthTaskRunResult.Updated(conversationId, batch.messages.size, false)
            HealthTaskNotificationDelivery.Superseded -> ForegroundHealthTaskRunResult.Superseded
        }
    }

    private suspend fun deliverStatus(
        running: ScheduledTaskConfig,
        now: Long,
        revision: String,
        message: String,
    ): ForegroundHealthTaskRunResult {
        val committed = when (
            val commit = commitAttempt(running, revision, ScheduledTaskRunStatus.UNCHANGED, null)
        ) {
            is AttemptCommit.Saved -> commit.config
            AttemptCommit.Superseded -> return ForegroundHealthTaskRunResult.Superseded
            is AttemptCommit.Failed -> return commit.result
        }
        val conversationId = appendCommitted(committed, listOf(message), now, null).getOrElse {
            return recoverConversationFailure(running, committed)
        } ?: return ForegroundHealthTaskRunResult.Superseded

        return when (publishCommitted(committed, listOf(message), now)) {
            HealthTaskNotificationDelivery.Delivered ->
                ForegroundHealthTaskRunResult.Unchanged(conversationId, true)
            HealthTaskNotificationDelivery.PermissionDisabled -> {
                markNotificationFailure(committed, "notification_permission_disabled")
                ForegroundHealthTaskRunResult.Unchanged(conversationId, false)
            }
            HealthTaskNotificationDelivery.Failed ->
                ForegroundHealthTaskRunResult.Unchanged(conversationId, false)
            HealthTaskNotificationDelivery.Superseded -> ForegroundHealthTaskRunResult.Superseded
        }
    }

    private suspend fun fail(
        running: ScheduledTaskConfig,
        now: Long,
        code: String,
        statusMessage: String,
    ): ForegroundHealthTaskRunResult {
        val safeCode = code.takeIf(ERROR_CODE_PATTERN::matches) ?: "task_failed"
        val committed = when (
            val commit = commitAttempt(
                running,
                running.lastSourceRevision,
                ScheduledTaskRunStatus.ERROR,
                safeCode,
            )
        ) {
            is AttemptCommit.Saved -> commit.config
            AttemptCommit.Superseded -> return ForegroundHealthTaskRunResult.Superseded
            is AttemptCommit.Failed -> return commit.result
        }
        if (running.options.syncOnly) return ForegroundHealthTaskRunResult.Failed(safeCode, failureMessage(safeCode))
        val conversationId = appendCommitted(committed, listOf(statusMessage), now, null).getOrNull()
        if (conversationId != null) {
            when (publishCommitted(committed, listOf(statusMessage), now)) {
                HealthTaskNotificationDelivery.Superseded -> return ForegroundHealthTaskRunResult.Superseded
                else -> Unit
            }
        }
        return ForegroundHealthTaskRunResult.Failed(safeCode, failureMessage(safeCode))
    }

    /**
     * Runtime state is committed before Room is touched. The NonCancellable section
     * then gives the single committed attempt an at-most-once Room append across
     * lifecycle STOP/cancellation; a restart sees the committed source revision.
     */
    private suspend fun appendCommitted(
        committed: ScheduledTaskConfig,
        messages: List<String>,
        createdAt: Long,
        execution: ResolvedExecution?,
    ): Result<String?> = withContext(NonCancellable) {
        ScheduledTaskMutationCoordinator.withLock {
            runCatching {
                if (!isCommittedOutcome(committed)) return@runCatching null
                conversationWriter.appendAssistantMessages(committed, messages, createdAt, execution).getOrThrow()
            }
        }
    }

    private suspend fun recoverConversationFailure(
        running: ScheduledTaskConfig,
        committed: ScheduledTaskConfig,
    ): ForegroundHealthTaskRunResult = withContext(NonCancellable) {
        val recovered = mutateCommittedOutcome(committed) { current ->
            current.copy(
                lastSourceRevision = running.lastSourceRevision,
                lastStatus = ScheduledTaskRunStatus.ERROR,
                lastErrorCode = "task_conversation_write_failed",
            )
        }
        when (recovered) {
            MutationOutcome.Saved -> ForegroundHealthTaskRunResult.Failed(
                "task_conversation_write_failed",
                "自动消息无法保存。",
            )
            MutationOutcome.Superseded -> ForegroundHealthTaskRunResult.Superseded
            MutationOutcome.Failed -> ForegroundHealthTaskRunResult.Failed(
                "task_store_write_failed",
                "定时任务状态无法保存。",
            )
        }
    }

    private suspend fun publishCommitted(
        committed: ScheduledTaskConfig,
        messages: List<String>,
        emittedAt: Long,
    ): HealthTaskNotificationDelivery {
        currentCoroutineContext().ensureActive()
        if (!isCommittedOutcome(committed)) return HealthTaskNotificationDelivery.Superseded
        showProgress(HealthTaskStage.SENDING, messages.joinToString("\n"))
        return try {
            notificationPublisher.publish(
                taskId = committed.id,
                messages = messages,
                emittedAt = emittedAt,
                deliveryId = deliveryId(committed),
                withValidityLease = { deliver ->
                    ScheduledTaskMutationCoordinator.withLock {
                        if (!isCommittedOutcome(committed)) {
                            false
                        } else {
                            deliver()
                            true
                        }
                    }
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            markNotificationFailure(committed, "notification_delivery_failed")
            HealthTaskNotificationDelivery.Failed
        }
    }

    private suspend fun markNotificationFailure(committed: ScheduledTaskConfig, code: String) {
        mutateCommittedOutcome(committed) { current -> current.copy(lastErrorCode = code) }
    }

    private suspend fun recoverUnexpectedFailure(
        running: ScheduledTaskConfig,
        failure: Throwable,
    ): ForegroundHealthTaskRunResult {
        val code = failureCode(failure)
        return when (
            val commit = commitAttempt(
                running = running,
                revision = running.lastSourceRevision,
                status = ScheduledTaskRunStatus.ERROR,
                errorCode = code,
            )
        ) {
            is AttemptCommit.Saved -> ForegroundHealthTaskRunResult.Failed(code, failureMessage(code))
            AttemptCommit.Superseded -> ForegroundHealthTaskRunResult.Superseded
            is AttemptCommit.Failed -> commit.result
        }
    }

    private suspend fun commitAttempt(
        running: ScheduledTaskConfig,
        revision: String?,
        status: ScheduledTaskRunStatus,
        errorCode: String?,
    ): AttemptCommit {
        repeat(MAX_CAS_ATTEMPTS) {
            val current = try {
                store.read(running.id)
            } catch (failure: Exception) {
                return AttemptCommit.Failed(failedStoreRead(failure))
            }
            if (!ownsRunningAttempt(running, current)) return AttemptCommit.Superseded
            val updated = current!!.copy(
                lastSourceRevision = revision,
                lastStatus = status,
                lastErrorCode = errorCode,
            )
            val saved = store.saveIfCurrent(current, updated).getOrElse { failure ->
                return AttemptCommit.Failed(failedStoreWrite(failure))
            }
            if (saved) return AttemptCommit.Saved(updated)
        }
        return AttemptCommit.Failed(
            ForegroundHealthTaskRunResult.Failed("task_config_conflict", "定时任务设置正在更新，请重试。"),
        )
    }

    private suspend fun mutateCommittedOutcome(
        committed: ScheduledTaskConfig,
        transform: (ScheduledTaskConfig) -> ScheduledTaskConfig,
    ): MutationOutcome {
        repeat(MAX_CAS_ATTEMPTS) {
            val current = try {
                store.read(committed.id)
            } catch (_: Exception) {
                return MutationOutcome.Failed
            }
            if (!sameCommittedOutcome(committed, current)) return MutationOutcome.Superseded
            val updated = transform(current!!)
            val saved = store.saveIfCurrent(current, updated).getOrElse { return MutationOutcome.Failed }
            if (saved) return MutationOutcome.Saved
        }
        return MutationOutcome.Failed
    }

    private suspend fun stillOwnsAttempt(running: ScheduledTaskConfig): Boolean =
        ownsRunningAttempt(running, store.read(running.id))

    /**
     * Linearizes the last ownership check with the HTTP submission boundary. UI
     * changes use the same coordinator, so a disable/provider change that wins
     * this lock prevents the old health summary from being submitted.
     */
    private suspend fun withOwnedSubmissionLease(
        running: ScheduledTaskConfig,
        submit: () -> Unit,
    ): Boolean = ScheduledTaskMutationCoordinator.withLock {
        val owned = try {
            stillOwnsAttempt(running)
        } catch (failure: Exception) {
            throw HealthTaskException("task_store_read_failed", "定时任务配置无法读取。")
        }
        if (!owned) {
            false
        } else {
            submit()
            true
        }
    }

    private suspend fun isCommittedOutcome(committed: ScheduledTaskConfig): Boolean =
        sameCommittedOutcome(committed, store.read(committed.id))

    private fun ownsRunningAttempt(
        running: ScheduledTaskConfig,
        current: ScheduledTaskConfig?,
    ): Boolean = current != null &&
        current.enabled &&
        current.type == running.type &&
        current.provider == running.provider &&
        current.modelId == running.modelId &&
        current.includeHealthSummary == running.includeHealthSummary &&
        current.options == running.options &&
        current.lastAttemptAt == running.lastAttemptAt &&
        current.lastStatus == ScheduledTaskRunStatus.RUNNING

    private fun sameCommittedOutcome(
        committed: ScheduledTaskConfig,
        current: ScheduledTaskConfig?,
    ): Boolean = current != null &&
        current.enabled &&
        current.type == committed.type &&
        current.provider == committed.provider &&
        current.modelId == committed.modelId &&
        current.includeHealthSummary == committed.includeHealthSummary &&
        current.options == committed.options &&
        current.lastAttemptAt == committed.lastAttemptAt &&
        current.lastSourceRevision == committed.lastSourceRevision &&
        current.lastStatus == committed.lastStatus

    private suspend fun showProgress(stage: HealthTaskStage, preview: String? = null) {
        try { progress.publish(stage, preview) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Optional island status cannot prevent synchronization. */ }
    }

    private suspend fun rollbackOwnedAttempt(
        running: ScheduledTaskConfig,
        previous: ScheduledTaskConfig,
    ) {
        repeat(MAX_CAS_ATTEMPTS) {
            val current = runCatching { store.read(running.id) }.getOrNull()
            if (!ownsRunningAttempt(running, current)) return
            val rolledBack = current!!.copy(
                lastAttemptAt = previous.lastAttemptAt,
                lastSourceRevision = previous.lastSourceRevision,
                lastStatus = previous.lastStatus,
                lastErrorCode = previous.lastErrorCode,
            )
            val saved = store.saveIfCurrent(current, rolledBack).getOrElse { return }
            if (saved) return
        }
    }

    private fun deliveryId(config: ScheduledTaskConfig): String = buildString {
        append(config.id)
        append(':')
        append(config.lastAttemptAt ?: 0L)
        append(':')
        append(config.lastStatus.name.lowercase())
        append(':')
        append(config.lastSourceRevision?.take(16).orEmpty())
        append(':')
        append(config.lastErrorCode.orEmpty())
    }

    private fun unchangedMessage(): String {
        val index = Math.floorMod(statusRotation.getAndIncrement(), UNCHANGED_MESSAGES.size)
        return UNCHANGED_MESSAGES[index]
    }

    private fun failureCode(failure: Throwable): String = when (failure) {
        is HealthTaskException -> failure.code
        is MiFitnessStepsSyncException -> failure.code
        else -> "task_failed"
    }.takeIf(ERROR_CODE_PATTERN::matches) ?: "task_failed"

    private fun failureMessage(code: String): String = when (code) {
        "credentials_missing" -> "尚未配置小米运动健康凭据。"
        "authentication_failed" -> "小米运动健康认证已失效。"
        "rate_limited" -> "小米云请求暂时受限。"
        "task_provider_key_missing" -> "定时任务的 Provider Key 已不可用。"
        "task_model_unavailable", "task_model_invalid", "task_model_mismatch" -> "定时任务锁定的模型当前不可用。"
        "health_summary_not_authorized" -> "定时任务尚未获得健康摘要授权。"
        "task_store_read_failed" -> "定时任务配置无法读取。"
        else -> "定时任务这次没有完成。"
    }

    private fun failedStoreRead(failure: Throwable): ForegroundHealthTaskRunResult.Failed {
        val code = failureCode(failure).takeIf { it == "task_store_read_failed" } ?: "task_store_read_failed"
        return ForegroundHealthTaskRunResult.Failed(code, "定时任务配置无法读取。")
    }

    private fun failedStoreWrite(failure: Throwable): ForegroundHealthTaskRunResult.Failed =
        ForegroundHealthTaskRunResult.Failed(
            failureCode(failure).takeIf { it == "task_store_read_failed" } ?: "task_store_write_failed",
            if (failureCode(failure) == "task_store_read_failed") "定时任务配置无法读取。" else "定时任务状态无法保存。",
        )

    private fun com.campusai.core.ai.CloudDailyHealthSummary.hasAnyValue(): Boolean =
        steps != null || distanceMeters != null || activeCaloriesKcal != null || activityMinutes != null ||
            sleepMinutes != null || averageHeartRateBpm != null || averageOxygenSaturationPercent != null ||
            averageStressScore != null || workoutCount != null

    private sealed interface AttemptCommit {
        data class Saved(val config: ScheduledTaskConfig) : AttemptCommit
        data object Superseded : AttemptCommit
        data class Failed(val result: ForegroundHealthTaskRunResult.Failed) : AttemptCommit
    }

    private enum class MutationOutcome { Saved, Superseded, Failed }

    private companion object {
        val PROCESS_MUTEX = Mutex()
        const val MAX_CAS_ATTEMPTS = 12
        val ERROR_CODE_PATTERN = Regex("[a-z0-9_]{1,64}")
        val UNCHANGED_MESSAGES = listOf(
            "小米云还没更新呢",
            "云端还是刚才那些",
            "数据暂时没变化",
        )
    }
}
