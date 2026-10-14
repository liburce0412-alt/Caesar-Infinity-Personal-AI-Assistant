package com.campusai.features.ai

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.campusai.core.ai.AiEngine
import com.campusai.core.ai.AiEngineRouter
import com.campusai.core.ai.AiEvent
import com.campusai.core.ai.AiExecutionEngine
import com.campusai.core.ai.AiRequest
import com.campusai.core.ai.AiRoutingException
import com.campusai.core.ai.CloudAiProvider
import com.campusai.core.ai.CloudDailyHealthSummary
import com.campusai.core.ai.CloudHealthDisclosure
import com.campusai.core.ai.NetworkAvailability
import com.campusai.core.ai.PersonalCloudAiEngine
import com.campusai.core.ai.ResolvedExecution
import com.campusai.core.agent.CaesarAgentEngine
import com.campusai.core.agent.CaesarAppTools
import com.campusai.core.agent.CaesarIdempotencyStore
import com.campusai.core.agent.CaesarMemoryStore
import com.campusai.core.agent.RoomCaesarTraceSink
import com.campusai.core.database.AiReportEntity
import com.campusai.core.database.AiReportWriteCoordinator
import com.campusai.core.database.CampusDao
import com.campusai.core.database.DailyGreetingEntity
import com.campusai.core.localai.LocalModelManager
import com.campusai.core.localai.LocalModelMode
import com.campusai.core.health.HealthAvailability
import com.campusai.core.health.HealthGateway
import com.campusai.core.health.HealthGatewayFactory
import com.campusai.core.health.HealthPeriods
import com.campusai.core.health.HealthSnapshot
import com.campusai.core.health.mifitness.MiFitnessAccountException
import com.campusai.core.health.mifitness.MiFitnessAccountService
import com.campusai.core.health.mifitness.MiFitnessCredentialStore
import com.campusai.core.health.mifitness.MiFitnessStepsSyncException
import com.campusai.core.health.mifitness.MiFitnessStepsSyncScheduler
import com.campusai.core.health.mifitness.MiFitnessStepsSyncWorker
import com.campusai.core.health.mifitness.MiFitnessSummaryHealthGateway
import com.campusai.core.model.AiConversationMessage
import com.campusai.core.model.AiMode
import com.campusai.core.model.AiProvider
import com.campusai.core.model.AiReport
import com.campusai.core.model.DailyGreeting
import com.campusai.core.network.PersonalCloudClient
import com.campusai.core.network.CaesarCloudFreeEngine
import com.campusai.core.preferences.UserPreferencesRepository
import com.campusai.core.security.PersonalAiProviderStore
import com.campusai.core.profile.ProfileRepository
import com.campusai.features.community.CampusRepository
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class AiUiState(
    val mode: AiMode = AiMode.FAST,
    val provider: AiProvider = AiProvider.CAESAR_CLOUD_FREE,
    val resolvedProvider: AiProvider? = null,
    val execution: ResolvedExecution? = null,
    val messages: List<AiConversationMessage> = emptyList(),
    val streaming: Boolean = false,
    val stage: String = "",
    val model: String = "",
    val elapsedMs: Long = 0,
    val error: String? = null,
    val errorCode: String? = null,
    val canUseCloudOnce: Boolean = false,
    val cloudOnceProviders: List<AiProvider> = emptyList(),
    val pendingCloudPrompt: String? = null,
    val pendingCloudDisplayPrompt: String? = null,
    val pendingContextJson: String? = null,
    val pendingPresentationJson: String? = null,
    val pendingCloudHealthDisclosure: CloudHealthDisclosure? = null,
    val contextSelection: AiContextSelection = AiContextSelection(),
    val pendingImages: List<CaesarImageAttachment> = emptyList(),
    val importingImage: Boolean = false,
    /** One-shot completion marker for ACTION_SEND imports; acknowledged by the current Activity. */
    val completedExternalImageImport: String? = null,
    val lockedLocalModelId: String? = null,
)

data class CaesarHealthUiState(
    val loading: Boolean = false,
    val miFitnessConfigured: Boolean = false,
    val miFitnessSyncing: Boolean = false,
    val miFitnessStatus: MiFitnessUiStatus = MiFitnessUiStatus.IDLE,
    val miFitnessLastSyncAt: Long? = null,
    val miFitnessFormResetKey: Long = 0L,
    val availability: HealthAvailability? = null,
    val grantedPermissionCount: Int = 0,
    val requiredPermissionCount: Int = 0,
    val snapshot: HealthSnapshot? = null,
    val healthError: String? = null,
    val actionMessage: String? = null,
)

enum class MiFitnessUiStatus {
    IDLE,
    VALIDATING,
    REFRESHING,
    DELETING,
    SUCCESS,
    NO_DATA,
    AUTH_ERROR,
    NETWORK_ERROR,
    STORAGE_ERROR,
}

data class CaesarMemoryUiItem(
    val id: String,
    val type: String,
    val content: String,
    val confirmed: Boolean,
    val expiresAt: Long?,
)

