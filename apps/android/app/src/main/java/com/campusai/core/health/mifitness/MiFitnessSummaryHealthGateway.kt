package com.campusai.core.health.mifitness

import com.campusai.core.time.CaesarTime

import com.campusai.core.health.HealthAvailability
import com.campusai.core.health.HealthFreshness
import com.campusai.core.health.HealthGateway
import com.campusai.core.health.HealthMetricKey
import com.campusai.core.health.HealthMetricStatus
import com.campusai.core.health.HealthMetricValue
import com.campusai.core.health.HealthMetrics
import com.campusai.core.health.HealthPeriod
import com.campusai.core.health.HealthSnapshot
import com.campusai.core.health.healthFreshness
import java.time.Instant
import java.time.ZoneId

class MiFitnessSummaryHealthGateway(
    private val credentialStore: MiFitnessCredentialStore,
    private val cache: MiFitnessStepsCache,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val zoneId: ZoneId = CaesarTime.zone,
    private val requestZoneId: () -> ZoneId = { CaesarTime.zone },
) : HealthGateway {
    override val readPermissions: Set<String> = emptySet()

    override fun availability(): HealthAvailability {
        val now = nowMillis()
        val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        val credentials = runCatching { credentialStore.read() }.getOrNull()
            ?: return HealthAvailability.Unsupported
        val cached = runCatching {
            cache.read(canonicalTodayPeriod(today), today, credentials.accountScope)
        }.getOrNull()
        return if (cached != null) HealthAvailability.Available else HealthAvailability.Unsupported
    }

    override suspend fun grantedPermissions(): Set<String> = emptySet()

    override suspend fun snapshot(period: HealthPeriod): Result<HealthSnapshot> = runCatching {
        val now = nowMillis()
        val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        val currentRequestZone = requestZoneId()
        val requestTodayStart = Instant.ofEpochMilli(now)
            .atZone(currentRequestZone)
            .toLocalDate()
            .atStartOfDay(currentRequestZone)
            .toInstant()
            .toEpochMilli()
        require(
            period.key.lowercase() in TODAY_KEYS &&
                period.startEpochMillis == requestTodayStart &&
                period.endEpochMillis >= period.startEpochMillis &&
                period.endEpochMillis <= now + CLOCK_SKEW_TOLERANCE_MILLIS
        ) {
            "Mi Fitness 缓存仅支持北京时间当天。"
        }
        val credentials = credentialStore.read() ?: error("尚未配置 Mi Fitness 凭据。")
        val summary = cache.read(canonicalTodayPeriod(today), today, credentials.accountScope)
            ?: error("Mi Fitness 当天健康摘要缓存不可用。")
        val freshness = healthFreshness(now, summary.lastSyncAt)
        val metricValues = summary.metricValues.mapValues { (_, metric) ->
            if (freshness == HealthFreshness.STALE && metric.status == HealthMetricStatus.AVAILABLE) {
                metric.copy(status = HealthMetricStatus.STALE, reasonCode = "cache_stale")
            } else {
                metric
            }
        }
        val metricTimeSeries = summary.metricTimeSeries.mapValues { (_, series) ->
            if (freshness == HealthFreshness.STALE && series.status == HealthMetricStatus.AVAILABLE) {
                series.copy(status = HealthMetricStatus.STALE, reasonCode = "cache_stale")
            } else {
                series
            }
        }
        HealthSnapshot(
            originPackages = setOf(SOURCE_ID),
            period = summary.period,
            observedAt = summary.observedAt,
            lastSyncAt = summary.lastSyncAt,
            freshness = freshness,
            metrics = metricValues.toHealthMetrics(),
            missingFields = HealthMetricKey.entries
                .filter { key ->
                    val metric = metricValues[key]
                    metric == null || metric.value == null ||
                        metric.status in setOf(HealthMetricStatus.EMPTY, HealthMetricStatus.ERROR)
                }
                .mapTo(linkedSetOf()) { it.name.lowercase() },
            confidence = VENDOR_AGGREGATE_CONFIDENCE,
            metricValues = metricValues,
            metricTimeSeries = metricTimeSeries,
        )
    }

    private fun canonicalTodayPeriod(today: java.time.LocalDate): HealthPeriod {
        val start = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L
        return HealthPeriod(startEpochMillis = start, endEpochMillis = end, key = "today")
    }

    private fun Map<HealthMetricKey, HealthMetricValue>.toHealthMetrics(): HealthMetrics {
        fun number(key: HealthMetricKey): Double? = this[key]
            ?.takeIf { it.status !in setOf(HealthMetricStatus.EMPTY, HealthMetricStatus.ERROR) }
            ?.value
        return HealthMetrics(
            steps = number(HealthMetricKey.STEPS)?.toLong(),
            distanceMeters = number(HealthMetricKey.DISTANCE_METERS),
            activeCaloriesKcal = number(HealthMetricKey.ACTIVE_CALORIES_KCAL),
            activityDurationMinutes = number(HealthMetricKey.ACTIVITY_DURATION_MINUTES)?.toLong(),
            validStandCount = number(HealthMetricKey.VALID_STAND_COUNT)?.toLong(),
            heartRateAverageBpm = number(HealthMetricKey.HEART_RATE_AVERAGE_BPM)?.toLong(),
            heartRateMaximumBpm = number(HealthMetricKey.HEART_RATE_MAXIMUM_BPM)?.toLong(),
            heartRateMinimumBpm = number(HealthMetricKey.HEART_RATE_MINIMUM_BPM)?.toLong(),
            restingHeartRateBpm = number(HealthMetricKey.RESTING_HEART_RATE_BPM)?.toLong(),
            oxygenSaturationAveragePercent = number(HealthMetricKey.OXYGEN_SATURATION_AVERAGE_PERCENT),
            oxygenSaturationMaximumPercent = number(HealthMetricKey.OXYGEN_SATURATION_MAXIMUM_PERCENT),
            oxygenSaturationMinimumPercent = number(HealthMetricKey.OXYGEN_SATURATION_MINIMUM_PERCENT),
            sleepMinutes = number(HealthMetricKey.SLEEP_MINUTES)?.toLong(),
            sleepDeepMinutes = number(HealthMetricKey.SLEEP_DEEP_MINUTES)?.toLong(),
            sleepLightMinutes = number(HealthMetricKey.SLEEP_LIGHT_MINUTES)?.toLong(),
            sleepRemMinutes = number(HealthMetricKey.SLEEP_REM_MINUTES)?.toLong(),
            sleepAwakeMinutes = number(HealthMetricKey.SLEEP_AWAKE_MINUTES)?.toLong(),
            sleepScore = number(HealthMetricKey.SLEEP_SCORE)?.toLong(),
            stressAverage = number(HealthMetricKey.STRESS_AVERAGE)?.toLong(),
            stressMaximum = number(HealthMetricKey.STRESS_MAXIMUM)?.toLong(),
            stressMinimum = number(HealthMetricKey.STRESS_MINIMUM)?.toLong(),
            vo2MaxAverage = number(HealthMetricKey.VO2_MAX_AVERAGE),
            vo2MaxMaximum = number(HealthMetricKey.VO2_MAX_MAXIMUM),
            vo2MaxMinimum = number(HealthMetricKey.VO2_MAX_MINIMUM),
            workoutCount = number(HealthMetricKey.WORKOUT_COUNT)?.toInt(),
        )
    }

    companion object {
        const val SOURCE_ID = MiFitnessMetricRegistry.SOURCE_ID
        const val VENDOR_AGGREGATE_CONFIDENCE = 0.95

        private const val CLOCK_SKEW_TOLERANCE_MILLIS = 60_000L
        private val TODAY_KEYS = setOf("today", "day", "今天", "今日")
    }
}
