package com.campusai.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.content.ContextCompat
import com.campusai.core.database.CampusDao
import com.campusai.core.automation.ForegroundHealthTaskRunResult
import com.campusai.core.automation.ForegroundHealthTaskRuntime
import com.campusai.core.automation.HealthTaskDefaults
import com.campusai.core.automation.ScheduledTaskConfig
import com.campusai.core.automation.healthTaskNotificationsEnabled
import com.campusai.core.automation.mergeDisabledHealthTaskConfig
import com.campusai.core.automation.mergeEnabledHealthTaskConfig
import com.campusai.core.automation.mutateWithRetry
import com.campusai.core.auth.AuthRepository
import com.campusai.core.designsystem.CampusTheme
import com.campusai.core.designsystem.DefaultSpectraTokens
import com.campusai.core.designsystem.GlassPanel
import com.campusai.core.designsystem.OpticalGlassRegistry
import com.campusai.core.designsystem.ProvideSpectraExperience
import com.campusai.core.designsystem.ProvideSpectraTokens
import com.campusai.core.designsystem.SpectraBackdrop
import com.campusai.core.designsystem.SpectraColors
import com.campusai.core.designsystem.SpectraPhase
import com.campusai.core.designsystem.SpectraTheme
import com.campusai.core.designsystem.SpectraVisualStyle
import com.campusai.core.designsystem.SpectraVisualStyleController
import com.campusai.core.designsystem.spectraTokensForStyle
import com.campusai.core.model.MotionMode
import com.campusai.core.model.UiState
import com.campusai.core.preferences.UserPreferences
import com.campusai.core.preferences.UserPreferencesRepository
import com.campusai.features.time.TimeViewModel
import com.campusai.features.time.TimeViewModelFactory
import com.campusai.features.ai.AiScreen
import com.campusai.features.ai.AiViewModel
import com.campusai.features.ai.AiViewModelFactory
import com.campusai.features.ai.AiContextSnapshot
import com.campusai.features.ai.AiPostContext
import com.campusai.features.ai.MiFitnessUiStatus
import com.campusai.features.community.CampusViewModel
import com.campusai.features.community.CampusRepository
import com.campusai.core.sync.CampusSyncScheduler
import com.campusai.core.localai.LocalMnnAiEngine
import com.campusai.core.localai.LocalModelManager
import com.campusai.core.agent.MnnAgentEngineFactory
import com.campusai.core.network.PersonalCloudClient
import com.campusai.core.ai.CloudAiProvider
import com.campusai.core.security.PersonalAiProviderStore
import com.campusai.core.profile.ProfileRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import android.net.Uri

enum class MainDestination(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Rounded.Home),
    TIME("时间", Icons.Rounded.Schedule),
    CAMPUS("树洞", Icons.Rounded.Forum),
    MARKET("心愿墙", Icons.Rounded.FavoriteBorder),
    PROFILE("我的", Icons.Rounded.Person),
}

/** One mutually-exclusive app surface; full-screen tasks never coexist with the main scaffold. */
internal sealed interface AppSurface {
    val returnDestination: MainDestination
    val opticalRouteKey: String

    data class Main(val destination: MainDestination) : AppSurface {
        override val returnDestination: MainDestination = destination
        override val opticalRouteKey: String = "main:${destination.name}"
    }

    data class Ai(override val returnDestination: MainDestination) : AppSurface {
        override val opticalRouteKey: String = "fullscreen:ai"
    }

    data class Messages(override val returnDestination: MainDestination) : AppSurface {
        override val opticalRouteKey: String = "fullscreen:messages"
    }

    data class Focus(
        val presetMinutes: Int,
        override val returnDestination: MainDestination = MainDestination.TIME,
    ) : AppSurface {
        override val opticalRouteKey: String = "fullscreen:focus"
    }

    data class Login(override val returnDestination: MainDestination) : AppSurface {
        override val opticalRouteKey: String = "fullscreen:login"
    }
}

internal val AppSurfaceSaver = Saver<AppSurface, String>(
    save = { surface -> encodeAppSurface(surface) },
    restore = { encoded -> decodeAppSurface(encoded) },
)

internal fun encodeAppSurface(surface: AppSurface): String = when (surface) {
    is AppSurface.Main -> "main|${surface.destination.name}"
    is AppSurface.Ai -> "ai|${surface.returnDestination.name}"
    is AppSurface.Messages -> "messages|${surface.returnDestination.name}"
    is AppSurface.Focus -> "focus|${surface.presetMinutes}|${surface.returnDestination.name}"
    is AppSurface.Login -> "login|${surface.returnDestination.name}"
}

