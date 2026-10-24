package com.campusai.core.automation

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class NoBackupScheduledTaskStore internal constructor(
    private val atomicFile: AtomicFile,
) : ScheduledTaskStore {
    constructor(context: Context) : this(
        AtomicFile(File(context.noBackupFilesDir, RELATIVE_PATH)),
    )

    override suspend fun read(taskId: String): ScheduledTaskConfig? =
        readAll().firstOrNull { it.id == taskId }

    override suspend fun readAll(): List<ScheduledTaskConfig> = withContext(Dispatchers.IO) {
        PROCESS_MUTEX.withLock { readLocked() }
    }

    override suspend fun save(config: ScheduledTaskConfig): Result<Unit> = withContext(Dispatchers.IO) {
        PROCESS_MUTEX.withLock {
            runCatching {
                val tasks = readLocked().associateByTo(linkedMapOf()) { it.id }
                tasks[config.id] = config
                writeLocked(tasks.values.sortedBy(ScheduledTaskConfig::id))
            }
        }
    }

    override suspend fun saveIfCurrent(
        expected: ScheduledTaskConfig?,
        updated: ScheduledTaskConfig,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        PROCESS_MUTEX.withLock {
            runCatching {
                require(expected == null || expected.id == updated.id)
                val tasks = readLocked().associateByTo(linkedMapOf()) { it.id }
                if (tasks[updated.id] != expected) return@runCatching false
                tasks[updated.id] = updated
                writeLocked(tasks.values.sortedBy(ScheduledTaskConfig::id))
                true
            }
        }
    }

    override suspend fun delete(taskId: String): Result<Unit> = withContext(Dispatchers.IO) {
        PROCESS_MUTEX.withLock {
            runCatching {
                val tasks = readLocked().filterNot { it.id == taskId }
                writeLocked(tasks)
            }
        }
    }

    private fun readLocked(): List<ScheduledTaskConfig> {
        if (!atomicFile.baseFile.exists()) return emptyList()
        return try {
            val root = atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                JSONObject(reader.readText())
            }
            require(root.optInt("version") == FORMAT_VERSION)
            val rows = root.getJSONArray("tasks")
            require(rows.length() <= MAX_TASKS)
            val decoded = buildList {
                repeat(rows.length()) { index -> add(decode(rows.getJSONObject(index))) }
            }
            require(decoded.distinctBy(ScheduledTaskConfig::id).size == decoded.size)
            decoded
        } catch (_: Exception) {
            // A corrupt or unsupported store is not equivalent to an empty store. In
            // particular, save() must not replace the unreadable file with a new one.
            throw HealthTaskException("task_store_read_failed", "定时任务配置无法读取。")
        }
    }

    private fun writeLocked(tasks: Collection<ScheduledTaskConfig>) {
        require(tasks.size <= MAX_TASKS)
        atomicFile.baseFile.parentFile?.mkdirs()
        val payload = JSONObject()
            .put("version", FORMAT_VERSION)
            .put("tasks", JSONArray(tasks.map(::encode)))
            .toString()
        val stream = atomicFile.startWrite()
        try {
            stream.write(payload.toByteArray(Charsets.UTF_8))
            stream.flush()
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    private fun encode(config: ScheduledTaskConfig): JSONObject = JSONObject()
        .put("id", config.id)
        .put("type", config.type.name)
        .put("enabled", config.enabled)
        .put("intervalMinutes", config.intervalMinutes)
        .put("provider", config.provider.name)
        .put("modelId", config.modelId)
        .put("includeHealthSummary", config.includeHealthSummary)
        .put("syncBeforeMessage", config.options.syncBeforeMessage)
        .put("syncOnly", config.options.syncOnly)
        .put("runInBackground", config.options.runInBackground)
        .put("aiMode", config.options.aiMode.name)
        .put("cloudFreeOwnerUserId", config.options.cloudFreeOwnerUserId)
        .put("lastAttemptAt", config.lastAttemptAt ?: JSONObject.NULL)
        .put("lastSourceRevision", config.lastSourceRevision ?: JSONObject.NULL)
        .put("lastStatus", config.lastStatus.name)
        .put("lastErrorCode", config.lastErrorCode ?: JSONObject.NULL)

    private fun decode(row: JSONObject): ScheduledTaskConfig = ScheduledTaskConfig(
        id = row.getString("id"),
        type = ScheduledTaskType.valueOf(row.getString("type")),
        enabled = row.getBoolean("enabled"),
        intervalMinutes = row.getInt("intervalMinutes"),
        provider = com.campusai.core.ai.CloudAiProvider.valueOf(row.getString("provider")),
        modelId = row.getString("modelId"),
        includeHealthSummary = row.getBoolean("includeHealthSummary"),
        // Existing installations remain cloud-read-only until the new settings are saved.
        options = HealthTaskOptions(
            syncBeforeMessage = row.optBoolean("syncBeforeMessage", false),
            syncOnly = row.optBoolean("syncOnly", false),
            runInBackground = row.optBoolean("runInBackground", false),
            aiMode = HealthTaskAiMode.valueOf(row.optString("aiMode", HealthTaskAiMode.PERSONAL_CLOUD.name)),
            cloudFreeOwnerUserId = row.optString("cloudFreeOwnerUserId", ""),
        ),
        lastAttemptAt = row.optNullableLong("lastAttemptAt"),
        lastSourceRevision = row.optNullableString("lastSourceRevision"),
        lastStatus = ScheduledTaskRunStatus.valueOf(row.getString("lastStatus")),
        lastErrorCode = row.optNullableString("lastErrorCode"),
    )

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key)

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key).takeIf(String::isNotBlank)

    companion object {
        private val PROCESS_MUTEX = Mutex()
        private const val FORMAT_VERSION = 1
        private const val MAX_TASKS = 16
        private const val RELATIVE_PATH = "automation/scheduled_tasks_v1.json"
    }
}
