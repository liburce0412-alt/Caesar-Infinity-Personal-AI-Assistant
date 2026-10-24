package com.campusai.features.ai

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.campusai.core.ai.NetworkAvailability
import com.campusai.core.health.HealthGateway
import com.campusai.core.health.HealthPeriods
import com.campusai.core.health.mifitness.MiFitnessAccountException
import com.campusai.core.health.mifitness.MiFitnessAccountService
import com.campusai.core.health.mifitness.MiFitnessCredentialStore
import com.campusai.core.health.mifitness.MiFitnessDeviceSyncStatus
import com.campusai.core.health.mifitness.MiFitnessStepsSyncException
import com.campusai.core.health.mifitness.MiFitnessStepsSyncScheduler
import com.campusai.core.health.mifitness.MiFitnessStepsSyncWorker
import com.campusai.core.health.mifitness.MiFitnessStepsSyncService
import com.campusai.core.health.mifitness.MiFitnessSummaryHealthGateway
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AiHealthController(
    private val appContext: Context,
    private val viewModelScope: kotlinx.coroutines.CoroutineScope,
    private val healthGateway: HealthGateway,
    cacheUpdates: Flow<Unit> = MiFitnessStepsSyncService.updates,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val network = NetworkAvailability(appContext)
    private val miFitnessCredentialStore = MiFitnessCredentialStore(appContext)
    private val miFitnessAccountService = MiFitnessAccountService(appContext)
    private val workManager = WorkManager.getInstance(appContext)
    private val healthLoadEpoch = AtomicLong(0L)
    private var miFitnessWorkLiveData: LiveData<WorkInfo?>? = null
    private var miFitnessWorkObserver: Observer<WorkInfo?>? = null
    private val _healthState = MutableStateFlow(
        CaesarHealthUiState(miFitnessConfigured = miFitnessCredentialStore.hasCredentials()),
    )
    val healthState: StateFlow<CaesarHealthUiState> = _healthState.asStateFlow()
    private val cacheUpdateJob = viewModelScope.launch {
        cacheUpdates.collect { loadHealthStatus() }
    }
    fun refreshHealthStatus() {
        if (_healthState.value.loading) return
        viewModelScope.launch { loadHealthStatus() }
    }

    fun saveMiFitnessCredentials(userId: String, passToken: String) {
        if (_healthState.value.miFitnessSyncing) return
        healthLoadEpoch.incrementAndGet()
        _healthState.value = _healthState.value.copy(
            loading = false,
            miFitnessSyncing = true,
            miFitnessStatus = MiFitnessUiStatus.VALIDATING,
            miFitnessDeviceSyncStatus = null,
            healthError = null,
            actionMessage = "正在验证 Mi Fitness 凭据。",
        )
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                miFitnessAccountService.validateAndSave(userId, passToken)
            }.fold(
                onSuccess = { summary ->
                    val previous = _healthState.value
                    _healthState.value = previous.copy(
                        miFitnessConfigured = true,
                        miFitnessSyncing = false,
                        miFitnessStatus = MiFitnessUiStatus.SUCCESS,
                        miFitnessLastSyncAt = summary.lastSyncAt,
                        miFitnessFormResetKey = previous.miFitnessFormResetKey + 1L,
                        actionMessage = "Mi Fitness 已连接，今日健康已同步。",
                    )
                    loadHealthStatus("Mi Fitness 今日健康已同步。")
                },
                onFailure = { error ->
                    val previous = _healthState.value
                    val configured = miFitnessCredentialStore.hasCredentials()
                    val savedWithoutCloudData = configured &&
                        (error as? MiFitnessStepsSyncException)?.code == "no_cloud_data"
                    if (savedWithoutCloudData) {
                        val message = "Mi Fitness 已连接；今天还没有同步到健康数据。"
                        _healthState.value = previous.afterMiFitnessNoDataCredentialSave(message)
                        loadHealthStatus(message)
                    } else {
                        _healthState.value = previous.copy(
                            miFitnessConfigured = configured,
                            loading = false,
                            miFitnessSyncing = false,
                            miFitnessStatus = error.toMiFitnessUiStatus(),
                            actionMessage = null,
                        )
                    }
                },
            )
        }
    }

    fun refreshMiFitnessSteps() {
        if (_healthState.value.miFitnessSyncing) return
        if (!miFitnessCredentialStore.hasCredentials()) {
            _healthState.value = _healthState.value.copy(
                miFitnessConfigured = false,
                miFitnessStatus = MiFitnessUiStatus.AUTH_ERROR,
                actionMessage = null,
            )
            return
        }
        if (!network.isOnline()) {
            _healthState.value = _healthState.value.copy(
                miFitnessStatus = MiFitnessUiStatus.NETWORK_ERROR,
                actionMessage = null,
            )
            return
        }
        healthLoadEpoch.incrementAndGet()
        val requestId = runCatching { MiFitnessStepsSyncScheduler.enqueue(appContext) }
            .getOrElse {
                _healthState.value = _healthState.value.copy(
                    miFitnessStatus = MiFitnessUiStatus.STORAGE_ERROR,
                    actionMessage = null,
                )
                return
            }
        _healthState.value = _healthState.value.copy(
            loading = false,
            miFitnessConfigured = true,
            miFitnessSyncing = true,
            miFitnessStatus = MiFitnessUiStatus.REFRESHING,
            miFitnessDeviceSyncStatus = null,
            healthError = null,
            actionMessage = "正在请求手环后台同步并刷新云端今日健康。",
        )
        observeMiFitnessWork(requestId)
    }

    fun deleteMiFitnessCredentials() {
        if (
            _healthState.value.miFitnessSyncing &&
            _healthState.value.miFitnessStatus != MiFitnessUiStatus.REFRESHING
        ) return
        healthLoadEpoch.incrementAndGet()
        detachMiFitnessWorkObserver()
        workManager.cancelUniqueWork(MiFitnessStepsSyncScheduler.UNIQUE_WORK)
        _healthState.value = _healthState.value.copy(
            loading = false,
            miFitnessSyncing = true,
            miFitnessStatus = MiFitnessUiStatus.DELETING,
            miFitnessDeviceSyncStatus = null,
            actionMessage = null,
        )
        viewModelScope.launch {
            withContext(Dispatchers.IO) { miFitnessAccountService.delete() }.fold(
                onSuccess = {
                    val previous = _healthState.value
                    _healthState.value = previous.copy(
                        miFitnessConfigured = false,
                        miFitnessSyncing = false,
                        miFitnessStatus = MiFitnessUiStatus.IDLE,
                        miFitnessLastSyncAt = null,
                        miFitnessFormResetKey = previous.miFitnessFormResetKey + 1L,
                        snapshot = previous.snapshot?.takeUnless {
                            MiFitnessSummaryHealthGateway.SOURCE_ID in it.originPackages
                        },
                        actionMessage = "Mi Fitness 凭据与本地健康缓存已删除。",
                    )
                    loadHealthStatus("Mi Fitness 凭据与本地健康缓存已删除。")
                },
                onFailure = {
                    _healthState.value = _healthState.value.copy(
                        miFitnessConfigured = miFitnessCredentialStore.hasCredentials(),
                        loading = false,
                        miFitnessSyncing = false,
                        miFitnessStatus = MiFitnessUiStatus.STORAGE_ERROR,
                    )
                },
            )
        }
    }

    private suspend fun loadHealthStatus(actionMessage: String? = _healthState.value.actionMessage) {
        val requestEpoch = healthLoadEpoch.incrementAndGet()
        val before = _healthState.value
        val current = before.forCurrentHealthDay(nowMillis())
        val currentMessage = if (before.actionMessage != current.actionMessage) null else actionMessage
        _healthState.value = current.copy(loading = true)
        val availability = healthGateway.availability()
        val granted = runCatching { healthGateway.grantedPermissions() }.getOrDefault(emptySet())
        val requestedPeriod = HealthPeriods.parse("today", nowMillis())
        val healthResult = healthGateway.snapshot(requestedPeriod)
        if (healthLoadEpoch.get() != requestEpoch) return
        if (requestedPeriod.startEpochMillis != HealthPeriods.parse("today", nowMillis()).startEpochMillis) {
            // A visible day-change refresh may have met the in-flight loading guard. Read the new
            // local window now, instead of publishing yesterday or waiting for another user action.
            loadHealthStatus(null)
            return
        }
        val configured = miFitnessCredentialStore.hasCredentials()
        val previous = _healthState.value
        val cloudLastSync = healthResult.getOrNull()
            ?.takeIf { MiFitnessSummaryHealthGateway.SOURCE_ID in it.originPackages }
            ?.lastSyncAt
        _healthState.value = CaesarHealthUiState(
            loading = false,
            miFitnessConfigured = configured,
            miFitnessSyncing = previous.miFitnessSyncing,
            miFitnessStatus = previous.miFitnessStatus,
            miFitnessDeviceSyncStatus = previous.miFitnessDeviceSyncStatus,
            miFitnessLastSyncAt = cloudLastSync ?: previous.miFitnessLastSyncAt,
            miFitnessFormResetKey = previous.miFitnessFormResetKey,
            availability = availability,
            grantedPermissionCount = granted.size,
            requiredPermissionCount = healthGateway.readPermissions.size,
            snapshot = healthResult.getOrNull(),
            healthError = healthResult.exceptionOrNull()?.let { "当前没有可读取的健康缓存。" },
            actionMessage = currentMessage,
        ).forCurrentHealthDay(nowMillis())
    }

    private fun observeMiFitnessWork(requestId: UUID) {
        detachMiFitnessWorkObserver()
        val liveData = workManager.getWorkInfoByIdLiveData(requestId)
        lateinit var observer: Observer<WorkInfo?>
        observer = Observer { info ->
            if (info == null) return@Observer
            val deviceStatus = (if (info.state.isFinished) info.outputData else info.progress)
                .getString(MiFitnessStepsSyncWorker.KEY_DEVICE_SYNC_STATUS)
                ?.let { name -> MiFitnessDeviceSyncStatus.entries.find { it.name == name } }
            if (deviceStatus != null) {
                _healthState.value = _healthState.value.copy(miFitnessDeviceSyncStatus = deviceStatus)
            }
            if (!info.state.isFinished) return@Observer
            liveData.removeObserver(observer)
            if (miFitnessWorkObserver === observer) {
                miFitnessWorkLiveData = null
                miFitnessWorkObserver = null
            }
            viewModelScope.launch {
                if (info.state == WorkInfo.State.SUCCEEDED) {
                    _healthState.value = _healthState.value.copy(
                        miFitnessSyncing = false,
                        miFitnessStatus = MiFitnessUiStatus.SUCCESS,
                    )
                    loadHealthStatus("Mi Fitness 云端今日健康已刷新。")
                } else {
                    val code = info.outputData.getString(MiFitnessStepsSyncWorker.KEY_ERROR_CODE)
                    _healthState.value = _healthState.value.copy(
                        miFitnessConfigured = miFitnessCredentialStore.hasCredentials(),
                        miFitnessSyncing = false,
                        miFitnessStatus = code.toMiFitnessUiStatus(),
                        actionMessage = null,
                    )
                }
            }
        }
        miFitnessWorkLiveData = liveData
        miFitnessWorkObserver = observer
        liveData.observeForever(observer)
    }

    private fun detachMiFitnessWorkObserver() {
        val liveData = miFitnessWorkLiveData
        val observer = miFitnessWorkObserver
        if (liveData != null && observer != null) liveData.removeObserver(observer)
        miFitnessWorkLiveData = null
        miFitnessWorkObserver = null
    }

    private fun Throwable.toMiFitnessUiStatus(): MiFitnessUiStatus = when (
        (this as? MiFitnessStepsSyncException)?.code ?: (this as? MiFitnessAccountException)?.code
    ) {
        "invalid_credentials", "authentication_failed", "validation_failed", "credentials_missing" ->
            MiFitnessUiStatus.AUTH_ERROR
        "no_cloud_data" -> MiFitnessUiStatus.NO_DATA
        "network_failed", "rate_limited", "server_unavailable", "response_invalid", "record_out_of_window", "record_limit",
        "cursor_missing", "cursor_limit", "cursor_repeated", "page_limit", "aggregation_invalid",
        "sync_failed" -> MiFitnessUiStatus.NETWORK_ERROR
        else -> MiFitnessUiStatus.STORAGE_ERROR
    }

    private fun String?.toMiFitnessUiStatus(): MiFitnessUiStatus = when (this) {
        "credentials_missing", "authentication_failed" -> MiFitnessUiStatus.AUTH_ERROR
        "no_cloud_data" -> MiFitnessUiStatus.NO_DATA
        "network_failed", "rate_limited", "server_unavailable", "response_invalid", "record_out_of_window", "record_limit",
        "cursor_missing", "cursor_limit", "cursor_repeated", "page_limit", "aggregation_invalid",
        "sync_failed" -> MiFitnessUiStatus.NETWORK_ERROR
        else -> MiFitnessUiStatus.STORAGE_ERROR
    }

    fun close() {
        cacheUpdateJob.cancel()
        healthLoadEpoch.incrementAndGet()
        detachMiFitnessWorkObserver()
    }
}
