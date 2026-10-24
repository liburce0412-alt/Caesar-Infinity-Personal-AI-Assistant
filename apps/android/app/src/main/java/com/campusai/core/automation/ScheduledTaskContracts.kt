package com.campusai.core.automation

import com.campusai.core.ai.CloudAiProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ScheduledTaskType {
    HEALTH_CLOUD_STATUS,
}

enum class ScheduledTaskRunStatus {
    IDLE,
    RUNNING,
    UPDATED,
    UNCHANGED,
    ERROR,
    SYNCED,
}

enum class HealthTaskAiMode { CAESAR_CLOUD_FREE, LOCAL, PERSONAL_CLOUD }

data class HealthTaskOptions(
    val syncBeforeMessage: Boolean = true,
    val syncOnly: Boolean = false,
    val runInBackground: Boolean = false,
    val aiMode: HealthTaskAiMode = HealthTaskAiMode.PERSONAL_CLOUD,
    val cloudFreeOwnerUserId: String = "",
)

data class ScheduledTaskConfig(
    val id: String,
    val type: ScheduledTaskType,
    val enabled: Boolean,
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    val provider: CloudAiProvider,
    val modelId: String,
    /** Explicit consent captured when this task is saved. */
    val includeHealthSummary: Boolean,
    val lastAttemptAt: Long? = null,
    val lastSourceRevision: String? = null,
    val lastStatus: ScheduledTaskRunStatus = ScheduledTaskRunStatus.IDLE,
    /** Stable diagnostic code only; never contains provider or health payloads. */
    val lastErrorCode: String? = null,
    val options: HealthTaskOptions = HealthTaskOptions(),
) {
    init {
        require(ID_PATTERN.matches(id)) { "Invalid scheduled task id" }
        require(intervalMinutes in ALLOWED_INTERVAL_MINUTES) { "Invalid scheduled task interval" }
        require(when (options.aiMode) {
            HealthTaskAiMode.CAESAR_CLOUD_FREE -> modelId == CLOUD_FREE_MODEL
            HealthTaskAiMode.LOCAL -> com.campusai.core.localai.LocalModelMode.entries.any { it.modelId == modelId }
            HealthTaskAiMode.PERSONAL_CLOUD -> modelId == provider.normalizeModelId(modelId) && provider.acceptsModelId(modelId)
        }) {
            "Invalid scheduled task model"
        }
        require(lastAttemptAt == null || lastAttemptAt >= 0L) { "Invalid scheduled task timestamp" }
        require(lastSourceRevision == null || REVISION_PATTERN.matches(lastSourceRevision)) {
            "Invalid scheduled task revision"
        }
        require(lastErrorCode == null || ERROR_CODE_PATTERN.matches(lastErrorCode)) {
            "Invalid scheduled task error"
        }
    }

    /** WorkManager cannot schedule periodic work more frequently than 15 minutes. */
    val backgroundIntervalMinutes: Int get() = intervalMinutes.coerceAtLeast(MIN_BACKGROUND_INTERVAL_MINUTES)

    fun isDue(nowMillis: Long): Boolean {
        if (!enabled) return false
        val lastAttempt = lastAttemptAt ?: return true
        return nowMillis >= lastAttempt + intervalMinutes * 60_000L
    }

    companion object {
        val ALLOWED_INTERVAL_MINUTES = 3..60
        const val MIN_BACKGROUND_INTERVAL_MINUTES = 15
        const val DEFAULT_INTERVAL_MINUTES = 5
        const val CLOUD_FREE_MODEL = "glm-4-flash-250414"

        private val ID_PATTERN = Regex("[a-zA-Z0-9._-]{1,64}")
        private val REVISION_PATTERN = Regex("[0-9a-f]{64}")
        private val ERROR_CODE_PATTERN = Regex("[a-z0-9_]{1,64}")
    }
}

