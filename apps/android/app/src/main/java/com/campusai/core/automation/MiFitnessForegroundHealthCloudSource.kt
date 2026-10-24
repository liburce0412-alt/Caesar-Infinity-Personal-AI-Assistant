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
    private val deviceSync: () -> com.campusai.core.health.mifitness.MiFitnessDeviceSyncStatus = {
        com.campusai.core.health.mifitness.MiFitnessDeviceSyncStatus.APP_UNAVAILABLE
    },
) : ForegroundHealthCloudSource {
    constructor(context: Context) : this(
        syncToday = MiFitnessStepsSyncService(context)::syncToday,
        snapshotToday = createSnapshotReader(context),
        deviceSync = {
            if (MiFitnessCredentialStore(context).hasCredentials()) com.campusai.core.health.mifitness.MiFitnessDeviceSync(context).request()
            else com.campusai.core.health.mifitness.MiFitnessDeviceSyncStatus.BLOCKED
        },
    )

    override suspend fun requestDeviceSync(withSubmissionLease: suspend (() -> Unit) -> Boolean): com.campusai.core.health.mifitness.MiFitnessDeviceSyncStatus {
        var status = com.campusai.core.health.mifitness.MiFitnessDeviceSyncStatus.BLOCKED
        if (!withSubmissionLease { status = deviceSync() }) {
            throw HealthTaskException("request_superseded", "设置已改变，本次同步已取消。")
        }
        return status
    }

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