class AiViewModel(
    private val dao: CampusDao,
    context: Context,
    private val preferences: UserPreferencesRepository,
    private val modelManager: LocalModelManager,
    private val localEngine: AiEngine,
    private val personalProviderStore: PersonalAiProviderStore,
    private val campusRepository: CampusRepository = CampusRepository(),
    private val profileRepository: ProfileRepository = ProfileRepository(),
    private val personalDeepSeekEngine: AiEngine = PersonalCloudAiEngine(
        PersonalCloudClient(CloudAiProvider.DEEPSEEK, personalProviderStore),
    ),
    private val personalGeminiEngine: AiEngine = PersonalCloudAiEngine(
        PersonalCloudClient(CloudAiProvider.GOOGLE_GEMINI, personalProviderStore),
    ),
    private val personalCodexEngine: AiEngine = PersonalCloudAiEngine(
        PersonalCloudClient(CloudAiProvider.CODEX, personalProviderStore),
    ),
) : ViewModel() {
    private val appContext = context.applicationContext
    private val provider = AtomicReference(AiProvider.CAESAR_CLOUD_FREE)
    private val network = NetworkAvailability(appContext)
    private val imageProcessor = CaesarImageProcessor(appContext)
    private val memoryStore = CaesarMemoryStore(dao)
    private val miFitnessCredentialStore = MiFitnessCredentialStore(appContext)
    private val miFitnessAccountService = MiFitnessAccountService(appContext)
    private val workManager = WorkManager.getInstance(appContext)
    private val healthGateway: HealthGateway = HealthGatewayFactory.create(appContext)
    private val router = AiEngineRouter(
        personalDeepSeek = personalDeepSeekEngine,
        local = localEngine,
        provider = provider::get,
        personalKeyAvailable = { personalProviderStore.hasCredential(CloudAiProvider.DEEPSEEK) },
        isOnline = network::isOnline,
        localState = { modelId -> modelManager.runtimeFor(modelId).selection.state },
        personalGoogleGemini = personalGeminiEngine,
        geminiKeyAvailable = { personalProviderStore.hasCredential(CloudAiProvider.GOOGLE_GEMINI) },
        personalCodex = personalCodexEngine,
        codexKeyAvailable = { personalProviderStore.hasCredential(CloudAiProvider.CODEX) },
        caesarCloudFree = CaesarCloudFreeEngine(),
    )
    private val caesarEngine = CaesarAgentEngine(
        delegate = router,
        tools = CaesarAppTools(
            dao = dao,
            campus = campusRepository,
            profile = profileRepository,
            health = healthGateway,
            idempotency = CaesarIdempotencyStore(dao),
            memory = memoryStore,
        ).registry(),
        traceSink = RoomCaesarTraceSink(dao),
    )
    private val _state = MutableStateFlow(AiUiState())
    val state: StateFlow<AiUiState> = _state.asStateFlow()
    private val _healthState = MutableStateFlow(
        CaesarHealthUiState(miFitnessConfigured = miFitnessCredentialStore.hasCredentials()),
    )
    val healthState: StateFlow<CaesarHealthUiState> = _healthState.asStateFlow()
    val localModelSelection = modelManager.selection
    val localModelStates = modelManager.states
    fun localModelFor(modelId: String) = modelManager.runtimeFor(modelId).selection
    val history: StateFlow<List<AiReport>> = dao.getAiReportsFlow().map { rows -> rows.map(AiReportEntity::toDomain) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val memories: StateFlow<List<CaesarMemoryUiItem>> = dao.getAgentMemoriesFlow().map { rows ->
        rows.map { row -> CaesarMemoryUiItem(row.id, row.type, row.content, row.confirmedAt != null, row.expiresAt) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _dailyGreeting = MutableStateFlow<DailyGreeting?>(null)
    val dailyGreeting: StateFlow<DailyGreeting?> = _dailyGreeting.asStateFlow()

    private var generation: Job? = null
    private var pendingRegeneratedTail: List<AiConversationMessage>? = null
    private val generationEpoch = AiGenerationEpoch()
    private val imageImportGate = AiImageImportGate()
    private var activeGenerationRollback: (() -> Unit)? = null
    private var greetingJob: Job? = null
    private var greetingAttemptedDate: String? = null
    private var conversationId = UUID.randomUUID().toString()
    private var conversationCreatedAt = System.currentTimeMillis()
    private var conversationTitle = ""
    private var conversationLocalModelId: String? = null
    private var recentlyDeleted: AiReport? = null
    private var recentlyDeletedCleanup: Job? = null
    private var activeOwnerUserId: String = ""
    private val healthLoadEpoch = AtomicLong(0L)
    private var miFitnessWorkLiveData: LiveData<WorkInfo?>? = null
    private var miFitnessWorkObserver: Observer<WorkInfo?>? = null

    init {
        viewModelScope.launch {
            preferences.preferences.collect { value ->
                provider.set(value.aiProvider)
                _state.value = _state.value.copy(provider = value.aiProvider)
            }
        }
    }

    fun setMode(mode: AiMode) {
        if (!_state.value.streaming) _state.value = _state.value.copy(mode = mode, error = null, errorCode = null)
    }

    fun selectLocalModel(mode: LocalModelMode) {
        if (_state.value.streaming) return
        modelManager.selectMode(mode)
    }

    fun setProvider(value: AiProvider) {
        if (_state.value.streaming) return
        provider.set(value)
        _state.value = _state.value.copy(
            provider = value,
            mode = if (value == AiProvider.LOCAL) AiMode.FAST else _state.value.mode,
            model = "",
            resolvedProvider = null,
            error = null,
            errorCode = null,
            canUseCloudOnce = false,
            cloudOnceProviders = emptyList(),
            pendingCloudPrompt = null,
            pendingCloudDisplayPrompt = null,
            pendingContextJson = null,
            pendingPresentationJson = null,
            pendingCloudHealthDisclosure = null,
        )
        viewModelScope.launch { preferences.setAiProvider(value) }
    }

    fun setContextSelection(value: AiContextSelection) {
        if (!_state.value.streaming) _state.value = _state.value.copy(contextSelection = value)
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
            healthError = null,
            actionMessage = "正在同步 Mi Fitness 今日健康。",
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
        _healthState.value = _healthState.value.copy(loading = true)
        val availability = healthGateway.availability()
        val granted = runCatching { healthGateway.grantedPermissions() }.getOrDefault(emptySet())
        val healthResult = healthGateway.snapshot(HealthPeriods.parse("today"))
        if (healthLoadEpoch.get() != requestEpoch) return
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
            miFitnessLastSyncAt = cloudLastSync ?: previous.miFitnessLastSyncAt,
            miFitnessFormResetKey = previous.miFitnessFormResetKey,
            availability = availability,
            grantedPermissionCount = granted.size,
            requiredPermissionCount = healthGateway.readPermissions.size,
            snapshot = healthResult.getOrNull(),
            healthError = healthResult.exceptionOrNull()?.let { "当前没有可读取的健康缓存。" },
            actionMessage = actionMessage,
        )
    }

    private fun observeMiFitnessWork(requestId: UUID) {
        detachMiFitnessWorkObserver()
        val liveData = workManager.getWorkInfoByIdLiveData(requestId)
        lateinit var observer: Observer<WorkInfo?>
        observer = Observer { info ->
            if (info == null || !info.state.isFinished) return@Observer
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
                    loadHealthStatus("Mi Fitness 今日健康已同步。")
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

    fun attachImage(uri: android.net.Uri, externalShare: Boolean = false): Boolean {
        if (_state.value.streaming || _state.value.importingImage || _state.value.pendingImages.size >= 4) return false
        val targetConversationId = conversationId
        val ticket = imageImportGate.begin(targetConversationId)
        val protectedRefs = buildSet {
            addAll(_state.value.messages.flatMap { it.attachmentRefs }.map { it.relativePath })
            addAll(_state.value.pendingImages.mapNotNull { it.imageRef?.relativePath })
        }
        _state.value = _state.value.copy(
            importingImage = true,
            completedExternalImageImport = if (externalShare) null else _state.value.completedExternalImageImport,
            error = null,
            errorCode = null,
        )
        viewModelScope.launch {
            try {
                runCatching { imageProcessor.import(uri, targetConversationId) }.fold(
                    onSuccess = { image ->
                        if (imageImportGate.owns(ticket, conversationId)) {
                            _state.value = _state.value.copy(
                                pendingImages = _state.value.pendingImages + image,
                                importingImage = false,
                            )
                        } else {
                            imageProcessor.delete(image, protectedRefs)
                        }
                    },
                    onFailure = { error ->
                        if (imageImportGate.owns(ticket, conversationId)) {
                            _state.value = _state.value.copy(
                                importingImage = false,
                                error = error.message ?: "图片导入失败",
                                errorCode = "image_import_failed",
                            )
                        }
                    },
                )
            } finally {
                if (externalShare) {
                    _state.value = _state.value.copy(completedExternalImageImport = uri.toString())
                }
            }
        }
        return true
    }

    fun acknowledgeExternalImageImport(uri: String) {
        if (_state.value.completedExternalImageImport != uri) return
        _state.value = _state.value.copy(completedExternalImageImport = null)
    }

    fun removeImage(index: Int) {
        if (_state.value.streaming) return
        val image = _state.value.pendingImages.getOrNull(index) ?: return
        val referenced = buildSet {
            addAll(_state.value.messages.flatMap { it.attachmentRefs }.map { it.relativePath })
            _state.value.pendingImages.forEachIndexed { pendingIndex, pending ->
                if (pendingIndex != index) pending.imageRef?.relativePath?.let(::add)
            }
        }
        imageProcessor.delete(image, referenced)
        _state.value = _state.value.copy(pendingImages = _state.value.pendingImages.toMutableList().also { it.removeAt(index) })
    }

    fun performSurfaceAction(actionId: String) {
        if (_state.value.streaming) return
        if (actionId.startsWith("memory.confirm:")) {
            val id = actionId.removePrefix("memory.confirm:")
            viewModelScope.launch {
                val confirmed = memoryStore.confirm(id)
                _state.value = _state.value.copy(
                    error = if (confirmed) null else "这条记忆已过期或不存在。",
                    errorCode = if (confirmed) null else "memory_confirmation_failed",
                )
            }
            return
        }
        if (!actionId.startsWith("confirm:")) return
        val messagesBeforeAction = _state.value.messages
        val pendingImagesBeforeAction = _state.value.pendingImages
        val executionBeforeAction = _state.value.execution
        val modelBeforeAction = _state.value.model
        val resolvedProviderBeforeAction = _state.value.resolvedProvider
        val lockedLocalModelBeforeAction = _state.value.lockedLocalModelId
        activeGenerationRollback = {
            val failed = _state.value
            _state.value = failed.copy(
                messages = messagesBeforeAction,
                pendingImages = pendingImagesBeforeAction,
                streaming = false,
                stage = "",
                execution = executionBeforeAction,
                model = modelBeforeAction,
                resolvedProvider = resolvedProviderBeforeAction,
                lockedLocalModelId = lockedLocalModelBeforeAction,
                error = failed.error ?: "生成中断，请重试。",
                errorCode = failed.errorCode ?: "generation_incomplete",
            )
        }
        _state.value = _state.value.copy(streaming = true, stage = "正在执行已确认操作", error = null, errorCode = null)
        launchGeneration { token ->
            val completion = AiGenerationCompletionTracker()
            try {
                caesarEngine.confirm(actionId).collect { event ->
                    completion.accept(event)
                    consumeEvent(event)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                completion.fail()
                if (generationEpoch.isCurrent(token)) {
                    _state.value = _state.value.copy(streaming = false, stage = "", error = error.message ?: "确认操作执行失败", errorCode = "confirmation_failed")
                }
            }
            if (generationEpoch.isCurrent(token)) {
                if (completion.completedNormally) {
                    activeGenerationRollback = null
                    saveReport()
                } else {
                    activeGenerationRollback?.invoke()
                    activeGenerationRollback = null
                }
            }
        }
    }

    fun confirmMemory(id: String) {
        viewModelScope.launch {
            val confirmed = memoryStore.confirm(id)
            if (!confirmed) _state.value = _state.value.copy(error = "这条记忆已过期或不存在。", errorCode = "memory_confirmation_failed")
        }
    }

    fun updateMemory(id: String, content: String) {
        viewModelScope.launch {
            val result = runCatching { memoryStore.updateContent(id, content) }
            if (result.getOrNull() != true) {
                _state.value = _state.value.copy(
                    error = result.exceptionOrNull()?.message ?: "这条记忆已不存在。",
                    errorCode = "memory_update_failed",
                )
            }
        }
    }

    fun forgetMemory(id: String) {
        viewModelScope.launch { memoryStore.forget(id) }
    }

    fun forgetAllMemories() {
        viewModelScope.launch { memoryStore.forgetAll() }
    }

    fun clearError() {
        _state.value = _state.value.copy(
            error = null,
            errorCode = null,
            canUseCloudOnce = false,
            cloudOnceProviders = emptyList(),
            pendingCloudPrompt = null,
            pendingCloudDisplayPrompt = null,
            pendingContextJson = null,
            pendingPresentationJson = null,
            pendingCloudHealthDisclosure = null,
        )
    }

    fun newConversation() {
        if (_state.value.streaming) return
        imageImportGate.invalidate()
        pendingRegeneratedTail = null
        val referenced = _state.value.messages.flatMap { it.attachmentRefs }.map { it.relativePath }.toSet()
        _state.value.pendingImages.forEach { imageProcessor.delete(it, referenced) }
        conversationId = UUID.randomUUID().toString()
        conversationCreatedAt = System.currentTimeMillis()
        conversationTitle = ""
        conversationLocalModelId = null
        _state.value = AiUiState(
            mode = _state.value.mode,
            provider = provider.get(),
            contextSelection = _state.value.contextSelection,
        )
    }

    fun send(prompt: String, snapshot: AiContextSnapshot) {
        activeOwnerUserId = snapshot.userId
        CaesarDeterministicReply.forPrompt(prompt, hasImages = _state.value.pendingImages.isNotEmpty())?.let { reply ->
            _state.value = _state.value.copy(
                contextSelection = _state.value.contextSelection.copy(healthSummary = false),
            )
            appendDeterministicReply(prompt, reply)
            return
        }
        val base = CampusAiTaskFactory.create(CampusAiTask.CHAT, snapshot.records, snapshot.courses, prompt)
        sendInternal(base.withContext(CampusAiTask.CHAT, prompt, snapshot), cloudOnce = false)
    }

    fun regenerateLastResponse(snapshot: AiContextSnapshot) {
        if (_state.value.streaming || _state.value.importingImage) return
        val originalMessages = _state.value.messages
        val plan = planAiRegeneration(originalMessages) ?: return
        activeOwnerUserId = snapshot.userId
        pendingRegeneratedTail = plan.replacedTail
        _state.value = _state.value.copy(
            messages = plan.history,
            error = null,
            errorCode = null,
        )
        val base = CampusAiTaskFactory.create(CampusAiTask.CHAT, snapshot.records, snapshot.courses, plan.prompt.content)
        sendInternal(
            payload = base.withContext(CampusAiTask.CHAT, plan.prompt.content, snapshot),
            cloudOnce = false,
            failureMessagesOverride = originalMessages,
        )
    }

    private fun appendDeterministicReply(prompt: String, reply: String) {
        val displayPrompt = prompt.trim()
        if (displayPrompt.isBlank() || _state.value.streaming) return
        greetingJob?.cancel()
        greetingJob = null
        val existing = _state.value.messages.dropLastWhile { it.role == "assistant" && it.content.isBlank() }
        if (conversationTitle.isBlank()) conversationTitle = displayPrompt.take(42)
        _state.value = _state.value.copy(
            messages = existing +
                AiConversationMessage("user", displayPrompt) +
                AiConversationMessage("assistant", reply),
            streaming = false,
            stage = "",
            model = "Caesar∞ · 本机事实",
            resolvedProvider = AiProvider.LOCAL,
            execution = ResolvedExecution(
                provider = AiProvider.LOCAL,
                model = "Caesar∞ · 本机事实",
                engine = AiExecutionEngine.LOCAL_DETERMINISTIC,
                requestId = UUID.randomUUID().toString(),
            ),
            error = null,
            errorCode = null,
            canUseCloudOnce = false,
            cloudOnceProviders = emptyList(),
            pendingCloudPrompt = null,
            pendingCloudDisplayPrompt = null,
            pendingContextJson = null,
            pendingPresentationJson = null,
            pendingCloudHealthDisclosure = null,
            elapsedMs = 0,
        )
        viewModelScope.launch { saveReport() }
    }

    fun sendTask(task: CampusAiTask, snapshot: AiContextSnapshot, userInput: String = "") {
        activeOwnerUserId = snapshot.userId
        val base = CampusAiTaskFactory.create(task, snapshot.records, snapshot.courses, userInput)
        sendInternal(base.withContext(task, userInput, snapshot), cloudOnce = false)
    }

    private fun CampusAiPayload.withContext(
        task: CampusAiTask,
        prompt: String,
        snapshot: AiContextSnapshot,
    ) = copy(
        structuredContext = AiContextAssembler.enrich(
            base = structuredContext,
            task = task,
            prompt = prompt,
            snapshot = snapshot,
            selection = _state.value.contextSelection,
        ),
    )

    fun useCloudOnce(cloudProvider: AiProvider) {
        if (cloudProvider !in _state.value.cloudOnceProviders) return
        val prompt = _state.value.pendingCloudPrompt ?: return
        val displayPrompt = _state.value.pendingCloudDisplayPrompt ?: prompt
        val context = JSONObject(_state.value.pendingContextJson ?: "{}")
        val presentation = AiTaskPresentation.fromJson(_state.value.pendingPresentationJson)
        val disclosure = _state.value.pendingCloudHealthDisclosure ?: CloudHealthDisclosure.Excluded
        sendInternal(
            CampusAiPayload(prompt, context, displayPrompt, presentation),
            cloudOnce = true,
            cloudOnceProvider = cloudProvider,
            cloudHealthDisclosureOverride = disclosure,
        )
    }

    fun openConversation(report: AiReport) {
        if (_state.value.streaming) return
        imageImportGate.invalidate()
        pendingRegeneratedTail = null
        val previousRefs = _state.value.messages.flatMap { it.attachmentRefs }.map { it.relativePath }.toSet()
        _state.value.pendingImages.forEach { imageProcessor.delete(it, previousRefs) }
        val decoded = parseMessages(report.messagesJson)
        val messages = imageProcessor.hydrate(decoded)
        val hasLegacyImages = decoded.any { message ->
            message.attachmentRefs.isEmpty() && message.attachmentPaths.isNotEmpty()
        }
        conversationId = report.id
        conversationCreatedAt = report.createdAt
        conversationTitle = report.title
        conversationLocalModelId = modelManager.modelIdFromLabel(report.model)
        _state.value = _state.value.withOpenedConversation(
            report = report,
            messages = messages,
            selectedProvider = provider.get(),
            localModelId = conversationLocalModelId,
        ).copy(importingImage = hasLegacyImages)
        if (!hasLegacyImages) return
        viewModelScope.launch(Dispatchers.IO) {
            var migratedForUi: List<AiConversationMessage>? = null
            val migrationFailure = runCatching {
                AiReportWriteCoordinator.withLock {
                    val latest = dao.getAiReport(report.id)?.toDomain() ?: return@withLock
                    val latestDecoded = parseMessages(latest.messagesJson)
                    val migrated = imageProcessor.migrateLegacy(latest.id, latestDecoded)
                    migratedForUi = migrated
                    if (migrated != imageProcessor.hydrate(latestDecoded)) {
                        dao.insertAiReport(AiReportEntity.fromDomain(latest.copy(messagesJson = AiConversationCodec.encode(migrated))))
                    }
                }
            }.exceptionOrNull()
            withContext(Dispatchers.Main) {
                if (conversationId == report.id && !_state.value.streaming) {
                    _state.value = _state.value.copy(
                        messages = migratedForUi ?: messages,
                        importingImage = false,
                        error = migrationFailure?.let { "历史图片迁移未完成，已保留可用内容。" },
                        errorCode = migrationFailure?.let { "image_migration_failed" },
                    )
                }
            }
        }
    }

    fun deleteConversation(report: AiReport) {
        if (_state.value.streaming) return
        val displaced = recentlyDeleted
        recentlyDeletedCleanup?.cancel()
        if (displaced != null && displaced.id != report.id) {
            viewModelScope.launch(Dispatchers.IO) { imageProcessor.deleteConversation(displaced.id) }
        }
        recentlyDeleted = report
        viewModelScope.launch {
            AiReportWriteCoordinator.withLock { dao.deleteAiReport(report.id) }
            if (report.id == conversationId) newConversation()
            recentlyDeletedCleanup = viewModelScope.launch {
                delay(DELETED_CONVERSATION_UNDO_MILLIS)
                if (recentlyDeleted?.id == report.id) {
                    recentlyDeleted = null
                    withContext(Dispatchers.IO) { imageProcessor.deleteConversation(report.id) }
                }
            }
        }
    }

    fun undoDeleteConversation() {
        val report = recentlyDeleted ?: return
        recentlyDeleted = null
        recentlyDeletedCleanup?.cancel()
        recentlyDeletedCleanup = null
        viewModelScope.launch {
            AiReportWriteCoordinator.withLock {
                val current = dao.getAiReport(report.id)?.toDomain()
                val restored = if (current == null) {
                    report
                } else {
                    val merged = mergePersistedConversationMessages(
                        current = parseMessages(report.messagesJson),
                        persisted = parseMessages(current.messagesJson),
                    )
                    current.copy(
                        summary = merged.lastOrNull { it.role == "assistant" }?.content.orEmpty().take(160),
                        messagesJson = AiConversationCodec.encode(merged),
                        createdAt = minOf(report.createdAt, current.createdAt),
                        updatedAt = maxOf(report.updatedAt, current.updatedAt),
                    )
                }
                dao.insertAiReport(AiReportEntity.fromDomain(restored))
            }
        }
    }

    fun ensureDailyGreeting(snapshot: AiContextSnapshot) {
        val zone = ZoneId.systemDefault()
        val date = LocalDate.now(zone)
        val cacheId = "${snapshot.userId.ifBlank { "local" }}:$date"
        val dateKey = date.toString()
        val current = _dailyGreeting.value
        if (current?.localDate == date.toString() && DailyGreetingPolicy.isGrounded(current.text, snapshot, zone = zone)) return
        if (greetingAttemptedDate == dateKey) return
        if (greetingJob?.isActive == true) return
        greetingAttemptedDate = dateKey
        greetingJob = viewModelScope.launch {
            dao.getDailyGreeting(cacheId)?.toDomain()?.let { cached ->
                val isOldFallback = cached.text == DailyGreetingPolicy.fallback(snapshot.displayName, date)
                if (!isOldFallback && DailyGreetingPolicy.isGrounded(cached.text, snapshot, zone = zone)) {
                    _dailyGreeting.value = cached
                    greetingJob = null
                    return@launch
                }
            }
            val fallback = DailyGreetingPolicy.fallback(snapshot.displayName, date)
            _dailyGreeting.value = DailyGreeting(date.toString(), fallback, AiProvider.LOCAL, System.currentTimeMillis())
            if (_state.value.streaming || provider.get() == AiProvider.CAESAR_CLOUD_FREE) {
                greetingJob = null
                return@launch
            }

            val context = AiContextAssembler.greetingContext(snapshot)
            val request = AiRequest(
                mode = AiMode.FAST,
                messages = listOf(
                    AiConversationMessage(
                        "user",
                        "随机创作一句 12 至 20 个汉字的首页短副文案。用行动感或克制意象，不写问句；不要写具体钟点、天气、校园状态，缺少课程就绝不提上课或课程。风格示例仅供理解：把注意力交给眼前这一件事。不要照抄示例。灵感编号：${UUID.randomUUID().toString().take(8)}",
                    ),
                ),
                structuredContextJson = context.toString(),
                maxOutputTokens = 48,
            )
            val answer = StringBuilder()
            var resolved = AiProvider.LOCAL
            var failed = false
            runCatching {
                router.stream(request).collect { event ->
                    when (event) {
                        is AiEvent.Meta -> resolved = event.provider
                        is AiEvent.Delta -> answer.append(event.text)
                        is AiEvent.Error -> failed = true
                        else -> Unit
                    }
                }
            }.onFailure { failed = true }
            val candidate = if (failed) "" else sanitizeGreeting(answer.toString())
            val text = candidate.takeIf { it.isNotBlank() && DailyGreetingPolicy.isGrounded(it, snapshot, zone = zone) } ?: fallback
            if (text == fallback) {
                _dailyGreeting.value = DailyGreeting(date.toString(), fallback, AiProvider.LOCAL, System.currentTimeMillis())
            } else {
                persistGreeting(cacheId, date, text, resolved)
            }
            greetingJob = null
        }
    }

    private suspend fun persistGreeting(id: String, date: LocalDate, text: String, source: AiProvider) {
        val generatedAt = System.currentTimeMillis()
        dao.insertDailyGreeting(DailyGreetingEntity(id, date.toString(), text, source.name, generatedAt))
        _dailyGreeting.value = DailyGreeting(date.toString(), text, source, generatedAt)
    }

    private fun sanitizeGreeting(raw: String): String = raw
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("[\r\n]+"), " ")
        .replace(Regex("^[\\s\"“”'‘’]+|[\\s\"“”'‘’]+$"), "")
        .substringBefore("。")
        .trim()
        .take(24)

    fun cancel() {
        val activeJob = generation
        generation = null
        activeGenerationRollback?.invoke()
        activeGenerationRollback = null
        generationEpoch.invalidate()
        caesarEngine.cancel()
        router.cancel()
        personalDeepSeekEngine.cancel()
        personalGeminiEngine.cancel()
        personalCodexEngine.cancel()
        localEngine.cancel()
        activeJob?.cancel()
        _state.value = _state.value.copy(streaming = false, stage = "", error = "已停止本次生成。", errorCode = "cancelled")
    }

    private fun sendInternal(
        payload: CampusAiPayload,
        cloudOnce: Boolean,
        cloudOnceProvider: AiProvider = AiProvider.DEEPSEEK,
        cloudHealthDisclosureOverride: CloudHealthDisclosure? = null,
        failureMessagesOverride: List<AiConversationMessage>? = null,
    ) {
        val prompt = payload.prompt
        val displayPrompt = payload.displayPrompt.ifBlank { prompt }
        if (prompt.isBlank() || _state.value.streaming || _state.value.importingImage) return
        greetingJob?.cancel()
        greetingJob = null
        router.cancel()
        if (cloudOnce && !network.isOnline()) {
            _state.value = _state.value.copy(
                error = "当前没有可用网络，无法在本次使用云端 Provider。",
                errorCode = "cloud_offline",
                canUseCloudOnce = false,
                cloudOnceProviders = emptyList(),
            )
            return
        }
        val existing = if (cloudOnce) {
            val withoutPlaceholder = _state.value.messages.dropLastWhile { it.role == "assistant" && it.content.isBlank() }
            if (withoutPlaceholder.lastOrNull()?.role == "user") withoutPlaceholder.dropLast(1) else withoutPlaceholder
        } else {
            _state.value.messages
        }
        val pendingImagesAtStart = _state.value.pendingImages
        val executionBeforeTurn = _state.value.execution
        val modelBeforeTurn = _state.value.model
        val resolvedProviderBeforeTurn = _state.value.resolvedProvider
        val lockedLocalModelBeforeTurn = _state.value.lockedLocalModelId
        val attachments = if (cloudOnce) emptyList() else _state.value.pendingImages
        val localModelId = conversationLocalModelId
            ?: modelManager.selection.value.manifest.id.also { conversationLocalModelId = it }
        if (conversationTitle.isBlank()) conversationTitle = displayPrompt.trim().take(42)
        val cloudHealthDisclosure = cloudHealthDisclosureOverride ?: currentCloudHealthDisclosure()
        val selectedProviderForTurn = if (cloudOnce) cloudOnceProvider else provider.get()
        val allowWebSearch = selectedProviderForTurn in setOf(
            AiProvider.DEEPSEEK,
            AiProvider.GOOGLE_GEMINI,
            AiProvider.CODEX,
        )
        val cloudHealthSensitive = cloudHealthDisclosure is CloudHealthDisclosure.Included
        val initial = existing +
            AiConversationMessage(
                "user",
                displayPrompt.trim(),
                attachmentPaths = attachments.map(CaesarImageAttachment::localPath),
                attachmentRefs = attachments.mapNotNull(CaesarImageAttachment::imageRef),
                cloudHealthSensitive = cloudHealthSensitive,
            ) +
            AiConversationMessage(
                "assistant",
                "",
                payload.presentation?.toJson(),
                cloudHealthSensitive = cloudHealthSensitive,
            )
        _state.value = _state.value.copy(
            messages = initial,
            streaming = true,
            stage = "思考中",
            error = null,
            errorCode = null,
            canUseCloudOnce = false,
            cloudOnceProviders = emptyList(),
            pendingCloudPrompt = null,
            pendingCloudDisplayPrompt = null,
            pendingContextJson = null,
            pendingPresentationJson = null,
            pendingCloudHealthDisclosure = null,
            elapsedMs = 0,
            pendingImages = emptyList(),
            importingImage = false,
            lockedLocalModelId = localModelId,
            execution = null,
            resolvedProvider = null,
            model = "",
            contextSelection = _state.value.contextSelection.copy(healthSummary = false),
        )
        val request = assembleCaesarTurnRequest(
            mode = _state.value.mode,
            existingMessages = existing,
            prompt = prompt,
            displayPrompt = displayPrompt,
            structuredContext = payload.structuredContext,
            attachments = attachments,
            sessionId = conversationId,
            ownerUserId = activeOwnerUserId,
            localModelId = localModelId,
            cloudOnce = cloudOnce,
            allowCodexImageUpload = !cloudOnce && provider.get() == AiProvider.CODEX,
            allowCloudFreeImageUpload = !cloudOnce && provider.get() == AiProvider.CAESAR_CLOUD_FREE,
        ).withCloudHealthDisclosure(cloudHealthDisclosure).copy(allowWebSearch = allowWebSearch)
        activeGenerationRollback = {
            val failed = _state.value
            _state.value = failed.withFailureExecutionFallback(
                previousExecution = executionBeforeTurn,
                previousModel = modelBeforeTurn,
                previousResolvedProvider = resolvedProviderBeforeTurn,
            ).copy(
                messages = failureMessagesOverride ?: existing,
                pendingImages = pendingImagesAtStart,
                streaming = false,
                stage = "",
                lockedLocalModelId = lockedLocalModelBeforeTurn,
                error = failed.error ?: "生成中断，请重试。",
                errorCode = failed.errorCode ?: "generation_incomplete",
            )
            pendingRegeneratedTail = null
        }
        launchGeneration { token ->
            val completion = AiGenerationCompletionTracker()
            val requestWithMemory = if (cloudOnce) request else request.copy(
                structuredContextJson = JSONObject(request.structuredContextJson)
                    .put("confirmedMemories", JSONArray(memoryStore.context().map { memory ->
                        JSONObject().put("id", memory.id).put("type", memory.type).put("content", memory.content).put("confidence", memory.confidence)
                    }))
                    .toString(),
            )
            try {
                val events = if (cloudOnce) {
                    router.streamCloudOnce(requestWithMemory, cloudOnceProvider)
                } else {
                    caesarEngine.stream(requestWithMemory)
                }
                events.collect { event ->
                    completion.accept(event)
                    consumeEvent(event)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                completion.fail()
                if (generationEpoch.isCurrent(token)) {
                    val routing = error as? AiRoutingException
                    val fallbackProviders = if (canOfferCloudFallback(request, routing)) configuredCloudProviders() else emptyList()
                    _state.value = _state.value.copy(
                        streaming = false,
                        stage = "",
                        error = generationFailureMessage(error),
                        errorCode = routing?.code ?: "generation_failed",
                        canUseCloudOnce = fallbackProviders.isNotEmpty(),
                        cloudOnceProviders = fallbackProviders,
                        pendingCloudPrompt = prompt.takeIf { fallbackProviders.isNotEmpty() },
                        pendingCloudDisplayPrompt = displayPrompt.takeIf { fallbackProviders.isNotEmpty() },
                        pendingContextJson = request.structuredContextJson.takeIf { fallbackProviders.isNotEmpty() },
                        pendingPresentationJson = payload.presentation?.toJson().takeIf { fallbackProviders.isNotEmpty() },
                        pendingCloudHealthDisclosure = cloudHealthDisclosure.takeIf { fallbackProviders.isNotEmpty() },
                    )
                }
            }
            if (generationEpoch.isCurrent(token)) {
                if (!completion.completedNormally) {
                    activeGenerationRollback?.invoke()
                    activeGenerationRollback = null
                } else {
                    activeGenerationRollback = null
                    saveReport()
                }
            }
        }
    }

    private fun configuredCloudProviders(): List<AiProvider> = buildList {
        if (personalProviderStore.hasCredential(CloudAiProvider.DEEPSEEK)) add(AiProvider.DEEPSEEK)
        if (personalProviderStore.hasCredential(CloudAiProvider.GOOGLE_GEMINI)) add(AiProvider.GOOGLE_GEMINI)
        if (personalProviderStore.hasCredential(CloudAiProvider.CODEX)) add(AiProvider.CODEX)
    }

    private fun currentCloudHealthDisclosure(): CloudHealthDisclosure {
        if (!_state.value.contextSelection.healthSummary) return CloudHealthDisclosure.Excluded
        val metrics = _healthState.value.snapshot?.metrics ?: return CloudHealthDisclosure.Excluded
        return CloudHealthDisclosure.Included(
            CloudDailyHealthSummary(
                localDate = LocalDate.now(ZoneId.systemDefault()).toString(),
                steps = metrics.steps,
                distanceMeters = metrics.distanceMeters,
                activeCaloriesKcal = metrics.activeCaloriesKcal,
                activityMinutes = metrics.activityDurationMinutes,
                sleepMinutes = metrics.sleepMinutes,
                averageHeartRateBpm = metrics.heartRateAverageBpm?.toDouble(),
                averageOxygenSaturationPercent = metrics.oxygenSaturationAveragePercent,
                averageStressScore = metrics.stressAverage?.toDouble(),
                workoutCount = metrics.workoutCount?.toLong(),
            ),
        )
    }

    private fun launchGeneration(block: suspend (token: Long) -> Unit) {
        val token = generationEpoch.begin()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                block(token)
            } finally {
                if (generationEpoch.isCurrent(token)) {
                    activeGenerationRollback?.invoke()
                    activeGenerationRollback = null
                    generation = null
                }
            }
        }
        generation = job
        job.start()
    }

    private fun consumeEvent(event: AiEvent) {
        when (event) {
            is AiEvent.Meta -> _state.value = _state.value.copy(
                execution = event.execution,
                model = event.execution.model,
                resolvedProvider = event.execution.provider,
            )
            is AiEvent.Status -> _state.value = _state.value.copy(stage = event.stage, elapsedMs = event.elapsedMs)
            is AiEvent.Delta -> {
                if (event.text.isEmpty()) return
                _state.value = _state.value.copy(messages = _state.value.messages.toMutableList().also { list ->
                    val last = list.last()
                    list[list.lastIndex] = last.copy(content = last.content + event.text)
                })
            }
            is AiEvent.Done -> _state.value = _state.value.copy(
                streaming = false,
                stage = "",
                elapsedMs = event.elapsedMs,
                messages = _state.value.messages.toMutableList().also { list ->
                    val last = list.lastOrNull() ?: return@also
                    list[list.lastIndex] = last.copy(providerReasoningContent = event.providerReasoningContent)
                },
            )
            is AiEvent.Error -> _state.value = _state.value.copy(
                streaming = false,
                error = event.message,
                errorCode = event.code,
                stage = "",
                messages = if (event.code in OUTPUT_CLEARING_ERRORS) clearPartialAssistant(_state.value.messages) else _state.value.messages,
            )
            is AiEvent.ToolCallRequested -> Unit
            is AiEvent.ToolStarted -> {
                if (event.name.startsWith("health.")) markCurrentTurnCloudHealthSensitive()
                _state.value = _state.value.copy(stage = "正在执行 ${event.name}")
            }
            is AiEvent.ToolFinished -> _state.value = _state.value.copy(stage = if (event.success) "正在整理结果" else "工具执行未完成")
            is AiEvent.Surface -> _state.value = _state.value.copy(messages = _state.value.messages.toMutableList().also { list ->
                val last = list.lastOrNull() ?: return@also
                list[list.lastIndex] = last.copy(presentationJson = event.json)
            })
            is AiEvent.MemoryProposal -> Unit
        }
    }

    private fun markCurrentTurnCloudHealthSensitive() {
        val messages = _state.value.messages.toMutableList()
        val assistantIndex = messages.indexOfLast { it.role == "assistant" }
        if (assistantIndex < 0) return
        messages[assistantIndex] = messages[assistantIndex].copy(cloudHealthSensitive = true)
        val userIndex = (assistantIndex - 1 downTo 0).firstOrNull { messages[it].role == "user" }
        if (userIndex != null) messages[userIndex] = messages[userIndex].copy(cloudHealthSensitive = true)
        _state.value = _state.value.copy(messages = messages)
    }

    private suspend fun saveReport() {
        val final = _state.value
        val savingConversationId = conversationId
        val savingTitle = conversationTitle.ifBlank { "新的对话" }
        val savingCreatedAt = conversationCreatedAt
        val messagesToPersist = final.messages.filterNot {
            it.role == "assistant" && it.content.isBlank() && it.presentationJson == null
        }
        val summary = messagesToPersist.lastOrNull { it.role == "assistant" }?.content.orEmpty()
        if (summary.isBlank()) return
        val now = System.currentTimeMillis()
        val merged = AiReportWriteCoordinator.withLock {
            val persisted = dao.getAiReport(savingConversationId)
                ?.toDomain()
                ?.messagesJson
                ?.let(::parseMessages)
                .orEmpty()
            val mergedMessages = mergePersistedConversationMessages(
                current = messagesToPersist,
                persisted = persisted,
                replacedTail = pendingRegeneratedTail.orEmpty(),
            )
            val mergedSummary = mergedMessages.lastOrNull { it.role == "assistant" }?.content.orEmpty()
            if (mergedSummary.isBlank()) return@withLock messagesToPersist
            val report = AiReport(
                id = savingConversationId,
                provider = final.execution?.provider ?: final.resolvedProvider ?: final.provider,
                mode = final.mode,
                model = final.execution?.model ?: final.model,
                executionEngine = (final.execution?.engine ?: if (
                    (final.execution?.provider ?: final.resolvedProvider ?: final.provider) == AiProvider.LOCAL
                ) {
                    AiExecutionEngine.LOCAL_MNN
                } else {
                    AiExecutionEngine.CLOUD_OPENAI_COMPATIBLE
                }).name,
                requestId = final.execution?.requestId.orEmpty(),
                title = savingTitle,
                summary = mergedSummary.take(160),
                messagesJson = AiConversationCodec.encode(mergedMessages),
                createdAt = savingCreatedAt,
                updatedAt = now,
            )
            dao.insertAiReport(AiReportEntity.fromDomain(report))
            mergedMessages
        }
        pendingRegeneratedTail = null
        if (merged != messagesToPersist && conversationId == savingConversationId) {
            _state.value = _state.value.copy(messages = imageProcessor.hydrate(merged))
        }
    }

    private fun parseMessages(raw: String): List<AiConversationMessage> = AiConversationCodec.decode(raw)

    private fun clearPartialAssistant(messages: List<AiConversationMessage>): List<AiConversationMessage> =
        messages.toMutableList().also { copy ->
            val index = copy.indexOfLast { it.role == "assistant" }
            if (index >= 0) copy[index] = copy[index].copy(content = "", providerReasoningContent = null)
        }

    override fun onCleared() {
        detachMiFitnessWorkObserver()
        greetingJob?.cancel()
        recentlyDeletedCleanup?.cancel()
        generationEpoch.invalidate()
        pendingRegeneratedTail = null
        router.cancel()
        super.onCleared()
    }
}

private const val DELETED_CONVERSATION_UNDO_MILLIS = 8_000L

private fun String.toExecutionEngine(provider: AiProvider): AiExecutionEngine =
    runCatching { AiExecutionEngine.valueOf(this) }.getOrDefault(
        if (provider == AiProvider.LOCAL) AiExecutionEngine.LOCAL_MNN else AiExecutionEngine.CLOUD_OPENAI_COMPATIBLE,
    )

internal fun AiUiState.withOpenedConversation(
    report: AiReport,
    messages: List<AiConversationMessage>,
    selectedProvider: AiProvider,
    localModelId: String?,
): AiUiState = copy(
    mode = report.mode,
    provider = selectedProvider,
    resolvedProvider = report.provider,
    execution = ResolvedExecution(
        provider = report.provider,
        model = report.model,
        engine = report.executionEngine.toExecutionEngine(report.provider),
        requestId = report.requestId.ifBlank { "legacy:${report.id}" },
    ),
    model = report.model,
    messages = messages,
    pendingImages = emptyList(),
    importingImage = false,
    error = null,
    errorCode = null,
    canUseCloudOnce = false,
    cloudOnceProviders = emptyList(),
    pendingCloudPrompt = null,
    pendingCloudDisplayPrompt = null,
    pendingContextJson = null,
    pendingPresentationJson = null,
    pendingCloudHealthDisclosure = null,
    elapsedMs = 0,
    lockedLocalModelId = localModelId,
)

/** Preserve the provider/model that actually emitted this turn's Meta on a failed generation. */
internal fun AiUiState.withFailureExecutionFallback(
    previousExecution: ResolvedExecution?,
    previousModel: String,
    previousResolvedProvider: AiProvider?,
): AiUiState {
    val actualExecution = execution
    return copy(
        execution = actualExecution ?: previousExecution,
        model = actualExecution?.model ?: model.ifBlank { previousModel },
        resolvedProvider = actualExecution?.provider ?: resolvedProvider ?: previousResolvedProvider,
    )
}

internal fun canOfferCloudFallback(request: AiRequest, routing: AiRoutingException?): Boolean =
    routing?.canUseCloudOnce == true &&
        !request.requiresLocal &&
        request.imagePaths.isEmpty() &&
        request.messages.none { it.attachmentPaths.isNotEmpty() || it.attachmentRefs.isNotEmpty() }

private val OUTPUT_CLEARING_ERRORS = setOf(
    "provider_output_invalid",
    "local_vision_decode_failed",
    "local_vision_unavailable",
)

internal fun CaesarHealthUiState.afterMiFitnessNoDataCredentialSave(
    message: String,
): CaesarHealthUiState = copy(
    miFitnessConfigured = true,
    loading = false,
    miFitnessSyncing = false,
    miFitnessStatus = MiFitnessUiStatus.NO_DATA,
    miFitnessLastSyncAt = null,
    miFitnessFormResetKey = miFitnessFormResetKey + 1L,
    snapshot = null,
    healthError = null,
    actionMessage = message,
)

internal fun generationFailureMessage(error: Throwable): String =
    (error as? AiRoutingException)?.message ?: "生成中断，请重试。"

internal class AiGenerationEpoch {
    private val value = AtomicLong(0)

    fun begin(): Long = value.incrementAndGet()

    fun invalidate() {
        value.incrementAndGet()
    }

    fun isCurrent(token: Long): Boolean = value.get() == token
}

internal class AiGenerationCompletionTracker {
    private var sawDone = false
    private var sawFailure = false

    val completedNormally: Boolean get() = sawDone && !sawFailure

    fun accept(event: AiEvent) {
        when (event) {
            is AiEvent.Done -> sawDone = true
            is AiEvent.Error -> sawFailure = true
            else -> Unit
        }
    }

    fun fail() {
        sawFailure = true
    }
}

internal data class AiImageImportTicket(
    val epoch: Long,
    val conversationId: String,
)

internal class AiImageImportGate {
    private val epoch = AtomicLong(0L)

    fun begin(conversationId: String): AiImageImportTicket =
        AiImageImportTicket(epoch.incrementAndGet(), conversationId)

    fun invalidate() {
        epoch.incrementAndGet()
    }

    fun owns(ticket: AiImageImportTicket, currentConversationId: String): Boolean =
        ticket.epoch == epoch.get() && ticket.conversationId == currentConversationId
}

class AiViewModelFactory(
    private val dao: CampusDao,
    private val context: Context,
    private val preferences: UserPreferencesRepository,
    private val modelManager: LocalModelManager,
    private val localEngine: AiEngine,
    private val personalProviderStore: PersonalAiProviderStore,
    private val campusRepository: CampusRepository = CampusRepository(),
    private val profileRepository: ProfileRepository = ProfileRepository(),
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AiViewModel(
            dao,
            context,
            preferences,
            modelManager,
            localEngine,
            personalProviderStore,
            campusRepository,
            profileRepository,
        ) as T
}