internal fun decodeAppSurface(encoded: String): AppSurface? {
    val parts = encoded.split('|')
    fun destinationAt(index: Int): MainDestination? = parts.getOrNull(index)
        ?.let { value -> MainDestination.entries.firstOrNull { it.name == value } }
    return when (parts.firstOrNull()) {
        "main" -> destinationAt(1)?.let(AppSurface::Main)
        "ai" -> destinationAt(1)?.let(AppSurface::Ai)
        "messages" -> destinationAt(1)?.let(AppSurface::Messages)
        "focus" -> parts.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }?.let { minutes ->
            AppSurface.Focus(minutes, destinationAt(2) ?: MainDestination.TIME)
        }
        "login" -> destinationAt(1)?.let(AppSurface::Login)
        else -> null
    }
}

internal enum class ExternalImageCompletionDisposition { NONE, ACK_STALE, CONSUME_AND_ACK }

internal fun externalImageCompletionDisposition(
    currentSharedUri: String?,
    completedUri: String?,
): ExternalImageCompletionDisposition = when {
    completedUri == null -> ExternalImageCompletionDisposition.NONE
    currentSharedUri == completedUri -> ExternalImageCompletionDisposition.CONSUME_AND_ACK
    else -> ExternalImageCompletionDisposition.ACK_STALE
}