interface ScheduledTaskStore {
    suspend fun read(taskId: String): ScheduledTaskConfig?
    suspend fun readAll(): List<ScheduledTaskConfig>
    suspend fun save(config: ScheduledTaskConfig): Result<Unit>
    /** Atomically persists only if the task still equals the snapshot the runner observed. */
    suspend fun saveIfCurrent(
        expected: ScheduledTaskConfig?,
        updated: ScheduledTaskConfig,
    ): Result<Boolean> {
        require(expected == null || expected.id == updated.id)
        val current = read(updated.id)
        if (current != expected) return Result.success(false)
        return save(updated).map { true }
    }
    suspend fun delete(taskId: String): Result<Unit>
}

data class ScheduledTaskMutation(
    val previous: ScheduledTaskConfig?,
    val updated: ScheduledTaskConfig?,
)

/**
 * Re-reads and merges after every compare-and-set conflict. This is the only safe
 * way for UI writers to coexist with a foreground runner updating runtime fields.
 */
internal object ScheduledTaskMutationCoordinator {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

suspend fun ScheduledTaskStore.mutateWithRetry(
    taskId: String,
    maxAttempts: Int = 12,
    transform: (ScheduledTaskConfig?) -> ScheduledTaskConfig?,
): Result<ScheduledTaskMutation> = ScheduledTaskMutationCoordinator.withLock {
    runCatching {
        require(maxAttempts > 0)
        repeat(maxAttempts) {
            val current = read(taskId)
            val updated = transform(current)
            if (updated == null || updated == current) {
                return@runCatching ScheduledTaskMutation(current, updated)
            }
            require(updated.id == taskId)
            if (saveIfCurrent(current, updated).getOrThrow()) {
                return@runCatching ScheduledTaskMutation(current, updated)
            }
        }
        throw HealthTaskException("task_config_conflict", "定时任务设置正在更新，请重试。")
    }
}

fun mergeEnabledHealthTaskConfig(
    current: ScheduledTaskConfig?,
    provider: CloudAiProvider,
    modelId: String,
    intervalMinutes: Int,
    includeHealthSummary: Boolean,
    options: HealthTaskOptions = HealthTaskOptions(),
): ScheduledTaskConfig {
    val sameExecution = current?.type == ScheduledTaskType.HEALTH_CLOUD_STATUS &&
        current.provider == provider && current.modelId == modelId && current.options == options
    val preserveRuntime = current?.enabled == true && sameExecution
    return ScheduledTaskConfig(
        id = HealthTaskDefaults.TASK_ID,
        type = ScheduledTaskType.HEALTH_CLOUD_STATUS,
        enabled = true,
        intervalMinutes = intervalMinutes,
        provider = provider,
        modelId = modelId,
        includeHealthSummary = includeHealthSummary,
        options = options,
        lastAttemptAt = current?.lastAttemptAt?.takeIf { preserveRuntime },
        lastSourceRevision = current?.lastSourceRevision?.takeIf { sameExecution },
        lastStatus = current?.lastStatus?.takeIf { preserveRuntime } ?: ScheduledTaskRunStatus.IDLE,
        lastErrorCode = current?.lastErrorCode?.takeIf { preserveRuntime },
    )
}

fun mergeDisabledHealthTaskConfig(current: ScheduledTaskConfig?): ScheduledTaskConfig? = current?.copy(
    enabled = false,
    lastStatus = ScheduledTaskRunStatus.IDLE,
    lastErrorCode = null,
)

sealed interface ForegroundHealthTaskRunResult {
    data object Disabled : ForegroundHealthTaskRunResult
    data object NotDue : ForegroundHealthTaskRunResult
    data object Superseded : ForegroundHealthTaskRunResult
    data object Synced : ForegroundHealthTaskRunResult
    data class Updated(
        val conversationId: String,
        val messageCount: Int,
        val notificationsEnabled: Boolean,
    ) : ForegroundHealthTaskRunResult

    data class Unchanged(
        val conversationId: String,
        val notificationsEnabled: Boolean,
    ) : ForegroundHealthTaskRunResult

    data class Failed(val code: String, val message: String) : ForegroundHealthTaskRunResult
}

interface ForegroundHealthTaskRunner {
    /** Runs at most one iteration. Lifecycle owners decide when to call it again. */
    suspend fun run(taskId: String, force: Boolean = false, background: Boolean = false): ForegroundHealthTaskRunResult
}

object HealthTaskDefaults {
    const val TASK_ID = "daily"
}
