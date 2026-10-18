package com.campusai.core.automation

import com.campusai.core.time.CaesarTime

import android.content.Context
import com.campusai.core.health.HealthPeriods
import com.campusai.core.health.mifitness.MiFitnessCredentialStore
import com.campusai.core.health.mifitness.MiFitnessStepsCache
import com.campusai.core.health.mifitness.MiFitnessStepsSyncService
import com.campusai.core.health.mifitness.MiFitnessSummaryHealthGateway

class MiFitnessForegroundHealthCloudSource internal constructor(
    private val syncToday: suspend () -> Result<com.campusai.core.health.mifitness.MiFitnessStepsSummary>,
    private val snapshotToday: suspend () -> Result<com.campusai.core.health.HealthSnapshot>,
) : ForegroundHealthCloudSource {
    constructor(context: Context) : this(
        syncToday = MiFitnessStepsSyncService(context)::syncToday,
        snapshotToday = createSnapshotReader(context),
    )

    override suspend fun refreshToday(): Result<HealthCloudObservation> = runCatching {
        val summary = syncToday().getOrThrow()
        val snapshot = snapshotToday().getOrThrow()
        require(snapshot.period.startEpochMillis == summary.period.startEpochMillis)
        HealthCloudObservation(summary.localDate.toString(), snapshot, summary.workoutRevision)
    }
}

private fun createSnapshotReader(context: Context): suspend () -> Result<com.campusai.core.health.HealthSnapshot> {
    val appContext = context.applicationContext
    val gateway = MiFitnessSummaryHealthGateway(
        credentialStore = MiFitnessCredentialStore(appContext),
        cache = MiFitnessStepsCache(appContext),
    )
    return suspend {
        gateway.snapshot(HealthPeriods.parse("today", zone = CaesarTime.zone))
    }
}
