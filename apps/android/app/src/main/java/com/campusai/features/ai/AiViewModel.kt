package com.campusai.features.ai

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.campusai.core.ai.AiEngine
import com.campusai.core.ai.AiEngineRouter
import com.campusai.core.ai.AiEvent
import com.campusai.core.ai.AiExecutionEngine
import com.campusai.core.ai.AiRequest
import com.campusai.core.ai.AiRoutingException
import com.campusai.core.ai.CloudAiProvider
import com.campusai.core.ai.CloudHealthDisclosure
import com.campusai.core.ai.NetworkAvailability
import com.campusai.core.ai.PersonalCloudAiEngine
import com.campusai.core.ai.ResolvedExecution
import com.campusai.core.ai.aiModeForProvider
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
import com.campusai.core.health.HealthGateway
import com.campusai.core.health.HealthGatewayFactory
import com.campusai.core.model.AiConversationMessage
import com.campusai.core.model.AiMode
import com.campusai.core.model.AiProvider
import com.campusai.core.model.AiReport
import com.campusai.core.model.AiReportSummary
import com.campusai.core.model.DailyGreeting
import com.campusai.core.network.PersonalCloudClient
import com.campusai.core.network.CaesarCloudFreeEngine
import com.campusai.core.preferences.UserPreferencesRepository
import com.campusai.core.security.PersonalAiProviderStore
import com.campusai.core.profile.ProfileRepository
import com.campusai.features.community.CampusRepository
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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
    val runtimeState = state.map(AiUiState::runtimeState).distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiRuntimeState())
    private val healthController = AiHealthController(appContext, viewModelScope, healthGateway)
    val healthState = healthController.healthState
    val localModelSelection = modelManager.selection
    val localModelStates = modelManager.states
    fun localModelFor(modelId: String) = modelManager.runtimeFor(modelId).selection
    private val historyController = AiHistoryController(dao, viewModelScope) {
        _state.value = _state.value.copy(error = "历史记录读取失败，请重新打开。", errorCode = "history_load_failed")
    }
    val viewingLocalHistory = historyController.viewingLocal
    val history = historyController.history
    val visibleHistoryLimit = historyController.limit
    fun loadMoreHistory() = historyController.loadMore()
    fun showLocalHistory(local: Boolean) {
        historyController.selectOwner(if (local) "local_user" else activeOwnerUserId.ifBlank { "local_user" })
    }
    private val memoryQuery = MutableStateFlow("local_user" to 0L)
    val memoryScope = memoryQuery.asStateFlow()
    private val _memories = MutableStateFlow<List<CaesarMemoryUiItem>>(emptyList())
    val memories = _memories.asStateFlow()
    private val _viewingLocalMemories = MutableStateFlow(true)
    val viewingLocalMemories = _viewingLocalMemories.asStateFlow()

    fun showLocalMemories(local: Boolean) {
        val owner = if (local) "local_user" else activeOwnerUserId.ifBlank { "local_user" }
        _memories.value = emptyList()
        _viewingLocalMemories.value = owner == "local_user"
        memoryQuery.value = owner to (memoryQuery.value.second + 1)
    }
    private val _dailyGreeting = MutableStateFlow<DailyGreeting?>(null)
    val dailyGreeting: StateFlow<DailyGreeting?> = _dailyGreeting.asStateFlow()

    private var generation: Job? = null
    private var pendingRegeneratedTail: List<AiConversationMessage>? = null
    private val generationEpoch = AiGenerationEpoch()
    private val imageImportGate = AiImageImportGate()
    private var activeGenerationRollback: (() -> Unit)? = null
    private var greetingJob: Job? = null
    private var greetingAttemptedDate: String? = null
    private var greetingRevision = 0L
    private var conversationId = UUID.randomUUID().toString()
    private var conversationCreatedAt = System.currentTimeMillis()
    private var conversationTitle = ""
    private var conversationLocalModelId: String? = null
    private var recentlyDeleted: AiReport? = null
    private var recentlyDeletedCleanup: Job? = null
    private var activeOwnerUserId: String = ""
    private var conversationOwnerUserId: String = "local_user"
    private var openHistoryJob: Job? = null
    private var conversationRevision = 0L

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeMemories() = viewModelScope.launch {
        memoryQuery.flatMapLatest { query ->
            dao.getAgentMemoriesFlow(query.first).map { rows ->
                query to rows.filter { it.ownerUserId == query.first }.map { row ->
                    CaesarMemoryUiItem(row.id, row.type, row.content, row.confirmedAt != null, row.expiresAt)
                }
            }.catch {
                if (memoryQuery.value == query) _state.value = _state.value.copy(error = "记忆读取失败，请重新打开。", errorCode = "memory_load_failed")
            }
        }.collect { (query, rows) ->
            if (memoryQuery.value == query) _memories.value = rows
        }
    }

    init {
        observeMemories()
        viewModelScope.launch {
            preferences.preferences.collect { value ->
                provider.set(value.aiProvider)
                _state.value = _state.value.copy(
                    provider = value.aiProvider,
                    mode = aiModeForProvider(value.aiProvider, _state.value.mode),
                )
            }
        }
    }

    fun setMode(mode: AiMode) {
        if (!_state.value.streaming) _state.value = _state.value.copy(
            mode = aiModeForProvider(provider.get(), mode), error = null, errorCode = null,
        )
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
            mode = if (value == AiProvider.LOCAL) AiMode.FAST else aiModeForProvider(value, _state.value.mode),
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
        if (!_state.value.streaming) {
            val current = _state.value
            val clearHealthNotice = !value.healthSummary && current.errorCode == "health_summary_unavailable"
            _state.value = current.copy(contextSelection = value,
                error = if (clearHealthNotice) null else current.error,
                errorCode = if (clearHealthNotice) null else current.errorCode)
        }
    }

    fun refreshHealthStatus() = healthController.refreshHealthStatus()
    fun saveMiFitnessCredentials(userId: String, passToken: String) = healthController.saveMiFitnessCredentials(userId, passToken)
    fun refreshMiFitnessSteps() = healthController.refreshMiFitnessSteps()
    fun deleteMiFitnessCredentials() = healthController.deleteMiFitnessCredentials()

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
        if (_state.value.streaming || _state.value.importingImage) return
        if (actionId.startsWith("memory.confirm:")) {
            val id = actionId.removePrefix("memory.confirm:")
            val owner = activeOwnerUserId.ifBlank { "local_user" }
            mutateMemory("memory_confirmation_failed") { memoryStore.confirm(id, owner) }
            return
        }
        if (!actionId.startsWith("confirm:")) return
        invalidateHistoryLoad()
        cancelGreeting()
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
                caesarEngine.confirm(actionId).batchTextUpdates().collect { event ->
                    if (!generationEpoch.isCurrent(token)) return@collect
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
        val owner = memoryQuery.value.first
        mutateMemory("memory_confirmation_failed") { memoryStore.confirm(id, owner) }
    }

    fun updateMemory(id: String, content: String) {
        val owner = memoryQuery.value.first
        mutateMemory("memory_update_failed") { memoryStore.updateContent(id, content, owner) }
    }

    fun forgetMemory(id: String) {
        val owner = memoryQuery.value.first
        mutateMemory("memory_delete_failed") { memoryStore.forget(id, owner) }
    }

    fun forgetAllMemories() {
        val owner = memoryQuery.value.first
        mutateMemory("memory_delete_failed") { memoryStore.forgetAll(owner); true }
    }

    private fun mutateMemory(errorCode: String, action: suspend () -> Boolean) {
        val ownerAtStart = activeOwnerUserId
        val query = memoryQuery.value
        viewModelScope.launch {
            try {
                // A queued UI callback must not write after an account/scope change.
                if (activeOwnerUserId != ownerAtStart || memoryQuery.value != query) return@launch
                val success = action()
                if (activeOwnerUserId == ownerAtStart && memoryQuery.value == query) {
                    _state.value = _state.value.copy(
                        error = if (success) null else "这条记忆已过期、不存在或不属于当前范围。",
                        errorCode = if (success) null else errorCode,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (activeOwnerUserId == ownerAtStart && memoryQuery.value == query) {
                    _state.value = _state.value.copy(error = error.message ?: "记忆操作失败，请重试。", errorCode = errorCode)
                }
            }
        }
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

    fun setActiveOwner(userId: String) {
        val owner = userId.ifBlank { "local_user" }
        if (activeOwnerUserId.ifBlank { "local_user" } == owner) return
        if (_state.value.streaming) cancel()
        caesarEngine.cancel()
        cancelGreeting()
        activeOwnerUserId = userId
        expireDeletedConversation()
        _dailyGreeting.value = null
        greetingAttemptedDate = null
        newConversation()
        showLocalHistory(false)
        showLocalMemories(false)
    }

    fun newConversation() {
        if (_state.value.streaming) return
        invalidateHistoryLoad()
        caesarEngine.cancel()
        imageImportGate.invalidate()
        pendingRegeneratedTail = null
        val referenced = _state.value.messages.flatMap { it.attachmentRefs }.map { it.relativePath }.toSet()
        _state.value.pendingImages.forEach { imageProcessor.delete(it, referenced) }
        conversationId = UUID.randomUUID().toString()
        conversationOwnerUserId = activeOwnerUserId.ifBlank { "local_user" }
        conversationCreatedAt = System.currentTimeMillis()
        conversationTitle = ""
        conversationLocalModelId = null
        _state.value = AiUiState(
            mode = aiModeForProvider(provider.get(), _state.value.mode),
            provider = provider.get(),
            contextSelection = _state.value.contextSelection,
        )
    }

    fun send(prompt: String, snapshot: AiContextSnapshot, onAccepted: () -> Unit = {}) {
        setActiveOwner(snapshot.userId)
        if (prompt.isBlank() || _state.value.streaming || _state.value.importingImage || rejectUnavailableHealthSelection()) return
        prepareConversationForWrite {
            if (rejectUnavailableHealthSelection()) return@prepareConversationForWrite
            val reply = CaesarDeterministicReply.forPrompt(prompt, hasImages = _state.value.pendingImages.isNotEmpty(), provider = provider.get())
            if (reply != null) {
                _state.value = _state.value.copy(
                    contextSelection = _state.value.contextSelection.copy(healthSummary = false),
                )
                appendDeterministicReply(prompt, reply)
                onAccepted()
            } else {
                val base = CampusAiTaskFactory.create(CampusAiTask.CHAT, snapshot.records, snapshot.courses, prompt)
                sendInternal(base.withContext(CampusAiTask.CHAT, prompt, snapshot), cloudOnce = false, onAccepted = onAccepted)
            }
        }
    }

    fun regenerateLastResponse(snapshot: AiContextSnapshot) {
        setActiveOwner(snapshot.userId)
        if (planAiRegeneration(_state.value.messages) == null) return
        if (_state.value.streaming || _state.value.importingImage || rejectUnavailableHealthSelection()) return
        prepareConversationForWrite {
            if (rejectUnavailableHealthSelection()) return@prepareConversationForWrite
            val originalMessages = _state.value.messages
            val plan = planAiRegeneration(originalMessages) ?: return@prepareConversationForWrite
            pendingRegeneratedTail = plan.replacedTail
            _state.value = _state.value.copy(messages = plan.history, error = null, errorCode = null)
            val base = CampusAiTaskFactory.create(CampusAiTask.CHAT, snapshot.records, snapshot.courses, plan.prompt.content)
            sendInternal(
                payload = base.withContext(CampusAiTask.CHAT, plan.prompt.content, snapshot),
                cloudOnce = false,
                failureMessagesOverride = originalMessages,
            )
        }
    }

    private fun appendDeterministicReply(prompt: String, reply: String) {
        val displayPrompt = prompt.trim()
        if (displayPrompt.isBlank() || _state.value.streaming) return
        invalidateHistoryLoad()
        cancelGreeting()
        caesarEngine.cancel()
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
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { saveReport() }
    }

    fun sendTask(task: CampusAiTask, snapshot: AiContextSnapshot, userInput: String = "", onAccepted: () -> Unit = {}) {
        setActiveOwner(snapshot.userId)
        if (_state.value.streaming || _state.value.importingImage || rejectUnavailableHealthSelection()) return
        prepareConversationForWrite {
            val base = CampusAiTaskFactory.create(task, snapshot.records, snapshot.courses, userInput)
            sendInternal(base.withContext(task, userInput, snapshot), cloudOnce = false, onAccepted = onAccepted)
        }
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

    private fun invalidateHistoryLoad() {
        conversationRevision++
        openHistoryJob?.cancel()
        openHistoryJob = null
    }

    private fun cancelGreeting() {
        greetingRevision++
        greetingJob?.cancel()
        greetingJob = null
    }

    /** Local legacy history remains intact. Continuing it creates an account-owned copy. */
    private fun prepareConversationForWrite(onReady: () -> Unit) {
        if (_state.value.streaming || _state.value.importingImage) return
        invalidateHistoryLoad()
        val owner = activeOwnerUserId.ifBlank { "local_user" }
        if (conversationOwnerUserId == owner) {
            onReady()
            return
        }
        if (conversationOwnerUserId != "local_user") return
        caesarEngine.cancel()
        val revision = conversationRevision
        val sourceId = conversationId
        val sourceState = _state.value
        val targetId = UUID.randomUUID().toString()
        _state.value = sourceState.copy(importingImage = true, error = null, errorCode = null)
        openHistoryJob = viewModelScope.launch {
            var saved = false
            try {
                val (messages, pendingImages) = imageProcessor.copyForConversation(targetId, sourceState.messages, sourceState.pendingImages)
                if (conversationRevision != revision || activeOwnerUserId.ifBlank { "local_user" } != owner) return@launch
                val now = System.currentTimeMillis()
                AiReportWriteCoordinator.withLock {
                    val source = dao.getAiReport(sourceId, "local_user")?.toDomain()
                        ?: error("原对话已不存在，请新建对话。")
                    if (conversationRevision != revision || activeOwnerUserId.ifBlank { "local_user" } != owner) return@withLock
                    // Finish the write and saved flag together even if a new owner cancels this job.
                    withContext(NonCancellable) {
                        dao.insertAiReport(AiReportEntity.fromDomain(source.copy(
                            id = targetId,
                            ownerUserId = owner,
                            messagesJson = AiConversationCodec.encode(messages),
                            createdAt = now,
                            updatedAt = now,
                        )))
                        saved = true
                    }
                }
                if (!saved || conversationRevision != revision || activeOwnerUserId.ifBlank { "local_user" } != owner) return@launch
                conversationId = targetId
                conversationOwnerUserId = owner
                conversationCreatedAt = now
                _state.value = sourceState.copy(messages = messages, pendingImages = pendingImages, importingImage = false)
                val originalRefs = sourceState.messages.flatMap { it.attachmentRefs }.map { it.relativePath }.toSet()
                sourceState.pendingImages.forEach { imageProcessor.delete(it, originalRefs) }
                openHistoryJob = null
                onReady()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (conversationRevision == revision) {
                    _state.value = _state.value.copy(importingImage = false, error = error.message ?: "无法继续这条旧对话，请重试。", errorCode = "history_fork_failed")
                }
            } finally {
                if (!saved) withContext(NonCancellable + Dispatchers.IO) { imageProcessor.deleteConversation(targetId) }
                if (conversationRevision == revision) openHistoryJob = null
            }
        }
    }

    fun openConversation(report: AiReportSummary) = openConversationById(report.id, report.ownerUserId)

    fun openConversationById(id: String, owner: String) {
        if (_state.value.streaming || owner !in setOf("local_user", activeOwnerUserId.ifBlank { "local_user" })) return
        invalidateHistoryLoad()
        caesarEngine.cancel()
        imageImportGate.invalidate()
        _state.value = _state.value.copy(importingImage = false)
        val activeOwner = activeOwnerUserId
        val revision = conversationRevision
        openHistoryJob = viewModelScope.launch {
            try {
                val report = dao.getAiReport(id, owner)?.toDomain() ?: error("这条对话已不存在。")
                if (activeOwnerUserId == activeOwner && conversationRevision == revision && !_state.value.streaming) {
                    openHistoryJob = null
                    openConversation(report)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (activeOwnerUserId == activeOwner && conversationRevision == revision) {
                    _state.value = _state.value.copy(error = error.message ?: "对话读取失败，请重试。", errorCode = "history_open_failed")
                }
            }
        }
    }

    private fun openConversation(report: AiReport) {
        if (_state.value.streaming) return
        caesarEngine.cancel()
        val revision = ++conversationRevision
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
        conversationOwnerUserId = report.ownerUserId
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
                    val latest = dao.getAiReport(report.id, report.ownerUserId)?.toDomain() ?: return@withLock
                    val latestDecoded = parseMessages(latest.messagesJson)
                    val migrated = imageProcessor.migrateLegacy(latest.id, latestDecoded)
                    migratedForUi = migrated
                    if (migrated != imageProcessor.hydrate(latestDecoded)) {
                        dao.insertAiReport(AiReportEntity.fromDomain(latest.copy(messagesJson = AiConversationCodec.encode(migrated))))
                    }
                }
            }.exceptionOrNull()
            withContext(Dispatchers.Main) {
                if (conversationId == report.id && conversationRevision == revision && !_state.value.streaming) {
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

    suspend fun deleteConversation(summary: AiReportSummary): Boolean {
        if (_state.value.streaming || summary.ownerUserId !in setOf("local_user", activeOwnerUserId.ifBlank { "local_user" })) return false
        val ownerAtStart = activeOwnerUserId
        // A completed deletion must always register undo/cleanup even if its sheet is dismissed.
        return withContext(NonCancellable) {
            try {
                val report = AiReportWriteCoordinator.withLock {
                    val full = dao.getAiReport(summary.id, summary.ownerUserId)?.toDomain()
                        ?: error("这条对话已不存在。")
                    if (activeOwnerUserId != ownerAtStart || _state.value.streaming) return@withLock null
                    dao.deleteAiReport(full.id, full.ownerUserId)
                    full
                } ?: return@withContext false
                if (activeOwnerUserId != ownerAtStart) {
                    cleanupDeletedImages(report)
                    return@withContext false
                }
                expireDeletedConversation()
                recentlyDeleted = report
                if (report.id == conversationId) newConversation()
                recentlyDeletedCleanup = viewModelScope.launch {
                    delay(DELETED_CONVERSATION_UNDO_MILLIS)
                    if (recentlyDeleted?.id == report.id) expireDeletedConversation()
                }
                true
            } catch (error: Throwable) {
                if (activeOwnerUserId == ownerAtStart) {
                    _state.value = _state.value.copy(error = "删除失败，请重试。", errorCode = "history_delete_failed")
                }
                false
            }
        }
    }

    private fun expireDeletedConversation() {
        val report = recentlyDeleted
        recentlyDeletedCleanup?.cancel()
        recentlyDeletedCleanup = null
        recentlyDeleted = null
        if (report != null) cleanupDeletedImages(report)
    }

    private fun cleanupDeletedImages(report: AiReport) {
        viewModelScope.launch(Dispatchers.IO) {
            AiReportWriteCoordinator.withLock {
                if (dao.getAiReport(report.id, report.ownerUserId) == null) imageProcessor.deleteConversation(report.id)
            }
        }
    }

    suspend fun undoDeleteConversation(id: String): Boolean {
        val report = recentlyDeleted?.takeIf { it.id == id } ?: return false
        if (report.ownerUserId !in setOf("local_user", activeOwnerUserId.ifBlank { "local_user" })) return false
        val ownerAtStart = activeOwnerUserId
        recentlyDeletedCleanup?.cancel()
        recentlyDeletedCleanup = null
        return withContext(NonCancellable) {
            try {
                val restored = AiReportWriteCoordinator.withLock {
                    if (recentlyDeleted !== report || activeOwnerUserId != ownerAtStart) return@withLock false
                    val current = dao.getAiReport(report.id, report.ownerUserId)?.toDomain()
                    val restored = if (current == null) report else {
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
                    true
                }
                if (recentlyDeleted === report) recentlyDeleted = null
                restored
            } catch (error: Throwable) {
                if (activeOwnerUserId == ownerAtStart) {
                    _state.value = _state.value.copy(error = "恢复失败，请重试。", errorCode = "history_restore_failed")
                }
                // Keep the recoverable report and its files after a failed undo.
                false
            }
        }
    }

    fun ensureDailyGreeting(snapshot: AiContextSnapshot) {
        val owner = snapshot.userId.ifBlank { "local_user" }
        if (owner != activeOwnerUserId.ifBlank { "local_user" }) return
        val zone = com.campusai.core.time.CaesarTime.zone
        val date = LocalDate.now(zone)
        val cacheId = "${snapshot.userId.ifBlank { "local" }}:$date"
        val current = _dailyGreeting.value
        if (current?.localDate == date.toString() && DailyGreetingPolicy.isGrounded(current.text, snapshot, zone = zone)) return
        if (greetingAttemptedDate == cacheId) return
        if (greetingJob?.isActive == true) return
        greetingAttemptedDate = cacheId
        val revision = ++greetingRevision
        fun ownsGreeting() = greetingRevision == revision &&
            owner == activeOwnerUserId.ifBlank { "local_user" } && LocalDate.now(zone) == date
        greetingJob = viewModelScope.launch {
            val fallback = DailyGreetingPolicy.fallback(snapshot.displayName, date)
            try {
                dao.getDailyGreeting(cacheId)?.toDomain()?.let { cached ->
                    val isOldFallback = cached.text == fallback
                    if (!isOldFallback && DailyGreetingPolicy.isGrounded(cached.text, snapshot, zone = zone)) {
                        if (ownsGreeting()) _dailyGreeting.value = cached
                        return@launch
                    }
                }
                if (!ownsGreeting()) return@launch
                _dailyGreeting.value = DailyGreeting(date.toString(), fallback, AiProvider.LOCAL, System.currentTimeMillis())
                if (_state.value.streaming || provider.get() == AiProvider.CAESAR_CLOUD_FREE) return@launch
                val context = AiContextAssembler.greetingContext(snapshot)
                val request = AiRequest(
                    mode = AiMode.FAST,
                    ownerUserId = owner,
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
                router.stream(request).collect { event ->
                    if (!ownsGreeting()) throw CancellationException("Greeting owner/date changed")
                    when (event) {
                        is AiEvent.Meta -> resolved = event.provider
                        is AiEvent.Delta -> answer.append(event.text)
                        is AiEvent.Error -> failed = true
                        else -> Unit
                    }
                }
                if (!ownsGreeting()) return@launch
                val candidate = if (failed) "" else sanitizeGreeting(answer.toString())
                val text = candidate.takeIf { it.isNotBlank() && DailyGreetingPolicy.isGrounded(it, snapshot, zone = zone) } ?: fallback
                if (text != fallback) {
                    val greeting = persistGreeting(cacheId, date, text, resolved)
                    if (ownsGreeting()) _dailyGreeting.value = greeting
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (ownsGreeting()) _dailyGreeting.value = DailyGreeting(date.toString(), fallback, AiProvider.LOCAL, System.currentTimeMillis())
            } finally {
                if (greetingRevision == revision) greetingJob = null
            }
        }
    }

    private suspend fun persistGreeting(id: String, date: LocalDate, text: String, source: AiProvider): DailyGreeting {
        val generatedAt = System.currentTimeMillis()
        dao.insertDailyGreeting(DailyGreetingEntity(id, date.toString(), text, source.name, generatedAt))
        return DailyGreeting(date.toString(), text, source, generatedAt)
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
        onAccepted: () -> Unit = {},
    ) {
        val prompt = payload.prompt
        val displayPrompt = payload.displayPrompt.ifBlank { prompt }
        if (prompt.isBlank() || _state.value.streaming || _state.value.importingImage) return
        if (rejectUnavailableHealthSelection(cloudHealthDisclosureOverride)) {
            if (failureMessagesOverride != null) _state.value = _state.value.copy(messages = failureMessagesOverride)
            pendingRegeneratedTail = null
            return
        }
        invalidateHistoryLoad()
        cancelGreeting()
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
        val modeForTurn = aiModeForProvider(selectedProviderForTurn, _state.value.mode)
        val capabilities = com.campusai.core.ai.aiCapabilities(selectedProviderForTurn, attachments.isNotEmpty())
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
            mode = modeForTurn,
            messages = initial,
            streaming = true,
            stage = if (selectedProviderForTurn == AiProvider.CAESAR_CLOUD_FREE) "正在生成回复" else "思考中",
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
        ).withCloudHealthDisclosure(cloudHealthDisclosure).copy(
            allowWebSearch = capabilities.webSearch,
            allowedToolNames = capabilities.allowedToolNames,
            allowLocalMemoryRead = capabilities.localMemoryRead,
        )
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
        onAccepted()
        launchGeneration { token ->
            val completion = AiGenerationCompletionTracker()
            val localMemories = if (cloudOnce || !request.requiresLocal) emptyList() else {
                memoryStore.context(ownerUserId = request.ownerUserId.ifBlank { "local_user" })
            }
            if (!generationEpoch.isCurrent(token)) return@launchGeneration
            if (localMemories.isNotEmpty()) markCurrentTurnCloudHealthSensitive()
            val requestWithMemory = if (localMemories.isEmpty()) request else request.copy(
                structuredContextJson = JSONObject(request.structuredContextJson)
                    .put("confirmedMemories", JSONArray(localMemories.map { memory ->
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
                events.batchTextUpdates().collect { event ->
                    if (!generationEpoch.isCurrent(token)) return@collect
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

    /** An explicitly selected attachment must exist before any prompt or other context leaves the device. */
    private fun rejectUnavailableHealthSelection(disclosureOverride: CloudHealthDisclosure? = null): Boolean {
        if (!_state.value.contextSelection.healthSummary && disclosureOverride !is CloudHealthDisclosure.Included) return false
        val disclosure = disclosureOverride ?: currentCloudHealthDisclosure()
        if (disclosure is CloudHealthDisclosure.Included && disclosure.summary.hasShareableMetrics) return false
        _state.value = _state.value.copy(
            error = "今天暂无可用于总结的健康指标，缺失值仍为未知。本次未发送问题或健康摘要，草稿已保留；可在“本次对话依据”关闭健康摘要后继续提问，或同步有数据后重试。",
            errorCode = "health_summary_unavailable",
            canUseCloudOnce = false,
            cloudOnceProviders = emptyList(),
            pendingCloudHealthDisclosure = null,
        )
        return true
    }

    private fun currentCloudHealthDisclosure() = currentCloudHealthDisclosure(
        healthState.value.snapshot, _state.value.contextSelection.healthSummary,
    )

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
                if (event.name.startsWith("health.") || event.name == "memory.list") markCurrentTurnCloudHealthSensitive()
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
        val savingOwner = conversationOwnerUserId
        val savingRevision = conversationRevision
        val replacedTail = pendingRegeneratedTail.orEmpty()
        val savingTitle = conversationTitle.ifBlank { "新的对话" }
        val savingCreatedAt = conversationCreatedAt
        val messagesToPersist = final.messages.filterNot {
            it.role == "assistant" && it.content.isBlank() && it.presentationJson == null
        }
        val summary = messagesToPersist.lastOrNull { it.role == "assistant" }?.content.orEmpty()
        if (summary.isBlank()) return
        val now = System.currentTimeMillis()
        val merged = AiReportWriteCoordinator.withLock {
            val persisted = dao.getAiReport(savingConversationId, savingOwner)
                ?.toDomain()
                ?.messagesJson
                ?.let(::parseMessages)
                .orEmpty()
            val mergedMessages = mergePersistedConversationMessages(
                current = messagesToPersist,
                persisted = persisted,
                replacedTail = replacedTail,
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
                ownerUserId = savingOwner,
            )
            dao.insertAiReport(AiReportEntity.fromDomain(report))
            mergedMessages
        }
        if (conversationRevision == savingRevision && conversationId == savingConversationId && conversationOwnerUserId == savingOwner) {
            pendingRegeneratedTail = null
            if (merged != messagesToPersist) _state.value = _state.value.copy(messages = imageProcessor.hydrate(merged))
        }
    }

    private fun parseMessages(raw: String): List<AiConversationMessage> = AiConversationCodec.decode(raw)

    private fun clearPartialAssistant(messages: List<AiConversationMessage>): List<AiConversationMessage> =
        messages.toMutableList().also { copy ->
            val index = copy.indexOfLast { it.role == "assistant" }
            if (index >= 0) copy[index] = copy[index].copy(content = "", providerReasoningContent = null)
        }

    override fun onCleared() {
        healthController.close()
        greetingJob?.cancel()
        recentlyDeletedCleanup?.cancel()
        generationEpoch.invalidate()
        pendingRegeneratedTail = null
        caesarEngine.cancel()
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
    mode = aiModeForProvider(selectedProvider, report.mode),
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
