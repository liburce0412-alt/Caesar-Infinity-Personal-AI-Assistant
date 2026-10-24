package com.campusai.core.automation

import com.campusai.core.ai.CloudAiProvider
import com.campusai.core.ai.CloudDailyHealthSummary
import com.campusai.core.ai.ResolvedExecution
import com.campusai.core.health.HealthMetricKey
import com.campusai.core.health.HealthMetricStatus
import com.campusai.core.health.HealthMetricValue
import com.campusai.core.health.HealthSnapshot
import java.math.BigDecimal
import java.security.MessageDigest
import org.json.JSONObject

@JvmInline
value class HealthRevision(val value: String) {
    init {
        require(REVISION_PATTERN.matches(value)) { "Invalid health revision" }
    }

    companion object {
        private val REVISION_PATTERN = Regex("[0-9a-f]{64}")

        fun from(
            localDate: String,
            snapshot: HealthSnapshot,
            workoutRevision: String? = null,
        ): HealthRevision {
            require(LOCAL_DATE_PATTERN.matches(localDate)) { "Invalid local date" }
            require(workoutRevision == null || REVISION_PATTERN.matches(workoutRevision)) {
                "Invalid workout revision"
            }
            val canonical = buildString {
                append(localDate)
                append("\nworkouts|")
                append(workoutRevision.orEmpty())
                snapshot.metricValues.toSortedMap(compareBy(HealthMetricKey::name)).forEach { (key, metric) ->
                    append('\n')
                    append(key.name)
                    append('|')
                    append(metric.canonicalValue())
                    append('|')
                    append(metric.status.name)
                    append('|')
                    append(metric.unit.name)
                    append('|')
                    append(metric.provenance.vendorKey.orEmpty())
                    append('|')
                    append(metric.provenance.sourceCount?.toString().orEmpty())
                }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return HealthRevision(digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) })
        }

        private fun HealthMetricValue.canonicalValue(): String = value?.let { number ->
            require(number.isFinite()) { "Invalid health metric value" }
            BigDecimal.valueOf(number).stripTrailingZeros().toPlainString()
        }.orEmpty()

        private val LOCAL_DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")
    }
}

data class HealthCloudObservation(
    val localDate: String,
    val snapshot: HealthSnapshot,
    val workoutRevision: String? = null,
) {
    val revision: HealthRevision = HealthRevision.from(localDate, snapshot, workoutRevision)

    /** A stale/error fetch is diagnostic state, not a new authoritative health value. */
    fun authoritativeRevisionFailureCode(): String? = snapshot.metricValues
        .toSortedMap(compareBy(HealthMetricKey::name))
        .values
        .firstOrNull { metric ->
            metric.status == HealthMetricStatus.ERROR || metric.status == HealthMetricStatus.STALE
        }
        ?.reasonCode
        ?.takeIf(REVISION_FAILURE_CODE::matches)
        ?: snapshot.metricValues.values
            .firstOrNull { metric ->
                metric.status == HealthMetricStatus.ERROR || metric.status == HealthMetricStatus.STALE
            }
            ?.let { "health_cloud_incomplete" }

    fun allowedCloudSummary(): CloudDailyHealthSummary {
        fun available(key: HealthMetricKey): Double? = snapshot.metricValues[key]
            ?.takeIf { it.status == HealthMetricStatus.AVAILABLE }
            ?.value
        return CloudDailyHealthSummary(
            localDate = localDate,
            steps = available(HealthMetricKey.STEPS)?.toLong(),
            distanceMeters = available(HealthMetricKey.DISTANCE_METERS),
            activeCaloriesKcal = available(HealthMetricKey.ACTIVE_CALORIES_KCAL),
            activityMinutes = available(HealthMetricKey.ACTIVITY_DURATION_MINUTES)?.toLong(),
            sleepMinutes = available(HealthMetricKey.SLEEP_MINUTES)?.toLong(),
            averageHeartRateBpm = available(HealthMetricKey.HEART_RATE_AVERAGE_BPM),
            averageOxygenSaturationPercent = available(HealthMetricKey.OXYGEN_SATURATION_AVERAGE_PERCENT),
            averageStressScore = available(HealthMetricKey.STRESS_AVERAGE),
            workoutCount = available(HealthMetricKey.WORKOUT_COUNT)?.toLong(),
        )
    }

    private companion object {
        val REVISION_FAILURE_CODE = Regex("[a-z0-9_]{1,64}")
    }
}

