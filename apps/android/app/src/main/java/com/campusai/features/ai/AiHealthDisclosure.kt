package com.campusai.features.ai

import com.campusai.core.ai.CloudDailyHealthSummary
import com.campusai.core.ai.CloudHealthDisclosure
import com.campusai.core.health.HealthSnapshot
import com.campusai.core.health.HealthPeriods
import com.campusai.core.health.mifitness.MiFitnessSummaryHealthGateway
import com.campusai.core.time.CaesarTime

/** Mi Fitness describes a daily aggregate bucket, not an observation from the future. */
internal fun currentDayHealthSnapshot(snapshot: HealthSnapshot?, nowMillis: Long = System.currentTimeMillis()): HealthSnapshot? {
    snapshot ?: return null
    val today = HealthPeriods.parse("today", nowMillis)
    val observedToday = today.startEpochMillis..nowMillis
    if (snapshot.period.startEpochMillis != today.startEpochMillis ||
        snapshot.period.endEpochMillis < today.startEpochMillis || snapshot.observedAt !in observedToday ||
        snapshot.lastSyncAt?.let { it !in observedToday } == true) return null
    if (snapshot.period.endEpochMillis > nowMillis) {
        val dayEnd = CaesarTime.today(java.time.Instant.ofEpochMilli(nowMillis)).plusDays(1)
            .atStartOfDay(CaesarTime.zone).toInstant().toEpochMilli() - 1L
        if (snapshot.originPackages != setOf(MiFitnessSummaryHealthGateway.SOURCE_ID) ||
            snapshot.period.endEpochMillis != dayEnd || snapshot.lastSyncAt == null) return null
    }
    return snapshot
}

internal fun CaesarHealthUiState.forCurrentHealthDay(nowMillis: Long = System.currentTimeMillis()): CaesarHealthUiState {
    val current = currentDayHealthSnapshot(snapshot, nowMillis)
    val today = HealthPeriods.parse("today", nowMillis)
    val syncAt = miFitnessLastSyncAt?.takeIf { it in today.startEpochMillis..nowMillis }
    val expired = (snapshot != null && current == null) || (miFitnessLastSyncAt != null && syncAt == null)
    return copy(snapshot = current, miFitnessLastSyncAt = syncAt,
        miFitnessStatus = if (expired && miFitnessStatus == MiFitnessUiStatus.SUCCESS) MiFitnessUiStatus.IDLE else miFitnessStatus,
        miFitnessDeviceSyncStatus = if (expired && !miFitnessSyncing) null else miFitnessDeviceSyncStatus,
        actionMessage = if (expired) null else actionMessage)
}

internal fun currentCloudHealthDisclosure(snapshot: HealthSnapshot?, allowed: Boolean, nowMillis: Long = System.currentTimeMillis()): CloudHealthDisclosure {
    if (!allowed) return CloudHealthDisclosure.Excluded
    val summary = currentDayHealthSummary(snapshot, nowMillis)?.takeIf { it.hasShareableMetrics }
        ?: return CloudHealthDisclosure.Excluded
    return CloudHealthDisclosure.Included(summary)
}

internal fun currentDayHealthSummary(snapshot: HealthSnapshot?, nowMillis: Long = System.currentTimeMillis()): CloudDailyHealthSummary? {
    val current = currentDayHealthSnapshot(snapshot, nowMillis) ?: return null
    val metrics = current.metrics
    return CloudDailyHealthSummary(
        localDate = java.time.Instant.ofEpochMilli(nowMillis).atZone(CaesarTime.zone).toLocalDate().toString(),
        steps = metrics.steps,
        distanceMeters = metrics.distanceMeters,
        activeCaloriesKcal = metrics.activeCaloriesKcal,
        activityMinutes = metrics.activityDurationMinutes,
        sleepMinutes = metrics.sleepMinutes,
        averageHeartRateBpm = metrics.heartRateAverageBpm?.toDouble(),
        averageOxygenSaturationPercent = metrics.oxygenSaturationAveragePercent,
        averageStressScore = metrics.stressAverage?.toDouble(),
        workoutCount = metrics.workoutCount?.toLong(),
    )
}