@Composable
fun CampusApp(
    dao: CampusDao,
    initialSharedImage: Uri? = null,
    onSharedImageConsumed: () -> Unit = {},
    initialAutomationConversationId: String? = null,
    onAutomationConversationConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferencesRepository = remember { UserPreferencesRepository(context.applicationContext) }
    val localModelManager = remember { LocalModelManager(context.applicationContext) }
    val localAiEngine = remember { LocalMnnAiEngine(context.applicationContext, localModelManager) }
    val agentLocalAiEngine = remember {
        MnnAgentEngineFactory.create(localAiEngine, localModelManager::manifestFor)
    }
    val personalAiProviderStore = remember { PersonalAiProviderStore(context.applicationContext) }
    val foregroundHealthRuntime = remember { ForegroundHealthTaskRuntime.get(context.applicationContext, dao) }
    val profileRepository = remember { ProfileRepository() }
    val campusRepository = remember { CampusRepository() }
    val profileState by profileRepository.state.collectAsState()
    DisposableEffect(localModelManager, localAiEngine) {
        onDispose {
            localAiEngine.shutdown()
            localModelManager.close()
        }
    }
    val authRepository = remember { AuthRepository(context.applicationContext) }
    val authState by authRepository.state.collectAsState()
    val loadedPreferences by preferencesRepository.preferences.collectAsState(initial = null)
    val preferences = loadedPreferences ?: UserPreferences()
    val timeViewModel: TimeViewModel = viewModel(factory = TimeViewModelFactory(dao, context.applicationContext, authState.userId.takeIf { authState.signedIn }))
    val aiViewModel: AiViewModel = viewModel(
        factory = AiViewModelFactory(
            dao,
            context.applicationContext,
            preferencesRepository,
            localModelManager,
            agentLocalAiEngine,
            personalAiProviderStore,
            campusRepository,
            profileRepository,
        ),
    )
    val aiRuntimeState by aiViewModel.state.collectAsState()
    val healthState by aiViewModel.healthState.collectAsState()
    val campusViewModel: CampusViewModel = viewModel()
    val campusState by campusViewModel.state.collectAsState()
    val announcementState by campusViewModel.announcements.collectAsState()
    val records by timeViewModel.timeRecords.collectAsState()
    val courses by timeViewModel.courses.collectAsState()
    val dailyTargetSnapshots by timeViewModel.dailyTargetSnapshots.collectAsState()
    val dailyGreeting by aiViewModel.dailyGreeting.collectAsState()
    val aiHistory by aiViewModel.history.collectAsState()
    val contextPosts = when (val posts = campusState.posts) {
        is UiState.Data -> posts.value
        is UiState.Offline -> posts.value
        else -> emptyList()
    }
    val aiSnapshot = AiContextSnapshot(
        userId = authState.userId,
        displayName = profileState.profile.displayName,
        records = records,
        courses = courses,
        visiblePosts = contextPosts.map { post ->
            AiPostContext(post.id, post.authorId, post.body, post.topic, post.likes, post.comments, post.createdAt)
        },
    )
    var appSurface by rememberSaveable(stateSaver = AppSurfaceSaver) {
        mutableStateOf<AppSurface>(AppSurface.Main(MainDestination.HOME))
    }
    // Set the route before any glass node can attach. Navigation below repeats this synchronously
    // before changing Compose state, making a route transition an atomic registry boundary.
    remember { OpticalGlassRegistry.beginRouteHost(appSurface.opticalRouteKey) }
    fun navigateTo(surface: AppSurface) {
        OpticalGlassRegistry.switchRouteScope(surface.opticalRouteKey)
        appSurface = surface
    }
    val snackbar = remember { SnackbarHostState() }
    val appScope = rememberCoroutineScope()
    var healthAutomationConfig by remember { mutableStateOf<ScheduledTaskConfig?>(null) }
    var healthAutomationSaving by remember { mutableStateOf(false) }
    var healthAutomationMessage by remember { mutableStateOf<String?>(null) }
    var healthAutomationMessageIsError by remember { mutableStateOf(false) }
    var healthAutomationNotificationsEnabled by remember {
        mutableStateOf(healthTaskNotificationsEnabled(context.applicationContext))
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        healthAutomationNotificationsEnabled = healthTaskNotificationsEnabled(context.applicationContext)
    }
    val unreadMessages = when (val conversations = campusState.conversations) {
        is UiState.Data -> conversations.value.sumOf { it.unreadCount }
        is UiState.Offline -> conversations.value.sumOf { it.unreadCount }
        else -> 0
    }
    LaunchedEffect(
        initialSharedImage,
        aiRuntimeState.streaming,
        aiRuntimeState.importingImage,
        aiRuntimeState.pendingImages.size,
        aiRuntimeState.completedExternalImageImport,
    ) {
        val shared = initialSharedImage ?: return@LaunchedEffect
        if (aiRuntimeState.completedExternalImageImport == shared.toString()) return@LaunchedEffect
        if (aiViewModel.attachImage(shared, externalShare = true)) {
            navigateTo(AppSurface.Ai(appSurface.returnDestination))
        }
    }

    LaunchedEffect(initialSharedImage, aiRuntimeState.completedExternalImageImport) {
        val completed = aiRuntimeState.completedExternalImageImport ?: return@LaunchedEffect
        if (externalImageCompletionDisposition(initialSharedImage?.toString(), completed) ==
            ExternalImageCompletionDisposition.CONSUME_AND_ACK
        ) {
            onSharedImageConsumed()
        }
        // A completion superseded by a different/non-share Intent is stale. Ack it as well so
        // sharing the same content URI in a future Intent starts a fresh import.
        aiViewModel.acknowledgeExternalImageImport(completed)
    }

    LaunchedEffect(initialAutomationConversationId, aiHistory, aiRuntimeState.streaming) {
        val conversationId = initialAutomationConversationId ?: return@LaunchedEffect
        if (aiRuntimeState.streaming) return@LaunchedEffect
        val report = aiHistory.firstOrNull { it.id == conversationId } ?: return@LaunchedEffect
        aiViewModel.openConversation(report)
        navigateTo(AppSurface.Ai(appSurface.returnDestination))
        onAutomationConversationConsumed()
    }

    LaunchedEffect(foregroundHealthRuntime, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                healthAutomationNotificationsEnabled = healthTaskNotificationsEnabled(context.applicationContext)
                try {
                    val config = foregroundHealthRuntime.store.read(HealthTaskDefaults.TASK_ID)
                    healthAutomationConfig = config
                    if (config?.enabled == true) {
                        when (foregroundHealthRuntime.runner.run(HealthTaskDefaults.TASK_ID)) {
                            is ForegroundHealthTaskRunResult.Updated,
                            is ForegroundHealthTaskRunResult.Unchanged -> aiViewModel.refreshHealthStatus()
                            else -> Unit
                        }
                        healthAutomationConfig = foregroundHealthRuntime.store.read(HealthTaskDefaults.TASK_ID)
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Preserve the last known value. Corruption is not an empty task
                    // list and must not be silently replaced by a later settings save.
                    healthAutomationMessage = "定时任务配置无法读取，请保留文件并重试。"
                    healthAutomationMessageIsError = true
                }
                delay(FOREGROUND_TASK_TICK_MILLIS)
            }
        }
    }

    BackHandler(enabled = appSurface !is AppSurface.Main && appSurface !is AppSurface.Ai) {
        when (val current = appSurface) {
            is AppSurface.Login -> {
                authRepository.clearError()
                navigateTo(AppSurface.Main(current.returnDestination))
            }
            is AppSurface.Ai -> navigateTo(AppSurface.Main(current.returnDestination))
            is AppSurface.Messages -> {
                if (campusState.activeConversationId != null) campusViewModel.closeMessageThread()
                else navigateTo(AppSurface.Main(current.returnDestination))
            }
            is AppSurface.Focus -> navigateTo(AppSurface.Main(current.returnDestination))
            is AppSurface.Main -> Unit
        }
    }

    LaunchedEffect(authState.signedIn) {
        if (authState.signedIn) {
            while (true) {
                delay(45 * 60 * 1_000L)
                if (authRepository.refresh()) {
                    val refreshed = authRepository.state.value
                    profileRepository.load(
                        userId = refreshed.userId,
                        fallbackName = refreshed.email.substringBefore('@').ifBlank { "Caesar 用户" },
                    )
                }
            }
        }
    }

    LaunchedEffect(authState.signedIn, authState.userId) {
        campusViewModel.setSession(authState.signedIn, authState.userId)
    }
    LaunchedEffect(authState.signedIn, authState.userId) {
        if (authState.signedIn) authRepository.refresh()
        val activeAuth = authRepository.state.value
        timeViewModel.setActiveUser(activeAuth.userId.takeIf { activeAuth.signedIn })
        profileRepository.load(
            userId = activeAuth.userId.takeIf { activeAuth.signedIn }.orEmpty(),
            fallbackName = activeAuth.email.substringBefore('@').ifBlank { "Caesar 用户" },
        )
        if (activeAuth.signedIn) CampusSyncScheduler.enqueue(context.applicationContext)
    }
    LaunchedEffect(aiSnapshot, authState.userId) { aiViewModel.ensureDailyGreeting(aiSnapshot) }
    LaunchedEffect(Unit) { aiViewModel.refreshHealthStatus() }
    SideEffect {
        SpectraVisualStyleController.set(preferences.visualStyle)
    }

    CampusTheme(preferences.themeMode, preferences.environment) {
        val styledTokens = spectraTokensForStyle(DefaultSpectraTokens, preferences.visualStyle)
        ProvideSpectraExperience(preferences.visualStyle) {
        ProvideSpectraTokens(
            styledTokens.copy(
                glassEffects = preferences.glassEffects,
                motion = if (preferences.motionMode == MotionMode.ON) styledTokens.motion else styledTokens.motion.disabled(),
            ),
        ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            SpectraBackdrop(
                environment = preferences.environment,
                quality = preferences.renderQuality,
                motion = preferences.motionMode,
                active = appSurface !is AppSurface.Login,
                phase = when (appSurface) {
                    is AppSurface.Focus -> SpectraPhase.FOCUS
                    is AppSurface.Ai if aiRuntimeState.streaming -> SpectraPhase.THINKING
                    else -> SpectraPhase.AMBIENT
                },
            )
            when (val surface = appSurface) {
                is AppSurface.Main -> {
                    Scaffold(
                        containerColor = Color.Transparent,
                        snackbarHost = { SnackbarHost(snackbar) },
                        bottomBar = {
                            // The dock survives destination changes inside the main Scaffold. Key it
                            // to the optical route so its Modifier nodes detach before the registry
                            // advances, then attach with the new authorized generation.
                            androidx.compose.runtime.key(surface.destination) {
                                SpectraDock(
                                    destination = surface.destination,
                                    motionEnabled = preferences.motionMode == MotionMode.ON,
                                    onDestination = { navigateTo(AppSurface.Main(it)) },
                                )
                            }
                        },
                    ) { padding ->
                        AnimatedContent(
                            targetState = surface.destination,
                            transitionSpec = {
                                val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                                if (preferences.motionMode == MotionMode.ON) {
                                    if (preferences.visualStyle == SpectraVisualStyle.FLUID) {
                                        (fadeIn() + slideInVertically { it / 18 }) togetherWith
                                            (fadeOut() + slideOutVertically { -it / 28 })
                                    } else {
                                        (fadeIn() + slideInHorizontally { it * direction / 10 }) togetherWith
                                            (fadeOut() + slideOutHorizontally { -it * direction / 10 })
                                    }
                                } else {
                                    fadeIn() togetherWith fadeOut()
                                }
                            },
                            contentKey = MainDestination::name,
                            label = "main-destination",
                        ) { selected ->
                            when (selected) {
                            MainDestination.HOME -> HomeScreen(
                                 collapsedComponents = preferences.collapsedComponents,
                                 onExpandComponent = { id -> appScope.launch { preferencesRepository.setComponentCollapsed(id, false) } },
                                 records = records,
                                 displayName = profileState.profile.displayName,
                                 avatarUrl = profileState.profile.avatarUrl,
                                 dailyText = dailyGreeting?.text.orEmpty(),
                                 announcements = announcementState,
                                 onRefreshAnnouncements = campusViewModel::refreshAnnouncements,
                                 onStartRecord = { navigateTo(AppSurface.Main(MainDestination.TIME)) },
                                 onOpenAi = { navigateTo(AppSurface.Ai(surface.destination)) },
                                 healthState = healthState,
                                 onRefreshHealth = aiViewModel::refreshHealthStatus,
                                 onSyncMiFitnessSteps = aiViewModel::refreshMiFitnessSteps,
                                contentPadding = padding,
                            )
                            MainDestination.TIME -> TimeScreen(
                                records = records,
                                viewModel = timeViewModel,
                                onStartFocus = { navigateTo(AppSurface.Focus(it, surface.destination)) },
                                onMessage = { message, action -> snackbar.showSnackbar(message, actionLabel = action) },
                                contentPadding = padding,
                            )
                            MainDestination.CAMPUS -> CampusScreen(
                                state = campusState,
                                signedIn = authState.signedIn,
                                userId = authState.userId,
                                displayName = profileState.profile.displayName
                                    .ifBlank { authState.email.substringBefore('@') }
                                    .ifBlank { "我" },
                                viewModel = campusViewModel,
                                onLogin = { navigateTo(AppSurface.Login(surface.destination)) },
                                contentPadding = padding,
                            )
                            MainDestination.MARKET -> MarketScreen(
                                state = campusState,
                                signedIn = authState.signedIn,
                                userId = authState.userId,
                                viewModel = campusViewModel,
                                onLogin = { navigateTo(AppSurface.Login(surface.destination)) },
                                onOpenConversation = { conversationId ->
                                    campusViewModel.openMessageThread(conversationId)
                                    navigateTo(AppSurface.Messages(surface.destination))
                                },
                                contentPadding = padding,
                            )
                            MainDestination.PROFILE -> ProfileScreen(
                                preferences = preferences,
                                repository = preferencesRepository,
                                records = records,
                                authState = authState,
                                unreadMessages = unreadMessages,
                                onLogin = { navigateTo(AppSurface.Login(surface.destination)) },
                                onSignOut = authRepository::signOut,
                                onOpenMessages = { navigateTo(AppSurface.Messages(surface.destination)) },
                                localModelManager = localModelManager,
                                localAiEngine = localAiEngine,
                                 personalAiProviderStore = personalAiProviderStore,
                                 profileRepository = profileRepository,
                                 contentPadding = padding,
                                 dailyTargetSnapshots = dailyTargetSnapshots,
                                 onOpenTimeRecordsForDay = { navigateTo(AppSurface.Main(MainDestination.TIME)) },
                                 miFitnessConfigured = healthState.miFitnessConfigured,
                                 miFitnessSyncing = healthState.miFitnessSyncing,
                                 miFitnessLastSyncAtMillis = healthState.miFitnessLastSyncAt,
                                 miFitnessStatus = healthState.miFitnessStatus.toSettingsStatus(),
                                 miFitnessFormResetKey = healthState.miFitnessFormResetKey,
                                 onSaveMiFitnessCredentials = aiViewModel::saveMiFitnessCredentials,
                                 onRefreshMiFitnessSteps = aiViewModel::refreshMiFitnessSteps,
                                 onDeleteMiFitnessCredentials = aiViewModel::deleteMiFitnessCredentials,
                                 onTestCloudProviderConnection = { provider, modelId ->
                                     runCatching {
                                         PersonalCloudClient(provider, personalAiProviderStore)
                                             .validateConnection(modelId)
                                     }
                                 },
                                 onListCloudProviderModels = { provider ->
                                     try {
                                         Result.success(
                                             PersonalCloudClient(provider, personalAiProviderStore).listModels(),
                                         )
                                     } catch (cancelled: CancellationException) {
                                         throw cancelled
                                     } catch (error: Exception) {
                                         Result.failure(error)
                                     }
                                 },
                                 healthAutomationConfig = healthAutomationConfig,
                                 healthAutomationSaving = healthAutomationSaving,
                                 healthAutomationNotificationsEnabled = healthAutomationNotificationsEnabled,
                                 healthAutomationMessage = healthAutomationMessage,
                                 healthAutomationMessageIsError = healthAutomationMessageIsError,
                                 onSaveHealthAutomation = saveAutomation@ { provider, rawModelId, intervalMinutes, includeSummary ->
                                     if (healthAutomationSaving) return@saveAutomation
                                     healthAutomationSaving = true
                                     healthAutomationMessage = null
                                     healthAutomationMessageIsError = false
                                     appScope.launch {
                                         val modelId = provider.normalizeModelId(rawModelId)
                                         val validation = if (includeSummary) {
                                             foregroundHealthRuntime.aiClient.validate(provider, modelId)
                                         } else {
                                             Result.failure(IllegalArgumentException("需要先允许附带必要的今日汇总。"))
                                         }
                                         validation.fold(
                                             onSuccess = {
                                                 foregroundHealthRuntime.store.mutateWithRetry(
                                                     taskId = HealthTaskDefaults.TASK_ID,
                                                 ) { current ->
                                                     mergeEnabledHealthTaskConfig(
                                                         current = current,
                                                         provider = provider,
                                                         modelId = modelId,
                                                         intervalMinutes = intervalMinutes,
                                                         includeHealthSummary = true,
                                                     )
                                                 }.fold(
                                                     onSuccess = { mutation ->
                                                         val saved = checkNotNull(mutation.updated)
                                                         val wasEnabled = mutation.previous?.enabled == true
                                                         healthAutomationConfig = saved
                                                         healthAutomationMessage = "已启用，回到前台后会立即检查小米云。"
                                                         healthAutomationMessageIsError = false
                                                         if (
                                                             !wasEnabled &&
                                                             Build.VERSION.SDK_INT >= 33 &&
                                                             ContextCompat.checkSelfPermission(
                                                                 context,
                                                                 Manifest.permission.POST_NOTIFICATIONS,
                                                             ) != PackageManager.PERMISSION_GRANTED
                                                         ) {
                                                             notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                         }
                                                     },
                                                     onFailure = { error ->
                                                         healthAutomationMessage = error.message ?: "任务设置保存失败。"
                                                         healthAutomationMessageIsError = true
                                                     },
                                                 )
                                             },
                                             onFailure = { error ->
                                                 healthAutomationMessage = error.message ?: "Provider 或模型验证失败。"
                                                 healthAutomationMessageIsError = true
                                             },
                                         )
                                         healthAutomationSaving = false
                                     }
                                 },
                                  onDisableHealthAutomation = disableAutomation@ {
                                     if (healthAutomationSaving) return@disableAutomation
                                      healthAutomationSaving = true
                                      appScope.launch {
                                          foregroundHealthRuntime.store.mutateWithRetry(
                                              taskId = HealthTaskDefaults.TASK_ID,
                                              transform = ::mergeDisabledHealthTaskConfig,
                                          ).fold(
                                              onSuccess = { mutation ->
                                                  healthAutomationConfig = mutation.updated
                                                  healthAutomationMessage = "定时任务已停用。"
                                                 healthAutomationMessageIsError = false
                                             },
                                             onFailure = { error ->
                                                 healthAutomationMessage = error.message ?: "停用失败，请重试。"
                                                 healthAutomationMessageIsError = true
                                             },
                                         )
                                         healthAutomationSaving = false
                                     }
                                 },
                             )
                        }
                    }
                }
                }
                is AppSurface.Focus -> {
                    FocusSessionScreen(
                        presetMinutes = surface.presetMinutes,
                        motionEnabled = preferences.motionMode == MotionMode.ON,
                        soundEnabled = preferences.soundEnabled,
                        onMinimize = { navigateTo(AppSurface.Main(surface.returnDestination)) },
                        onFinish = { elapsedMinutes ->
                            val end = System.currentTimeMillis()
                            timeViewModel.addTimeRecord("专注 $elapsedMinutes 分钟", "专注", end - elapsedMinutes * 60_000L, end, "专注计时自动记录")
                            navigateTo(AppSurface.Main(surface.returnDestination))
                        },
                    )
                }
                is AppSurface.Ai -> {
                    AiScreen(
                        viewModel = aiViewModel,
                        snapshot = aiSnapshot,
                        motionEnabled = preferences.motionMode == MotionMode.ON,
                        visualStyle = preferences.visualStyle,
                        onVisualStyleChange = { style -> appScope.launch { preferencesRepository.setVisualStyle(style) } },
                        onBack = { navigateTo(AppSurface.Main(surface.returnDestination)) },
                    )
                }
                is AppSurface.Messages -> {
                    MessageCenterScreen(
                        state = campusState,
                        userId = authState.userId,
                        viewModel = campusViewModel,
                        onBack = {
                            campusViewModel.closeMessageThread()
                            navigateTo(AppSurface.Main(surface.returnDestination))
                        },
                    )
                }
                is AppSurface.Login -> {
                    AuthScreen(
                        state = authState,
                        onSignIn = authRepository::signIn,
                        onSignUp = authRepository::signUp,
                        onClearMessage = authRepository::clearError,
                        onBack = {
                            authRepository.clearError()
                            navigateTo(AppSurface.Main(surface.returnDestination))
                        },
                    )
                }
            }
            if (loadedPreferences?.onboardingCompleted == false) {
                WelcomeGuide { appScope.launch { preferencesRepository.completeOnboarding() } }
            }
        }
        }
        }
    }
}