interface ForegroundHealthCloudSource {
    suspend fun requestDeviceSync(withSubmissionLease: suspend (() -> Unit) -> Boolean): com.campusai.core.health.mifitness.MiFitnessDeviceSyncStatus =
        com.campusai.core.health.mifitness.MiFitnessDeviceSyncStatus.APP_UNAVAILABLE
    suspend fun refreshToday(): Result<HealthCloudObservation>
}

data class AutoMessageBatch(
    val messages: List<String>,
    val execution: ResolvedExecution? = null,
) {
    init {
        require(validateMessages(messages) == null) { "Invalid automatic message batch" }
    }

    companion object {
        fun parse(raw: String): Result<AutoMessageBatch> = runCatching {
            require(raw.length <= MAX_RAW_CHARS)
            val root = JSONObject(raw.trim())
            require(root.length() == 1 && root.has("messages"))
            val rows = root.getJSONArray("messages")
            val messages = buildList {
                repeat(rows.length()) { index ->
                    val value = rows.get(index)
                    require(value is String)
                    add(value)
                }
            }
            validateMessages(messages)?.let { throw IllegalArgumentException(it) }
            AutoMessageBatch(messages)
        }

        internal fun validateMessages(messages: List<String>): String? = when {
            messages.size !in 2..3 -> "message_count"
            messages.any { it != it.trim() || it.length !in 4..28 || it.any(Char::isISOControl) } -> "message_length"
            messages.sumOf(String::length) > 84 -> "batch_length"
            messages.any { '\n' in it || '\r' in it } -> "message_line_break"
            messages.any { message -> FORBIDDEN_TERMS.any { message.contains(it, ignoreCase = true) } } -> "message_forbidden_term"
            messages.any { message -> !message.hasNaturalChineseText() } -> "message_language"
            messages.any { message -> MARKDOWN_PATTERN.containsMatchIn(message) } -> "message_markdown"
            messages.sumOf { message -> message.count { it == '。' } } > 1 -> "message_too_formal"
            else -> null
        }

        private const val MAX_RAW_CHARS = 2_048
        private val FORBIDDEN_TERMS = setOf(
            "健康提醒",
            "自动分析",
            "DeepSeek",
            "Gemini",
            "Provider",
            "AI",
            "模型",
            "定时任务",
            "作为AI",
            "医疗诊断",
            "患有",
            "治疗方案",
        )
        private val MARKDOWN_PATTERN = Regex("(^|\\s)(#{1,6}|[-*]\\s|```|>)")
        private val HAN_PATTERN = Regex("[\\p{IsHan}]")

        private fun String.hasNaturalChineseText(): Boolean {
            val hanCount = HAN_PATTERN.findAll(this).count()
            val latinCount = count { it in 'A'..'Z' || it in 'a'..'z' }
            return hanCount >= 2 && latinCount <= hanCount
        }
    }
}

interface HealthAutoMessageClient {
    suspend fun validateLocal(modelId: String): Result<Unit> =
        Result.failure(HealthTaskException("local_model_unavailable", "请先下载并校验本地模型。"))

    suspend fun generateLocal(
        modelId: String,
        summary: CloudDailyHealthSummary,
        withSubmissionLease: suspend (submit: () -> Unit) -> Boolean,
    ): Result<AutoMessageBatch> =
        Result.failure(HealthTaskException("local_model_unavailable", "本地模型暂不可用。"))

    suspend fun validateCaesarCloudFree(ownerUserId: String): Result<Unit> =
        Result.failure(HealthTaskException("task_model_unavailable", "免费云端暂不可用。"))

    suspend fun generateCaesarCloudFree(
        ownerUserId: String,
        summary: CloudDailyHealthSummary,
        withSubmissionLease: suspend (submit: () -> Unit) -> Boolean,
    ): Result<AutoMessageBatch> =
        Result.failure(HealthTaskException("task_model_unavailable", "免费云端暂不可用。"))

    /** Must validate this exact model against the provider's current model list. */
    suspend fun validate(provider: CloudAiProvider, modelId: String): Result<Unit>

    /** Must use the exact provider and model supplied; fallback is forbidden. */
    suspend fun generate(
        provider: CloudAiProvider,
        modelId: String,
        summary: CloudDailyHealthSummary,
        withSubmissionLease: suspend (submit: () -> Unit) -> Boolean,
    ): Result<AutoMessageBatch>
}

class HealthTaskException(
    val code: String,
    override val message: String,
) : IllegalStateException(message) {
    override fun toString(): String = "HealthTaskException(code=$code)"
}