private const val FOREGROUND_TASK_TICK_MILLIS = 15_000L

private fun MiFitnessUiStatus.toSettingsStatus(): MiFitnessSettingsStatus = when (this) {
    MiFitnessUiStatus.IDLE -> MiFitnessSettingsStatus.IDLE
    MiFitnessUiStatus.VALIDATING -> MiFitnessSettingsStatus.VALIDATING
    MiFitnessUiStatus.REFRESHING -> MiFitnessSettingsStatus.REFRESHING
    MiFitnessUiStatus.DELETING -> MiFitnessSettingsStatus.DELETING
    MiFitnessUiStatus.SUCCESS -> MiFitnessSettingsStatus.SUCCESS
    MiFitnessUiStatus.NO_DATA -> MiFitnessSettingsStatus.NO_DATA
    MiFitnessUiStatus.AUTH_ERROR -> MiFitnessSettingsStatus.AUTH_ERROR
    MiFitnessUiStatus.NETWORK_ERROR -> MiFitnessSettingsStatus.NETWORK_ERROR
    MiFitnessUiStatus.STORAGE_ERROR -> MiFitnessSettingsStatus.STORAGE_ERROR
}

@Composable
internal fun SpectraDock(
    destination: MainDestination,
    motionEnabled: Boolean,
    onDestination: (MainDestination) -> Unit,
) {
    val entries = MainDestination.entries
    val layout = SpectraTheme.layout
    val fluid = SpectraTheme.isFluid
    val dark = MaterialTheme.colorScheme.background.luminance() < .35f
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val animatedCenter = remember { Animatable(0f) }
    var dockWidth by remember { mutableFloatStateOf(0f) }
    var dragCenter by remember { mutableFloatStateOf(0f) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var visualIndex by remember { mutableIntStateOf(destination.ordinal) }

    fun centerFor(index: Int): Float {
        val slot = dockWidth / entries.size.coerceAtLeast(1)
        return slot * (index + .5f)
    }

    LaunchedEffect(destination, dockWidth, dragging, motionEnabled) {
        if (dockWidth <= 0f || dragging) return@LaunchedEffect
        visualIndex = destination.ordinal
        val target = centerFor(destination.ordinal)
        if (!motionEnabled || animatedCenter.value == 0f) animatedCenter.snapTo(target)
        else animatedCenter.animateTo(
            target,
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }

    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = layout.dockHorizontalPadding, vertical = layout.dockVerticalPadding),
    ) {
        GlassPanel(
            Modifier.fillMaxWidth().height(layout.dockHeight),
            radius = (layout.dockHeight.value / 2f).roundToInt(),
            emphasized = true,
            shadowed = false,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (fluid) 5.dp else 7.dp)
                    .onSizeChanged {
                        dockWidth = it.width.toFloat()
                        if (animatedCenter.value == 0f) {
                            dragCenter = centerFor(destination.ordinal)
                        }
                    }
                    .pointerInput(dockWidth, motionEnabled) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                if (dockWidth <= 0f) return@detectHorizontalDragGestures
                                dragging = true
                                dragCenter = animatedCenter.value.takeIf { it > 0f } ?: centerFor(destination.ordinal)
                                dragDelta = 0f
                                visualIndex = destination.ordinal
                            },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                val slot = dockWidth / entries.size
                                dragCenter = (dragCenter + amount).coerceIn(slot * .5f, dockWidth - slot * .5f)
                                dragDelta = if (motionEnabled) amount else 0f
                                val next = ((dragCenter / slot) - .5f).roundToInt().coerceIn(entries.indices)
                                if (next != visualIndex) {
                                    visualIndex = next
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            },
                            onDragEnd = {
                                val targetIndex = visualIndex.coerceIn(entries.indices)
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                scope.launch {
                                    animatedCenter.snapTo(dragCenter)
                                    dragging = false
                                    dragDelta = 0f
                                    onDestination(entries[targetIndex])
                                }
                            },
                            onDragCancel = {
                                scope.launch {
                                    animatedCenter.snapTo(dragCenter)
                                    dragging = false
                                    dragDelta = 0f
                                    visualIndex = destination.ordinal
                                }
                            },
                        )
                    },
            ) {
                val center = if (dragging) dragCenter else animatedCenter.value
                LiquidDockSelection(
                    centerX = center,
                    dragDelta = dragDelta,
                    motionEnabled = motionEnabled,
                    fluid = fluid,
                    modifier = Modifier.fillMaxSize(),
                )
                Row(
                    Modifier.fillMaxSize().semantics { selectableGroup() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    entries.forEachIndexed { index, item ->
                        val selected = index == if (dragging) visualIndex else destination.ordinal
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag("main-nav-${item.name.lowercase()}")
                                .selectable(
                                    selected = selected,
                                    interactionSource = remember(item) { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Tab,
                                    onClick = {
                                        visualIndex = index
                                        if (item != destination) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onDestination(item)
                                    },
                                )
                                .semantics(mergeDescendants = true) {
                                    contentDescription = item.label
                                    this.selected = selected
                                    stateDescription = if (selected) "当前页" else "未选中"
                                },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                item.icon,
                                null,
                                tint = if (selected) {
                                    if (dark) Color.White else SpectraColors.Ink
                                } else MaterialTheme.colorScheme.onSurface.copy(.62f),
                                modifier = Modifier.size(if (selected) 20.dp else 22.dp),
                            )
                            if (selected) {
                                Spacer(Modifier.size(4.dp))
                                Text(
                                    item.label,
                                    color = if (dark) Color.White else SpectraColors.Ink,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiquidDockSelection(
    centerX: Float,
    dragDelta: Float,
    motionEnabled: Boolean,
    fluid: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .35f
    Canvas(modifier) {
        if (centerX <= 0f) return@Canvas
        val baseHalfWidth = with(density) { (if (fluid) 34.dp else 38.dp).toPx() }
        val halfHeight = with(density) { (if (fluid) 19.dp else 23.dp).toPx() }
        val maxStretch = with(density) { (if (fluid) 16.dp else 12.dp).toPx() }
        val stretch = if (motionEnabled) (abs(dragDelta) * .68f).coerceAtMost(maxStretch) else 0f
        val direction = when {
            dragDelta > .4f -> 1f
            dragDelta < -.4f -> -1f
            else -> 0f
        }
        val left = centerX - baseHalfWidth - if (direction < 0f) stretch else 0f
        val right = centerX + baseHalfWidth + if (direction > 0f) stretch else 0f
        val top = center.y - halfHeight
        val bottom = center.y + halfHeight
        val radius = halfHeight
        val liquid = Path().apply {
            moveTo(left + radius, top)
            lineTo(right - radius, top)
            cubicTo(right - radius * .28f, top, right, top + radius * .34f, right, center.y)
            cubicTo(right, bottom - radius * .34f, right - radius * .28f, bottom, right - radius, bottom)
            lineTo(left + radius, bottom)
            cubicTo(left + radius * .28f, bottom, left, bottom - radius * .34f, left, center.y)
            cubicTo(left, top + radius * .34f, left + radius * .28f, top, left + radius, top)
            close()
        }
        val bodyBrush = Brush.horizontalGradient(
            colors = if (dark) listOf(
                Color(0xFF15171D).copy(if (fluid) .50f else .58f),
                Color(0xFF373A42).copy(if (fluid) .28f else .36f),
                Color(0xFF15171D).copy(if (fluid) .50f else .58f),
            ) else listOf(
                Color.White.copy(if (fluid) .38f else .46f),
                Color(0xFFE6E7E9).copy(if (fluid) .16f else .24f),
                Color.White.copy(if (fluid) .32f else .40f),
            ),
            startX = left,
            endX = right,
        )
        drawPath(liquid, bodyBrush)
        drawPath(
            liquid,
            Color.White.copy(if (dark) .42f else .72f),
            style = Stroke(width = with(density) { 1.dp.toPx() }),
        )
        drawLine(
            Brush.horizontalGradient(
                listOf(Color.Transparent, Color.White.copy(.82f), Color.Transparent),
                startX = left,
                endX = right,
            ),
            start = Offset(left + radius * .7f, top + with(density) { 3.dp.toPx() }),
            end = Offset(right - radius * .7f, top + with(density) { 3.dp.toPx() }),
            strokeWidth = with(density) { 1.dp.toPx() },
        )
    }
}
