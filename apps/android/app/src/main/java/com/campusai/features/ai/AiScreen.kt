package com.campusai.features.ai

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.app.ActivityCompat
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.campusai.core.designsystem.GlassPanel
import com.campusai.core.designsystem.CaesarSlidingSelector
import com.campusai.core.designsystem.PageMood
import com.campusai.core.designsystem.SpectraAction
import com.campusai.core.designsystem.SpectraAlertDialog
import com.campusai.core.designsystem.SpectraColors
import com.campusai.core.designsystem.SpectraDialog
import com.campusai.core.designsystem.SpectraModalBottomSheet
import com.campusai.core.designsystem.SpectraStatus
import com.campusai.core.designsystem.SpectraStatusTone
import com.campusai.core.designsystem.SpectraSurface
import com.campusai.core.designsystem.SpectraTheme
import com.campusai.core.designsystem.SpectraVisualStyle
import com.campusai.core.health.HealthAvailability
import com.campusai.core.health.HealthFreshness
import com.campusai.core.localai.LocalModelSelection
import com.campusai.core.localai.LocalModelMode
import com.campusai.core.model.AiMode
import com.campusai.core.model.AiProvider
import com.campusai.core.model.LocalModelState
import com.campusai.core.agent.CaesarComponent
import com.campusai.core.agent.CaesarSurface
import coil.compose.AsyncImage
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import java.util.Locale
import java.util.UUID

private enum class AiVisualPreset(val label: String) {
    CLASSIC("经典"),
    FLUID("流体"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiScreen(
    viewModel: AiViewModel,
    snapshot: AiContextSnapshot,
    motionEnabled: Boolean,
    visualStyle: SpectraVisualStyle,
    onVisualStyleChange: (SpectraVisualStyle) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val selectedLocalModel by viewModel.localModelSelection.collectAsState()
    val localModelStates by viewModel.localModelStates.collectAsState()
    val history by viewModel.history.collectAsState()
    val memories by viewModel.memories.collectAsState()
    val healthState by viewModel.healthState.collectAsState()
    var prompt by rememberSaveable { mutableStateOf("") }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showTasks by rememberSaveable { mutableStateOf(false) }
    var showContext by rememberSaveable { mutableStateOf(false) }
    var showRuntime by rememberSaveable { mutableStateOf(false) }
    // The AI page consumes the same persisted interface system as every other destination.
    val visualPreset = if (visualStyle == SpectraVisualStyle.FLUID) AiVisualPreset.FLUID else AiVisualPreset.CLASSIC
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val localModel = state.lockedLocalModelId?.let(viewModel::localModelFor)?.let { locked ->
        locked.copy(state = localModelStates[locked.manifest.id] ?: locked.state)
    } ?: selectedLocalModel
    val localRouteSelected = state.provider == AiProvider.AUTO || state.provider == AiProvider.LOCAL
    val regeneratableAssistantIndex = remember(state.messages, state.streaming) {
        if (state.streaming) -1 else planAiRegeneration(state.messages)?.assistantIndex ?: -1
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(viewModel::attachImage) }
    var cameraUriValue by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val capturedUri = cameraUriValue?.let(android.net.Uri::parse)
        cameraUriValue = null
        if (saved) capturedUri?.let(viewModel::attachImage)
        else capturedUri?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
    }
    val healthPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshHealthStatus()
    }
    var listening by remember { mutableStateOf(false) }
    var voiceStartRequest by remember { mutableIntStateOf(0) }
    var consumedVoiceStartRequest by remember { mutableIntStateOf(0) }
    var systemSpeechConsentGranted by rememberSaveable { mutableStateOf(false) }
    var showSystemSpeechConsent by rememberSaveable { mutableStateOf(false) }
    var showMicrophoneSettingsRecovery by rememberSaveable { mutableStateOf(false) }
    var preferSystemSpeechActivity by rememberSaveable { mutableStateOf(false) }
    var systemSpeechActivityInFlight by remember { mutableStateOf(false) }
    var systemSpeechActivityRoute by remember { mutableStateOf(CaesarSpeechFallbackRoute.STANDARD_RECOGNITION_ACTIVITY) }
    val xiaomiEnginePermissionRecoveryAvailable = remember(context) {
        context.packageManager.resolveActivity(
            Intent(XIAOMI_PUBLIC_SPEECH_ACTION),
            PackageManager.MATCH_DEFAULT_ONLY,
        ) != null
    }
    val systemSpeechActivityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val completedRoute = systemSpeechActivityRoute
        systemSpeechActivityRoute = CaesarSpeechFallbackRoute.STANDARD_RECOGNITION_ACTIVITY
        systemSpeechActivityInFlight = false
        listening = false
        if (completedRoute == CaesarSpeechFallbackRoute.XIAOMI_ENGINE_PERMISSION_RECOVERY) {
            scope.launch {
                snackbarHost.showSnackbar("完成系统语音引擎的麦克风授权后，请返回并再点一次语音。")
            }
        } else if (result.resultCode == Activity.RESULT_OK) {
            val transcript = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (transcript.isNotEmpty()) prompt = transcript
            else scope.launch { snackbarHost.showSnackbar("没有听清，请再说一次。") }
        }
    }
    val launchSystemSpeechActivity: (CaesarSpeechFallbackRoute) -> Unit = { route ->
        if (!systemSpeechActivityInFlight) {
            systemSpeechActivityInFlight = true
            systemSpeechActivityRoute = route
            listening = true
            runCatching {
                val intent = caesarSpeechRecognitionIntent(preferOffline = false).apply {
                    if (route == CaesarSpeechFallbackRoute.XIAOMI_ENGINE_PERMISSION_RECOVERY) {
                        action = XIAOMI_PUBLIC_SPEECH_ACTION
                    }
                }
                systemSpeechActivityLauncher.launch(intent)
            }.onFailure { error ->
                systemSpeechActivityInFlight = false
                systemSpeechActivityRoute = CaesarSpeechFallbackRoute.STANDARD_RECOGNITION_ACTIVITY
                listening = false
                Log.w(SPEECH_LOG_TAG, "System recognition activity failed to start", error)
                scope.launch { snackbarHost.showSnackbar("系统语音输入无法启动，请检查默认语音识别服务。") }
            }
        }
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            voiceStartRequest += 1
        } else {
            val canAskAgain = context.findActivity()?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
            } == true
            if (canAskAgain) scope.launch {
                snackbarHost.showSnackbar("需要麦克风权限才能进行语音转写。")
            } else {
                showMicrophoneSettingsRecovery = true
            }
        }
    }
    val speechAvailability = remember(context) {
        (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) to
            SpeechRecognizer.isRecognitionAvailable(context)
    }
    var onDeviceRecognizerFailed by remember { mutableStateOf(false) }
    val speechPolicy = caesarSpeechRecognitionPolicy(
        onDeviceAvailable = speechAvailability.first,
        onDeviceRuntimeFailed = onDeviceRecognizerFailed,
        systemAvailable = speechAvailability.second,
        systemConsentGranted = systemSpeechConsentGranted,
    )
    val speechRecognizer = remember(
        context,
        speechPolicy.recognizerKind,
        speechPolicy.requiresSystemConsent,
        preferSystemSpeechActivity,
    ) {
        runCatching {
            when (speechPolicy.recognizerKind) {
                CaesarSpeechRecognizerKind.ON_DEVICE -> if (Build.VERSION.SDK_INT >= 31) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else null
                CaesarSpeechRecognizerKind.SYSTEM -> if (speechPolicy.requiresSystemConsent || preferSystemSpeechActivity) {
                    null
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
                CaesarSpeechRecognizerKind.UNAVAILABLE -> null
            }
        }.onFailure { error ->
            Log.w(SPEECH_LOG_TAG, "Failed to create ${speechPolicy.recognizerKind} recognizer", error)
        }.getOrNull()
    }
    LaunchedEffect(speechPolicy.recognizerKind, speechRecognizer) {
        if (speechPolicy.recognizerKind == CaesarSpeechRecognizerKind.ON_DEVICE && speechRecognizer == null) {
            onDeviceRecognizerFailed = true
        }
    }
    DisposableEffect(speechRecognizer) {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) {
                listening = false
                val audioPermissionGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                Log.w(
                    SPEECH_LOG_TAG,
                    "Recognizer error=$error kind=${speechPolicy.recognizerKind} appPermission=$audioPermissionGranted",
                )
                val shouldFallbackFromOnDevice = speechPolicy.recognizerKind == CaesarSpeechRecognizerKind.ON_DEVICE &&
                    speechAvailability.second &&
                    audioPermissionGranted &&
                    error in setOf(
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                        SpeechRecognizer.ERROR_CLIENT,
                        SpeechRecognizer.ERROR_SERVER,
                    )
                val fallbackRoute = caesarSpeechFallbackRoute(
                    recognizerKind = speechPolicy.recognizerKind,
                    errorCode = error,
                    appMicrophonePermissionGranted = audioPermissionGranted,
                    xiaomiEnginePermissionRecoveryAvailable = xiaomiEnginePermissionRecoveryAvailable,
                )
                val shouldFallbackToSystemActivity =
                    fallbackRoute != CaesarSpeechFallbackRoute.NONE && !systemSpeechActivityInFlight
                if (shouldFallbackFromOnDevice) {
                    onDeviceRecognizerFailed = true
                    if (systemSpeechConsentGranted) voiceStartRequest += 1
                    else showSystemSpeechConsent = true
                }
                if (shouldFallbackToSystemActivity) {
                    preferSystemSpeechActivity = true
                    launchSystemSpeechActivity(fallbackRoute)
                }
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && !audioPermissionGranted) {
                    showMicrophoneSettingsRecovery = true
                }
                val message = when {
                    shouldFallbackFromOnDevice -> "端侧语音服务未能启动，已为你切换到系统语音识别。"
                    shouldFallbackToSystemActivity &&
                        fallbackRoute != CaesarSpeechFallbackRoute.XIAOMI_ENGINE_PERMISSION_RECOVERY ->
                        "系统语音服务不接受直接麦克风访问，已切换到系统语音输入。"
                    shouldFallbackToSystemActivity -> null
                    error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && !audioPermissionGranted -> "麦克风权限已关闭，可前往系统设置恢复。"
                    error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "系统语音服务未能打开麦克风，请重试或检查系统语音识别服务。"
                    error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "系统语音服务网络不可用。"
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别器正忙，请稍后重试。"
                    error == SpeechRecognizer.ERROR_SERVER -> "系统语音服务暂时不可用。"
                    error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听清，请再说一次。"
                    else -> null
                }
                message?.let { scope.launch { snackbarHost.showSnackbar(it) } }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { prompt = it }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { prompt = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose { speechRecognizer?.cancel(); speechRecognizer?.destroy() }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, speechRecognizer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                speechRecognizer?.cancel()
                listening = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(voiceStartRequest, speechRecognizer) {
        if (voiceStartRequest == 0 || consumedVoiceStartRequest == voiceStartRequest) return@LaunchedEffect
        val recognizer = speechRecognizer ?: return@LaunchedEffect
        consumedVoiceStartRequest = voiceStartRequest
        listening = true
        runCatching {
            recognizer.startListening(caesarSpeechRecognitionIntent(speechPolicy.preferOffline))
        }.onFailure { error ->
            listening = false
            Log.w(SPEECH_LOG_TAG, "Recognizer start failed for ${speechPolicy.recognizerKind}", error)
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                showMicrophoneSettingsRecovery = true
            } else {
                snackbarHost.showSnackbar("语音识别启动失败，请稍后重试。")
            }
        }
    }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        val engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.SIMPLIFIED_CHINESE
        }
        tts = engine
        onDispose { engine.stop(); engine.shutdown(); tts = null }
    }

    LaunchedEffect(showContext, showRuntime) {
        if (showContext || showRuntime) viewModel.refreshHealthStatus()
    }

    BackHandler {
        if (showHistory) showHistory = false else onBack()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
            .windowInsetsPadding(
                WindowInsets.ime
                    .union(WindowInsets.navigationBars)
                    .only(WindowInsetsSides.Bottom),
            ),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (visualPreset == AiVisualPreset.CLASSIC) {
                ClassicAiChrome(
                    runtimeLabel = runtimeSummary(state.provider, state.model, localModel),
                    provider = state.provider,
                    localModel = localModel,
                    streaming = state.streaming,
                    showHistory = showHistory,
                    motionEnabled = motionEnabled,
                    onBack = onBack,
                    onRuntime = { showRuntime = true },
                    onProviderSelected = { index -> selectProvider(index, viewModel) },
                    onContext = { showContext = true },
                    onTasks = { showTasks = true },
                    onHistory = { showHistory = !showHistory },
                    onNewConversation = viewModel::newConversation,
                )
            } else {
                FluidAiHeader(
                    runtimeLabel = runtimeSummary(state.provider, state.model, localModel),
                    showHistory = showHistory,
                    streaming = state.streaming,
                    onBack = onBack,
                    onRuntime = { showRuntime = true },
                    onHistory = { showHistory = !showHistory },
                    onNewConversation = viewModel::newConversation,
                )
            }

            if (showHistory) {
                LazyColumn(
                    Modifier.weight(1f).padding(horizontal = SpectraTheme.layout.pageHorizontalPadding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (history.isEmpty()) item {
                        QuietEmptyState(
                            label = "还没有保存的 AI 报告",
                            motionEnabled = motionEnabled,
                            preset = visualPreset,
                        )
                    }
                    items(history, key = { it.id }) { report ->
                        GlassPanel(
                            Modifier.fillMaxWidth(),
                            radius = 16,
                            shadowed = false,
                            onClick = { viewModel.openConversation(report); showHistory = false },
                        ) {
                            Row(Modifier.fillMaxWidth().padding(start = 15.dp, top = 13.dp, bottom = 13.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(report.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.size(4.dp))
                                    Text(plainAiText(report.summary), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(.64f))
                                    Spacer(Modifier.size(6.dp))
                                    Text(
                                        "${providerLabel(report.provider)} · ${report.model.ifBlank { report.mode.name }}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(historyTime(report.updatedAt), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = {
                                    viewModel.deleteConversation(report)
                                    scope.launch {
                                        if (snackbarHost.showSnackbar("已删除对话", "撤销") == SnackbarResult.ActionPerformed) {
                                            viewModel.undoDeleteConversation()
                                        }
                                    }
                                }) { Icon(Icons.Rounded.DeleteOutline, "删除对话", tint = MaterialTheme.colorScheme.onSurface.copy(.56f)) }
                            }
                        }
                    }
                }
                SnackbarHost(
                    hostState = snackbarHost,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SpectraTheme.layout.pageHorizontalPadding, vertical = 6.dp),
                )
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (state.messages.none { it.content.isNotBlank() } && state.error == null && !state.streaming) {
                        QuietEmptyState(
                            if (localRouteSelected) localModelStatus(localModel) else selectedCloudProviderStatus(state.provider),
                            motionEnabled,
                            visualPreset,
                            Modifier.fillMaxSize(),
                            headline = "现在，想做什么？",
                        )
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize().padding(horizontal = SpectraTheme.layout.pageHorizontalPadding),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            itemsIndexed(state.messages) { messageIndex, message ->
                                if (message.content.isNotBlank() || message.attachmentPaths.isNotEmpty() || message.missingAttachmentCount > 0 || message.presentationJson != null) {
                                    Box(
                                        Modifier.fillMaxWidth(),
                                        contentAlignment = if (message.role == "user") Alignment.CenterEnd else Alignment.CenterStart,
                                    ) {
                                        Column(horizontalAlignment = if (message.role == "user") Alignment.End else Alignment.Start) {
                                            if (message.attachmentPaths.isNotEmpty()) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                                                    message.attachmentPaths.take(4).forEach { path ->
                                                        AsyncImage(model = File(path), contentDescription = "对话图片", contentScale = ContentScale.Crop, modifier = Modifier.size(112.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)))
                                                    }
                                                }
                                            }
                                            if (message.missingAttachmentCount > 0) {
                                                Text(
                                                    text = "原图片已清理",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(bottom = 6.dp),
                                                )
                                            }
                                            val surface = CaesarSurface.fromJson(message.presentationJson)
                                            val presentation = AiTaskPresentation.fromJson(message.presentationJson)
                                            when {
                                                message.role == "assistant" && surface != null -> CaesarSurfaceCard(surface, message.content, viewModel::performSurfaceAction)
                                                message.role == "assistant" && presentation != null -> AnalysisActionCard(presentation, message.content)
                                                message.content.isNotBlank() -> GlassPanel(
                                                    Modifier.fillMaxWidth(if (message.role == "user") .82f else .94f),
                                                    radius = 16,
                                                    shadowed = false,
                                                ) { AiMarkdownText(message.content, Modifier.padding(15.dp)) }
                                            }
                                            if (message.role == "assistant" && message.content.isNotBlank()) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    IconButton(onClick = {
                                                        val engine = tts ?: return@IconButton
                                                        if (engine.isSpeaking) engine.stop() else engine.speak(plainAiText(message.content), TextToSpeech.QUEUE_FLUSH, null, "caesar-${message.hashCode()}")
                                                    }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.VolumeUp, "朗读或停止", modifier = Modifier.size(18.dp)) }
                                                    if (messageIndex == regeneratableAssistantIndex) {
                                                        TextButton(
                                                            onClick = { viewModel.regenerateLastResponse(snapshot) },
                                                            modifier = Modifier.heightIn(min = 48.dp),
                                                        ) {
                                                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                                            Spacer(Modifier.size(5.dp))
                                                            Text("重新生成")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (state.streaming) item {
                                ThinkingStatusVisibility(
                                    visible = state.messages.lastOrNull()?.content.isNullOrBlank(),
                                    motionEnabled = motionEnabled,
                                    preset = visualPreset,
                                )
                            }
                            state.error?.let { error -> item { AiErrorCard(state, error, viewModel::useCloudOnce) } }
                        }
                    }
                }
                SnackbarHost(
                    hostState = snackbarHost,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SpectraTheme.layout.pageHorizontalPadding, vertical = 4.dp),
                )
                AiComposer(
                    value = prompt,
                    onValueChange = { prompt = it },
                    streaming = state.streaming,
                    motionEnabled = motionEnabled,
                    preset = visualPreset,
                    images = state.pendingImages,
                    importingImage = state.importingImage,
                    onPickImage = { imagePicker.launch("image/*") },
                    onCamera = {
                        val directory = File(context.cacheDir, "caesar-camera").apply { mkdirs() }
                        val target = File(directory, "${UUID.randomUUID()}.jpg")
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
                        cameraUriValue = uri.toString()
                        cameraLauncher.launch(uri)
                    },
                    onRemoveImage = viewModel::removeImage,
                    listening = listening,
                    onVoice = {
                        if (listening) { speechRecognizer?.stopListening(); listening = false }
                        else if (speechPolicy.recognizerKind == CaesarSpeechRecognizerKind.UNAVAILABLE) scope.launch {
                            snackbarHost.showSnackbar("当前设备没有可用的语音识别服务。")
                        }
                        else if (speechPolicy.requiresSystemConsent) showSystemSpeechConsent = true
                        else if (speechPolicy.recognizerKind == CaesarSpeechRecognizerKind.SYSTEM && preferSystemSpeechActivity) {
                            launchSystemSpeechActivity(CaesarSpeechFallbackRoute.STANDARD_RECOGNITION_ACTIVITY)
                        }
                        else if (speechRecognizer == null) scope.launch {
                            snackbarHost.showSnackbar("系统语音识别器初始化失败，请稍后重试。")
                        }
                        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voiceStartRequest += 1
                        else audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onAction = {
                        if (state.streaming) viewModel.cancel()
                        else if (prompt.isNotBlank() || state.pendingImages.isNotEmpty()) {
                            val value = prompt.ifBlank { "请分析这些图片。" }
                            prompt = ""
                            viewModel.send(value, snapshot)
                        }
                    },
                )
            }
        }
    }

    if (showSystemSpeechConsent) {
        SpectraAlertDialog(
            onDismissRequest = { showSystemSpeechConsent = false },
            title = "使用系统语音识别？",
            message = "这台设备没有可用的端侧识别器。继续后，录音可能由系统语音服务联网处理。" +
                "Caesar∞ 不会将录音写入记忆或 Trace；本次进入 AI 页期间只询问一次。",
            confirmLabel = "同意并继续",
            dismissLabel = "取消",
            onConfirm = {
                showSystemSpeechConsent = false
                systemSpeechConsentGranted = true
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    voiceStartRequest += 1
                } else {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
        )
    }

    if (showMicrophoneSettingsRecovery) {
        SpectraAlertDialog(
            onDismissRequest = { showMicrophoneSettingsRecovery = false },
            title = "恢复麦克风权限",
            message = "麦克风权限已被关闭且系统不再显示授权弹窗。请在应用设置中将“麦克风”改为允许，返回后再点击语音。",
            confirmLabel = "打开设置",
            dismissLabel = "取消",
            onConfirm = {
                showMicrophoneSettingsRecovery = false
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            },
        )
    }

    if (showRuntime) {
        AiRuntimeSheet(
            visualPreset = visualPreset,
            provider = state.provider,
            mode = state.mode,
            localModel = localModel,
            defaultLocalModel = selectedLocalModel,
            conversationModelLocked = state.lockedLocalModelId != null,
            healthState = healthState,
            streaming = state.streaming,
            motionEnabled = motionEnabled,
            onPresetSelected = { preset ->
                onVisualStyleChange(
                    if (preset == AiVisualPreset.FLUID) SpectraVisualStyle.FLUID else SpectraVisualStyle.CLASSIC,
                )
            },
            onProviderSelected = { index -> selectProvider(index, viewModel) },
            onModeSelected = viewModel::setMode,
            onLocalModelSelected = viewModel::selectLocalModel,
            onOpenContext = {
                showRuntime = false
                showContext = true
            },
            onOpenTasks = {
                showRuntime = false
                showTasks = true
            },
            onRefreshHealth = viewModel::refreshHealthStatus,
            onSyncMiFitnessSteps = viewModel::refreshMiFitnessSteps,
            onHealthPermissions = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    healthPermissionLauncher.launch(
                        Intent().setClassName(context.packageName, "com.campusai.core.health.HealthPermissionActivity"),
                    )
                } else {
                    scope.launch { snackbarHost.showSnackbar("此 Android 版本不支持 Health Connect。") }
                }
            },
            onDismiss = { showRuntime = false },
        )
    }
    if (showTasks) {
        AiTaskSheet(
            promptAvailable = prompt.isNotBlank(),
            onDismiss = { showTasks = false },
            onSelect = { task ->
                showTasks = false
                if (task == CampusAiTask.TIME_PARSE) {
                    val value = prompt
                    prompt = ""
                    viewModel.sendTask(task, snapshot, value)
                } else viewModel.sendTask(task, snapshot)
            },
        )
    }
    if (showContext) {
        AiContextSheet(
            selection = state.contextSelection,
            provider = state.provider,
            healthState = healthState,
            memories = memories,
            onChange = viewModel::setContextSelection,
            onConfirmMemory = viewModel::confirmMemory,
            onUpdateMemory = viewModel::updateMemory,
            onForgetMemory = viewModel::forgetMemory,
            onForgetAllMemories = viewModel::forgetAllMemories,
            onRefreshHealth = viewModel::refreshHealthStatus,
            onSyncMiFitnessSteps = viewModel::refreshMiFitnessSteps,
            onHealthPermissions = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    healthPermissionLauncher.launch(
                        Intent().setClassName(context.packageName, "com.campusai.core.health.HealthPermissionActivity"),
                    )
                } else {
                    scope.launch { snackbarHost.showSnackbar("此 Android 版本不支持 Health Connect。") }
                }
            },
            onDismiss = { showContext = false },
        )
    }
}

private fun LocalModelMode.displayLabel(): String = when (this) {
    LocalModelMode.QUALITY -> "DEEP 4B"
    LocalModelMode.FAST -> "FAST 2B"
}

private fun runtimeSummary(
    provider: AiProvider,
    resolvedModel: String,
    localModel: LocalModelSelection,
): String = resolvedModel.takeIf(String::isNotBlank) ?: when (provider) {
    AiProvider.AUTO -> "自动 · ${localModel.mode.displayLabel()}优先"
    AiProvider.LOCAL -> "本机 · ${localModel.mode.displayLabel()}"
    AiProvider.DEEPSEEK -> "DeepSeek"
    AiProvider.GOOGLE_GEMINI -> "Google Gemini"
    AiProvider.CODEX -> "Codex · 私有服务"
}

private val AI_RUNTIME_PROVIDERS = listOf(
    AiProvider.AUTO,
    AiProvider.LOCAL,
    AiProvider.CODEX,
    AiProvider.DEEPSEEK,
    AiProvider.GOOGLE_GEMINI,
)

private fun providerIndex(provider: AiProvider): Int = AI_RUNTIME_PROVIDERS.indexOf(provider).coerceAtLeast(0)

private fun providerSelectorLabel(provider: AiProvider): String = when (provider) {
    AiProvider.AUTO -> "自动"
    AiProvider.LOCAL -> "本机"
    AiProvider.CODEX -> "Codex"
    AiProvider.DEEPSEEK -> "DeepSeek"
    AiProvider.GOOGLE_GEMINI -> "Gemini"
}

private fun selectProvider(index: Int, viewModel: AiViewModel) {
    AI_RUNTIME_PROVIDERS.getOrNull(index)?.let(viewModel::setProvider)
}

@Composable
private fun FluidAiHeader(
    runtimeLabel: String,
    showHistory: Boolean,
    streaming: Boolean,
    onBack: () -> Unit,
    onRuntime: () -> Unit,
    onHistory: () -> Unit,
    onNewConversation: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
        }
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = !streaming, role = Role.Button, onClick = onRuntime)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Text("Caesar∞", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "流体 · $runtimeLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(enabled = !streaming, onClick = onHistory, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.History, if (showHistory) "返回对话" else "报告历史")
        }
        IconButton(enabled = !streaming, onClick = onNewConversation, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.AddComment, "新对话")
        }
    }
}

@Composable
private fun ClassicAiChrome(
    runtimeLabel: String,
    provider: AiProvider,
    localModel: LocalModelSelection,
    streaming: Boolean,
    showHistory: Boolean,
    motionEnabled: Boolean,
    onBack: () -> Unit,
    onRuntime: () -> Unit,
    onProviderSelected: (Int) -> Unit,
    onContext: () -> Unit,
    onTasks: () -> Unit,
    onHistory: () -> Unit,
    onNewConversation: () -> Unit,
) {
    GlassPanel(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        radius = 18,
        emphasized = true,
        opticalPriority = 4,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !streaming, role = Role.Button, onClick = onRuntime)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text("Caesar∞", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    runtimeLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(.58f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(enabled = !streaming, onClick = onHistory) {
                Icon(Icons.Rounded.History, if (showHistory) "返回对话" else "报告历史")
            }
            IconButton(enabled = !streaming, onClick = onNewConversation) {
                Icon(Icons.Rounded.AddComment, "新对话")
            }
        }
    }
    if (showHistory) return

    CaesarSlidingSelector(
        options = AI_RUNTIME_PROVIDERS.map(::providerSelectorLabel),
        selectedIndex = providerIndex(provider),
        enabled = !streaming,
        motionEnabled = motionEnabled,
        onSelected = onProviderSelected,
        modifier = Modifier.fillMaxWidth().padding(horizontal = SpectraTheme.layout.pageHorizontalPadding),
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = SpectraTheme.layout.pageHorizontalPadding, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassPanel(
            Modifier.weight(1f).heightIn(min = 54.dp),
            radius = 24,
            shadowed = false,
            onClick = { if (!streaming) onRuntime() },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LiquidMetalOrb(active = false, motionEnabled = motionEnabled, size = 34.dp)
                Text(
                    when (provider) {
                        AiProvider.DEEPSEEK -> "DEEPSEEK"
                        AiProvider.GOOGLE_GEMINI -> "GEMINI"
                        AiProvider.CODEX -> "CODEX · TAILSCALE"
                        AiProvider.AUTO, AiProvider.LOCAL -> "${localModel.mode.displayLabel()} · 本机"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(.68f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        GlassPanel(
            Modifier.size(54.dp),
            radius = 27,
            shadowed = false,
            onClick = { if (!streaming) onContext() },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Policy, "对话依据与手环", tint = SpectraColors.Focus)
            }
        }
        GlassPanel(
            Modifier.size(54.dp),
            radius = 27,
            shadowed = false,
            onClick = { if (!streaming) onTasks() },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.AutoAwesome, "快捷任务", tint = SpectraColors.Violet)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiRuntimeSheet(
    visualPreset: AiVisualPreset,
    provider: AiProvider,
    mode: AiMode,
    localModel: LocalModelSelection,
    defaultLocalModel: LocalModelSelection,
    conversationModelLocked: Boolean,
    healthState: CaesarHealthUiState,
    streaming: Boolean,
    motionEnabled: Boolean,
    onPresetSelected: (AiVisualPreset) -> Unit,
    onProviderSelected: (Int) -> Unit,
    onModeSelected: (AiMode) -> Unit,
    onLocalModelSelected: (LocalModelMode) -> Unit,
    onOpenContext: () -> Unit,
    onOpenTasks: () -> Unit,
    onRefreshHealth: () -> Unit,
    onSyncMiFitnessSteps: () -> Unit,
    onHealthPermissions: () -> Unit,
    onDismiss: () -> Unit,
) {
    SpectraModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Caesar∞ 界面体系", style = MaterialTheme.typography.headlineSmall)
            Text(
                "选择会同步改变整个 App 的背景场、页面留白、卡片体积、导航与转场；模型、图片、语音、健康与手环能力保持一致。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(.62f),
            )
            Text("全局预设", style = MaterialTheme.typography.labelLarge)
            CaesarSlidingSelector(
                options = AiVisualPreset.entries.map(AiVisualPreset::label),
                selectedIndex = visualPreset.ordinal,
                enabled = !streaming,
                motionEnabled = motionEnabled,
                onSelected = { onPresetSelected(AiVisualPreset.entries[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
            HealthStatus(
                state = healthState,
                onRefresh = onRefreshHealth,
                onCloudRefresh = onSyncMiFitnessSteps,
                onPermissions = onHealthPermissions,
            )
            Text("运行方式", style = MaterialTheme.typography.labelLarge)
            CaesarSlidingSelector(
                options = AI_RUNTIME_PROVIDERS.map(::providerSelectorLabel),
                selectedIndex = providerIndex(provider),
                enabled = !streaming,
                motionEnabled = motionEnabled,
                onSelected = onProviderSelected,
                modifier = Modifier.fillMaxWidth(),
            )
            if (provider == AiProvider.DEEPSEEK || provider == AiProvider.GOOGLE_GEMINI || provider == AiProvider.CODEX) {
                CaesarSlidingSelector(
                    options = listOf("快速", "深度"),
                    selectedIndex = if (mode == AiMode.FAST) 0 else 1,
                    enabled = !streaming,
                    motionEnabled = motionEnabled,
                    onSelected = { onModeSelected(if (it == 0) AiMode.FAST else AiMode.DEEP) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text("新会话默认", style = MaterialTheme.typography.labelLarge)
                CaesarSlidingSelector(
                    options = listOf("FAST · 2B", "DEEP · 4B"),
                    selectedIndex = if (defaultLocalModel.mode == LocalModelMode.FAST) 0 else 1,
                    enabled = !streaming,
                    motionEnabled = motionEnabled,
                    onSelected = { onLocalModelSelected(if (it == 0) LocalModelMode.FAST else LocalModelMode.QUALITY) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (conversationModelLocked) {
                        "本会话已锁定 ${localModel.mode.displayLabel()} · ${localModel.manifest.quantization}。上方选择只作用于新会话。"
                    } else {
                        "本会话尚未锁定模型；第一次发送时将锁定 ${defaultLocalModel.mode.displayLabel()}。选择不会开始下载。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(.62f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SpectraAction(
                    text = "对话依据与手环",
                    icon = Icons.Rounded.Policy,
                    onClick = onOpenContext,
                    modifier = Modifier.weight(1f),
                )
                SpectraAction(
                    text = "快捷任务",
                    icon = Icons.Rounded.AutoAwesome,
                    onClick = onOpenTasks,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun providerLabel(provider: AiProvider) = when (provider) {
    AiProvider.LOCAL -> "本地"
    AiProvider.DEEPSEEK -> "DeepSeek"
    AiProvider.GOOGLE_GEMINI -> "Gemini"
    AiProvider.CODEX -> "Codex"
    AiProvider.AUTO -> "自动"
}

internal fun selectedCloudProviderStatus(provider: AiProvider): String = when (provider) {
    AiProvider.DEEPSEEK -> "DEEPSEEK · 已选择"
    AiProvider.GOOGLE_GEMINI -> "GEMINI · 已选择"
    AiProvider.CODEX -> "CODEX · 已选择"
    AiProvider.LOCAL, AiProvider.AUTO -> "本机 · 已选择"
}

private fun localModelStatus(selection: LocalModelSelection): String = when (val state = selection.state) {
    LocalModelState.NotDownloaded -> "QWEN · ${selection.mode.displayLabel()} · 未安装"
    LocalModelState.Checking -> "QWEN · ${selection.mode.displayLabel()} · 正在检查"
    is LocalModelState.Downloading -> "QWEN · ${selection.mode.displayLabel()} · ${(state.progress * 100).toInt()}%"
    is LocalModelState.Paused -> "QWEN · ${selection.mode.displayLabel()} · 已暂停"
    LocalModelState.Verifying -> "QWEN · ${selection.mode.displayLabel()} · 正在校验"
    LocalModelState.Ready -> "QWEN · ${selection.mode.displayLabel()} · 本机就绪"
    LocalModelState.Loading -> "QWEN · ${selection.mode.displayLabel()} · 正在加载"
    is LocalModelState.Error -> "QWEN · ${selection.mode.displayLabel()} · 不可用"
    is LocalModelState.Incompatible -> "QWEN · ${selection.mode.displayLabel()} · 不兼容"
}

private fun historyTime(value: Long): String = runCatching {
    DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()))
}.getOrDefault("")

@Composable
private fun AnalysisActionCard(presentation: AiTaskPresentation, answer: String) {
    GlassPanel(Modifier.fillMaxWidth(.96f), radius = 18, shadowed = false) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("分析", style = MaterialTheme.typography.labelMedium, color = SpectraColors.Focus)
            Text(presentation.headline, style = MaterialTheme.typography.titleLarge)
            val cleaned = plainAiText(answer).trim()
            if (cleaned.isNotBlank()) {
                Spacer(Modifier.size(7.dp))
                Text(cleaned, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.68f))
            }
            if (presentation.actionBlocks.isNotEmpty()) {
                Spacer(Modifier.size(13.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.10f))
                Spacer(Modifier.size(11.dp))
                Text("行动", style = MaterialTheme.typography.labelMedium, color = SpectraColors.Violet)
                presentation.actionBlocks.forEachIndexed { index, block ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(28.dp).background(SpectraColors.Focus.copy(.12f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = SpectraColors.Focus) }
                        Spacer(Modifier.size(10.dp))
                        Text(block.subject, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                        Text("${block.durationMinutes} 分钟", style = MaterialTheme.typography.labelLarge)
                    }
                    if (index < presentation.actionBlocks.lastIndex && presentation.breakMinutes > 0) {
                        Text(
                            "休息 ${presentation.breakMinutes} 分钟",
                            Modifier.padding(start = 38.dp, top = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(.48f),
                        )
                    }
                }
                if (presentation.remainingAfterPlanMinutes > 0) {
                    Text(
                        "这份启动计划完成后仍有 ${presentation.remainingAfterPlanMinutes} 分钟未覆盖",
                        Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(.48f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CaesarSurfaceCard(surface: CaesarSurface, answer: String, onAction: (String) -> Unit) {
    GlassPanel(Modifier.fillMaxWidth(.96f), radius = 18, shadowed = false) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(surface.title.ifBlank { "Caesar∞" }, style = MaterialTheme.typography.titleLarge)
            val cleanAnswer = plainAiText(answer).trim()
            if (cleanAnswer.isNotBlank()) Text(cleanAnswer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.68f))
            surface.components.forEach { component ->
                when (component) {
                    is CaesarComponent.Text -> Text(component.text, style = if (component.emphasis) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
                    is CaesarComponent.Metric -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Text(component.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Column(horizontalAlignment = Alignment.End) {
                            Text(component.value, style = MaterialTheme.typography.titleLarge)
                            if (component.freshness.isNotBlank()) Text(component.freshness, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.5f))
                        }
                    }
                    is CaesarComponent.ListItems -> component.items.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    is CaesarComponent.Progress -> Column {
                        Text(component.label, style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(progress = { component.value }, modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
                    }
                    is CaesarComponent.Button -> TextButton(onClick = { onAction(component.actionId) }, modifier = Modifier.fillMaxWidth()) {
                        Text(component.label, color = if (component.destructive) MaterialTheme.colorScheme.error else SpectraColors.Focus)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiContextSheet(
    selection: AiContextSelection,
    provider: AiProvider,
    healthState: CaesarHealthUiState,
    memories: List<CaesarMemoryUiItem>,
    onChange: (AiContextSelection) -> Unit,
    onConfirmMemory: (String) -> Unit,
    onUpdateMemory: (String, String) -> Unit,
    onForgetMemory: (String) -> Unit,
    onForgetAllMemories: () -> Unit,
    onRefreshHealth: () -> Unit,
    onSyncMiFitnessSteps: () -> Unit,
    onHealthPermissions: () -> Unit,
    onDismiss: () -> Unit,
) {
    var editingMemory by remember { mutableStateOf<CaesarMemoryUiItem?>(null) }
    var editedContent by remember { mutableStateOf("") }
    var deletingMemory by remember { mutableStateOf<CaesarMemoryUiItem?>(null) }
    var confirmForgetAll by remember { mutableStateOf(false) }
    SpectraModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text("本次对话依据", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (provider == AiProvider.LOCAL) "所选内容仅在本机处理，不会离开设备。"
                else "所选的学习上下文可能发送给当前云端 Provider；健康摘要只有在本次请求明确勾选后才会附带。",
                Modifier.padding(top = 5.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(.62f),
            )
            HealthStatus(
                state = healthState,
                onRefresh = onRefreshHealth,
                onCloudRefresh = onSyncMiFitnessSteps,
                onPermissions = onHealthPermissions,
            )
            Spacer(Modifier.height(14.dp))
            GlassPanel(Modifier.fillMaxWidth(), radius = 16, shadowed = false) {
                Column {
                    ContextToggle("时间记录与精确统计", selection.timeRecords) { onChange(selection.copy(timeRecords = it)) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.08f))
                    ContextToggle("课程与已计算冲突", selection.courses) { onChange(selection.copy(courses = it)) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.08f))
                    ContextToggle("我自己的相关动态", selection.ownPosts) { onChange(selection.copy(ownPosts = it)) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.08f))
                    ContextToggle("明确加入公共树洞动态", selection.publicPosts) { onChange(selection.copy(publicPosts = it)) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.08f))
                    ContextToggle("本次附带今日健康摘要（发送后自动关闭）", selection.healthSummary) {
                        onChange(selection.copy(healthSummary = it))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            MemoryManagerSection(
                memories = memories,
                onConfirm = onConfirmMemory,
                onEdit = { memory -> editingMemory = memory; editedContent = memory.content },
                onDelete = { deletingMemory = it },
                onDeleteAll = { confirmForgetAll = true },
            )
            Text(
                "私聊、账号安全信息、原始健康序列与他人私有数据永不加入。",
                Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(.52f),
            )
        }
    }

    editingMemory?.let { memory ->
        SpectraDialog(onDismissRequest = { editingMemory = null }) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("编辑记忆", style = MaterialTheme.typography.titleLarge)
                    Text(memoryTypeLabel(memory.type), style = MaterialTheme.typography.labelLarge, color = SpectraColors.Focus)
                    GlassPanel(Modifier.fillMaxWidth(), radius = 16, shadowed = false) {
                        BasicTextField(
                            value = editedContent,
                            onValueChange = { editedContent = it.take(1_000) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(14.dp),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(SpectraColors.Focus),
                        )
                    }
                    Text("${editedContent.length}/1000", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.5f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { editingMemory = null }) { Text("取消") }
                    TextButton(
                        enabled = editedContent.isNotBlank(),
                        onClick = {
                            onUpdateMemory(memory.id, editedContent)
                            editingMemory = null
                        },
                    ) { Text("保存") }
                }
            }
        }
    }
    deletingMemory?.let { memory ->
        SpectraAlertDialog(
            onDismissRequest = { deletingMemory = null },
            title = "删除这条记忆？",
            message = memory.content,
            confirmLabel = "删除",
            dismissLabel = "取消",
            destructive = true,
            onConfirm = { onForgetMemory(memory.id); deletingMemory = null },
        )
    }
    if (confirmForgetAll) {
        SpectraAlertDialog(
            onDismissRequest = { confirmForgetAll = false },
            title = "清空全部长期记忆？",
            message = "清空后 Caesar∞ 将不再使用这些已确认的偏好、事实、目标与习惯。",
            confirmLabel = "全部删除",
            dismissLabel = "取消",
            destructive = true,
            onConfirm = { onForgetAllMemories(); confirmForgetAll = false },
        )
    }
}

@Composable
private fun MemoryManagerSection(
    memories: List<CaesarMemoryUiItem>,
    onConfirm: (String) -> Unit,
    onEdit: (CaesarMemoryUiItem) -> Unit,
    onDelete: (CaesarMemoryUiItem) -> Unit,
    onDeleteAll: () -> Unit,
) {
    val now = System.currentTimeMillis()
    SpectraSurface(Modifier.fillMaxWidth(), mood = PageMood.PERSONAL) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("长期记忆", style = MaterialTheme.typography.titleMedium)
                Text(
                    "只有已确认内容会进入后续对话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(.56f),
                )
            }
            if (memories.isNotEmpty()) TextButton(onClick = onDeleteAll) { Text("清空") }
        }
        if (memories.isEmpty()) {
            Text("暂无记忆。你可以对 Caesar∞ 说“记住我喜欢……”，确认后才会保存。", style = MaterialTheme.typography.bodyMedium)
        } else {
            memories.forEachIndexed { index, memory ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.08f))
                val expired = memory.expiresAt?.let { it <= now } == true
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(memoryTypeLabel(memory.type), style = MaterialTheme.typography.labelLarge, color = SpectraColors.Focus, modifier = Modifier.weight(1f))
                        SpectraStatus(
                            when {
                                expired -> "已过期"
                                memory.confirmed -> "已确认"
                                else -> "待确认"
                            },
                            tone = if (memory.confirmed && !expired) SpectraStatusTone.SUCCESS else SpectraStatusTone.NEUTRAL,
                        )
                    }
                    Text(memory.content, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (!memory.confirmed && !expired) TextButton(onClick = { onConfirm(memory.id) }) { Text("确认") }
                        TextButton(onClick = { onEdit(memory) }) { Text("编辑") }
                        TextButton(onClick = { onDelete(memory) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

private fun memoryTypeLabel(type: String): String = when (type) {
    "preference" -> "偏好"
    "fact" -> "事实"
    "goal" -> "目标"
    "routine" -> "习惯"
    else -> "记忆"
}

@Composable
private fun HealthStatus(
    state: CaesarHealthUiState,
    onRefresh: () -> Unit,
    onCloudRefresh: () -> Unit,
    onPermissions: () -> Unit,
) {
    if (state.miFitnessConfigured) {
        MiFitnessCloudStatus(state, onCloudRefresh)
        return
    }
    val hasVerifiedHealthData = state.snapshot?.metrics?.let { metrics ->
        listOf(
            metrics.steps,
            metrics.distanceMeters,
            metrics.activeCaloriesKcal,
            metrics.heartRateAverageBpm,
            metrics.heartRateMaximumBpm,
            metrics.restingHeartRateBpm,
            metrics.oxygenSaturationAveragePercent,
            metrics.sleepMinutes,
            metrics.sleepStageCount,
            metrics.workoutCount,
        ).any { it != null }
    } == true
    val healthLabel = when (state.availability) {
        HealthAvailability.Available -> if (hasVerifiedHealthData) "已授权 · 数据已读取" else "已授权 · 尚无数据"
        is HealthAvailability.MissingPermissions -> if (hasVerifiedHealthData) "部分授权 · 数据已读取" else "部分授权 · 尚无数据"
        HealthAvailability.NeedsProvider -> "Health Connect 需更新"
        HealthAvailability.Unsupported -> "当前系统不支持"
        null -> if (state.loading) "正在检查 Health Connect" else "Health Connect 未检查"
    }
    val healthTone = when (state.availability) {
        HealthAvailability.Available -> SpectraStatusTone.SUCCESS
        is HealthAvailability.MissingPermissions -> SpectraStatusTone.WARNING
        HealthAvailability.NeedsProvider, HealthAvailability.Unsupported -> SpectraStatusTone.ERROR
        null -> SpectraStatusTone.INFO
    }
    SpectraSurface(
        modifier = Modifier.fillMaxWidth(),
        mood = PageMood.HEALTH,
        emphasized = true,
        shadowed = false,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Health Connect", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                SpectraStatus(healthLabel, tone = healthTone)
            }
            Text(
                "权限 ${state.grantedPermissionCount}/${state.requiredPermissionCount}" +
                    (state.snapshot?.lastSyncAt?.let { " · 最后同步 ${healthStatusTime(it)}" } ?: " · 暂无可验证同步时间"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(.66f),
            )
            state.snapshot?.let { snapshot ->
                val summary = buildList {
                    snapshot.metrics.steps?.let { add("今日 $it 步") }
                    snapshot.metrics.heartRateAverageBpm?.let { add("平均心率 $it bpm") }
                    snapshot.metrics.sleepMinutes?.let { add("睡眠 $it 分钟") }
                }
                if (summary.isNotEmpty()) Text(summary.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "来源：${snapshot.originPackages.takeIf { it.isNotEmpty() }?.joinToString() ?: "尚未观测到数据源"} · ${healthFreshnessLabel(snapshot.freshness)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(.56f),
                )
            }
            state.healthError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpectraAction("刷新", onRefresh, Modifier.weight(1f), enabled = !state.loading, mood = PageMood.HEALTH)
                SpectraAction("授权", onPermissions, Modifier.weight(1f), mood = PageMood.HEALTH)
            }
            Text(
                "Health Connect 只读取用户已授权的历史健康记录；缺失指标保持为未知，不会回退显示为 0。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(.52f),
            )
        }
    }
}

@Composable
private fun MiFitnessCloudStatus(
    state: CaesarHealthUiState,
    onRefresh: () -> Unit,
) {
    val snapshot = state.snapshot
    val cloudSnapshot = snapshot?.takeIf {
        com.campusai.core.health.mifitness.MiFitnessSummaryHealthGateway.SOURCE_ID in it.originPackages
    }
    val failed = state.miFitnessStatus in setOf(
        MiFitnessUiStatus.NO_DATA,
        MiFitnessUiStatus.AUTH_ERROR,
        MiFitnessUiStatus.NETWORK_ERROR,
        MiFitnessUiStatus.STORAGE_ERROR,
    )
    val cloudMetricSummary = buildList {
        cloudSnapshot?.metrics?.steps?.let { add("步数 $it 步") }
        cloudSnapshot?.metrics?.distanceMeters?.let { add("距离 ${it.roundToInt()} 米") }
        cloudSnapshot?.metrics?.activeCaloriesKcal?.let { add("消耗 ${it.roundToInt()} 千卡") }
        cloudSnapshot?.metrics?.activityDurationMinutes?.let { add("活动 $it 分钟") }
        cloudSnapshot?.metrics?.sleepMinutes?.let { add("睡眠 $it 分钟") }
        cloudSnapshot?.metrics?.heartRateAverageBpm?.let { add("平均心率 $it bpm") }
        cloudSnapshot?.metrics?.oxygenSaturationAveragePercent?.let { add("平均血氧 ${it.roundToInt()}%") }
        cloudSnapshot?.metrics?.stressAverage?.let { add("平均压力 $it") }
        cloudSnapshot?.metrics?.workoutCount?.let { add("训练 $it 次") }
    }
    SpectraSurface(
        modifier = Modifier.fillMaxWidth(),
        mood = PageMood.HEALTH,
        emphasized = true,
        shadowed = false,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mi Fitness", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "今日健康",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(.52f),
                    )
                }
                SpectraStatus(
                    when {
                        state.miFitnessSyncing -> "正在同步"
                        state.miFitnessStatus == MiFitnessUiStatus.NO_DATA -> "今天暂无记录"
                        failed && cloudSnapshot != null -> "缓存可用 · 刷新失败"
                        failed -> "刷新失败"
                        cloudSnapshot != null -> "已更新"
                        else -> "待同步"
                    },
                    tone = when {
                        state.miFitnessStatus == MiFitnessUiStatus.NO_DATA -> SpectraStatusTone.WARNING
                        failed -> SpectraStatusTone.ERROR
                        cloudSnapshot != null -> SpectraStatusTone.SUCCESS
                        else -> SpectraStatusTone.INFO
                    },
                )
            }
            if (cloudMetricSummary.isNotEmpty()) {
                Text(cloudMetricSummary.joinToString(" · "), style = MaterialTheme.typography.titleMedium)
            } else Text(
                "今天还没有可显示的健康数据。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(.66f),
            )
            cloudSnapshot?.lastSyncAt?.let {
                Text(
                    "更新 ${healthStatusTime(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(.56f),
                )
            }
            if (failed) {
                Text(
                    when (state.miFitnessStatus) {
                        MiFitnessUiStatus.NO_DATA -> "今天还没有同步到健康数据。"
                        MiFitnessUiStatus.AUTH_ERROR -> "身份验证失败，请在个人页更新凭据。"
                        MiFitnessUiStatus.NETWORK_ERROR -> "网络异常，请稍后重试。"
                        MiFitnessUiStatus.STORAGE_ERROR -> "系统安全存储暂不可用。"
                        else -> "本次刷新未完成。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.actionMessage?.takeUnless { failed }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = SpectraColors.Success)
            }
            SpectraAction(
                text = if (state.miFitnessSyncing) "正在同步" else "同步今日健康",
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.loading && !state.miFitnessSyncing,
                mood = PageMood.HEALTH,
            )
            Text(
                "CampusAI 不连接手环，也不需要 Health Connect 权限。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(.52f),
            )
        }
    }
}

private fun healthFreshnessLabel(value: HealthFreshness): String = when (value) {
    HealthFreshness.LIVE -> "实时"
    HealthFreshness.FRESH -> "新鲜"
    HealthFreshness.STALE -> "已过期"
    HealthFreshness.UNKNOWN -> "新鲜度未知"
}

private fun healthStatusTime(value: Long): String = runCatching {
    DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()))
}.getOrDefault("未知")

@Composable
private fun ContextToggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SpectraSegmentedControl(
    labels: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    motionEnabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var widthPx by remember { mutableFloatStateOf(0f) }
    val safeIndex = selectedIndex.coerceIn(labels.indices)
    val haptic = LocalHapticFeedback.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .35f
    var dragging by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var visualIndex by remember { mutableFloatStateOf(safeIndex.toFloat()) }
    val slotWidth = if (labels.isEmpty()) 0f else widthPx / labels.size
    val target = slotWidth * safeIndex
    val indicatorX by animateFloatAsState(
        targetValue = target,
        animationSpec = if (motionEnabled) spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow) else tween(0),
        label = "spectra-tab-indicator",
    )
    val selectedVisualIndex = if (dragging) visualIndex.roundToInt().coerceIn(labels.indices) else safeIndex
    GlassPanel(
        modifier.height(if (compact) 46.dp else 52.dp),
        radius = if (compact) 23 else 26,
        emphasized = false,
        shadowed = false,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { widthPx = it.width.toFloat() }
                .pointerInput(enabled, labels, widthPx) {
                    if (!enabled || labels.size < 2 || widthPx <= 0f) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragging = true
                            dragX = indicatorX
                            visualIndex = safeIndex.toFloat()
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            dragX = (dragX + amount).coerceIn(0f, widthPx - slotWidth)
                            val next = (dragX / slotWidth).roundToInt().coerceIn(labels.indices)
                            if (next != visualIndex.roundToInt()) {
                                visualIndex = next.toFloat()
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onDragEnd = {
                            val next = visualIndex.roundToInt().coerceIn(labels.indices)
                            dragX = next * slotWidth
                            onSelect(next)
                            dragging = false
                        },
                        onDragCancel = {
                            dragX = target
                            visualIndex = safeIndex.toFloat()
                            dragging = false
                        },
                    )
                },
        ) {
            if (labels.isNotEmpty()) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(1f / labels.size)
                        .padding(4.dp)
                        .graphicsLayer { translationX = if (dragging) dragX else indicatorX }
                        .background(
                            Brush.horizontalGradient(
                                if (dark) listOf(
                                    SpectraColors.Ink.copy(.88f),
                                    SpectraColors.Violet.copy(.72f),
                                    SpectraColors.Ink.copy(.88f),
                                ) else listOf(
                                    Color.White.copy(.18f),
                                    SpectraColors.Cyan.copy(.17f),
                                    SpectraColors.Violet.copy(.15f),
                                    SpectraColors.Rose.copy(.11f),
                                    Color.White.copy(.16f),
                                ),
                            ),
                            CircleShape,
                        )
                        .then(
                            Modifier.background(
                                Brush.verticalGradient(listOf(Color.White.copy(.18f), Color.Transparent)),
                                CircleShape,
                            ),
                        )
                        .border(
                            1.dp,
                            Brush.horizontalGradient(
                                listOf(
                                    SpectraColors.Cyan.copy(if (dark) .52f else .32f),
                                    Color.White.copy(if (dark) .24f else .58f),
                                    SpectraColors.Violet.copy(if (dark) .46f else .28f),
                                    SpectraColors.Warm.copy(if (dark) .34f else .20f),
                                ),
                            ),
                            CircleShape,
                        ),
                )
            }
            Row(Modifier.fillMaxSize()) {
                labels.forEachIndexed { index, label ->
                    val selected = index == selectedVisualIndex
                    val interactionSource = remember(label) { MutableInteractionSource() }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .semantics { role = Role.Tab; this.selected = selected }
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                enabled = enabled,
                            ) { onSelect(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) {
                                if (dark) Color.White.copy(if (enabled || labels.size == 1) 1f else .72f)
                                else SpectraColors.Ink.copy(if (enabled || labels.size == 1) 1f else .58f)
                            } else MaterialTheme.colorScheme.onSurface.copy(if (enabled) .62f else .38f),
                            maxLines = 1,
                            fontSize = if (compact) 12.sp else 14.sp,
                        )
                    }
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                drawLine(
                    Brush.horizontalGradient(listOf(Color.Transparent, SpectraColors.Cyan.copy(.62f), SpectraColors.Violet.copy(.54f), SpectraColors.Warm.copy(.48f), Color.Transparent)),
                    start = Offset(size.width * .08f, 1.dp.toPx()),
                    end = Offset(size.width * .92f, 1.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
    }
}

@Composable
private fun QuietEmptyState(
    label: String,
    motionEnabled: Boolean,
    preset: AiVisualPreset,
    modifier: Modifier = Modifier,
    headline: String? = null,
) {
    if (preset == AiVisualPreset.CLASSIC || headline == null) {
        Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LiquidMetalOrb(active = false, motionEnabled = motionEnabled, size = 56.dp)
            Spacer(Modifier.size(12.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(.50f))
        }
        return
    }
    Column(
        modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(headline, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface.copy(.90f))
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiquidMetalOrb(active = false, motionEnabled = motionEnabled, size = 34.dp)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(.56f))
        }
    }
}

@Composable
private fun StreamingStatus(motionEnabled: Boolean, preset: AiVisualPreset) {
    Row(
        modifier = Modifier.padding(vertical = if (preset == AiVisualPreset.FLUID) 4.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LiquidMetalOrb(
            active = true,
            motionEnabled = motionEnabled,
            size = if (preset == AiVisualPreset.FLUID) 30.dp else 40.dp,
        )
        Column {
            Text(
                if (preset == AiVisualPreset.FLUID) "Caesar∞ 正在整理" else "思考中",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(.68f),
            )
            ThinkingMarquee(motionEnabled)
        }
    }
}

@Composable
private fun LiquidMetalOrb(active: Boolean, motionEnabled: Boolean, size: androidx.compose.ui.unit.Dp) {
    val phase = if (motionEnabled) {
        val transition = rememberInfiniteTransition(label = "liquid-metal-orb")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(if (active) 2_200 else 5_200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "liquid-metal-caustic",
        )
        animated
    } else .114f
    Canvas(Modifier.size(size)) {
        val t = phase * PI * 2.0
        val inset = 1.25.dp.toPx()
        val radius = (this.size.minDimension * .5f - inset).coerceAtLeast(1f)
        val cx = center.x
        val cy = center.y
        val sphere = Path().apply { addOval(Rect(cx - radius, cy - radius, cx + radius, cy + radius)) }
        val light = Offset(cx - radius * .35f, cy - radius * .38f)
        drawCircle(
            Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color.White,
                    .18f to Color(0xFFF5F8FC),
                    .42f to Color(0xFFB8C3D0),
                    .68f to Color(0xFF6D7B8D),
                    .86f to Color(0xFFDCE3EB),
                    1f to Color(0xFF8794A5),
                ),
                center = light,
                radius = radius * 1.72f,
            ),
            radius = radius,
            center = center,
        )
        clipPath(sphere) {
            val bandY = cy + sin(t * .72).toFloat() * radius * .13f
            val liquid = Path().apply {
                moveTo(cx - radius * 1.20f, bandY)
                cubicTo(cx - radius * .70f, bandY - radius * .32f, cx - radius * .08f, bandY + radius * .28f, cx + radius * .36f, bandY - radius * .12f)
                cubicTo(cx + radius * .72f, bandY - radius * .42f, cx + radius, bandY + radius * .06f, cx + radius * 1.20f, bandY - radius * .08f)
                lineTo(cx + radius * 1.20f, cy + radius * 1.20f)
                lineTo(cx - radius * 1.20f, cy + radius * 1.20f)
                close()
            }
            drawPath(
                liquid,
                Brush.verticalGradient(
                    listOf(Color(0xFF657489).copy(.12f), Color(0xFF273548).copy(.34f), Color(0xFFABB8C7).copy(.42f), Color.White.copy(.34f)),
                    startY = bandY - radius * .20f,
                    endY = cy + radius,
                ),
            )
            val innerX = cx + sin(t * .77).toFloat() * radius * .25f
            val innerY = cy + cos(t * .63).toFloat() * radius * .20f
            drawOval(
                Brush.radialGradient(
                    listOf(Color.White.copy(.72f), SpectraColors.Cyan.copy(.30f), SpectraColors.Violet.copy(.28f), Color.Transparent),
                    center = Offset(innerX - radius * .12f, innerY - radius * .08f),
                    radius = radius * .62f,
                ),
                topLeft = Offset(innerX - radius * .58f, innerY - radius * .34f),
                size = Size(radius * 1.16f, radius * .68f),
            )
            drawOval(
                Brush.radialGradient(listOf(Color.White.copy(.92f), Color.White.copy(.20f), Color.Transparent), center = light, radius = radius * .48f),
                topLeft = Offset(light.x - radius * .48f, light.y - radius * .31f),
                size = Size(radius * .96f, radius * .62f),
            )
            val caustic = Path().apply {
                moveTo(cx - radius * .76f, cy + radius * (.18f + sin(t).toFloat() * .07f))
                cubicTo(cx - radius * .28f, cy - radius * .10f, cx + radius * .18f, cy + radius * .34f, cx + radius * .76f, cy - radius * .16f)
            }
            drawPath(
                caustic,
                Brush.horizontalGradient(
                    listOf(SpectraColors.Cyan.copy(.12f), SpectraColors.Cyan.copy(.86f), Color.White, SpectraColors.Violet.copy(.76f), SpectraColors.Warm.copy(.48f), Color.Transparent),
                ),
                style = Stroke(width = (this.size.minDimension * .032f).coerceAtLeast(1.dp.toPx()), cap = StrokeCap.Round),
            )
            repeat(if (active) 24 else 16) { index ->
                val seed = index * 2.399f + t.toFloat() * (.42f + (index % 4) * .035f)
                val distance = radius * (.12f + (index % 7) * .105f)
                val point = Offset(cx + cos(seed) * distance, cy + sin(seed * 1.17f) * distance * .78f)
                drawCircle(
                    color = when (index % 5) {
                        0 -> SpectraColors.Cyan.copy(.68f)
                        1 -> SpectraColors.Violet.copy(.50f)
                        2 -> SpectraColors.Warm.copy(.34f)
                        else -> Color.White.copy(.30f + (index % 3) * .12f)
                    },
                    radius = (.24f + (index % 3) * .10f).dp.toPx(),
                    center = point,
                )
            }
        }
        drawCircle(
            Brush.linearGradient(listOf(Color.White.copy(.92f), Color(0xFFD7E0EB).copy(.72f), Color(0xFF8593A5).copy(.54f), Color.White.copy(.42f))),
            radius = radius,
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

@Composable
private fun ThinkingMarquee(motionEnabled: Boolean) {
    val trackColor = MaterialTheme.colorScheme.onSurface
    val phase = if (motionEnabled) {
        val transition = rememberInfiniteTransition(label = "thinking-silver-signal")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_650, easing = LinearEasing), RepeatMode.Reverse),
            label = "thinking-silver-signal-phase",
        )
        animated
    } else .5f
    Canvas(Modifier.width(116.dp).height(10.dp).padding(top = 5.dp)) {
        val trackHeight = 1.5.dp.toPx()
        val beadWidth = 30.dp.toPx().coerceAtMost(size.width * .42f)
        val beadX = (size.width - beadWidth) * phase
        drawRoundRect(
            trackColor.copy(.10f),
            size = Size(size.width, trackHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight),
        )
        drawRoundRect(
            Brush.horizontalGradient(
                listOf(Color.Transparent, Color.White.copy(.86f), SpectraColors.Silver.copy(.72f), Color.Transparent),
                startX = beadX,
                endX = beadX + beadWidth,
            ),
            topLeft = Offset(beadX, 0f),
            size = Size(beadWidth, trackHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight),
        )
    }
}

@Composable
private fun AiComposer(
    value: String,
    onValueChange: (String) -> Unit,
    streaming: Boolean,
    motionEnabled: Boolean,
    preset: AiVisualPreset,
    images: List<CaesarImageAttachment>,
    importingImage: Boolean,
    onPickImage: () -> Unit,
    onCamera: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    listening: Boolean,
    onVoice: () -> Unit,
    onAction: () -> Unit,
) {
    val common = AiComposerCallbacks(
        onValueChange = onValueChange,
        onPickImage = onPickImage,
        onCamera = onCamera,
        onRemoveImage = onRemoveImage,
        onVoice = onVoice,
        onAction = onAction,
    )
    if (preset == AiVisualPreset.CLASSIC) {
        ClassicAiComposer(value, streaming, motionEnabled, images, importingImage, listening, common)
    } else {
        FluidAiComposer(value, streaming, motionEnabled, images, importingImage, listening, common)
    }
}

private data class AiComposerCallbacks(
    val onValueChange: (String) -> Unit,
    val onPickImage: () -> Unit,
    val onCamera: () -> Unit,
    val onRemoveImage: (Int) -> Unit,
    val onVoice: () -> Unit,
    val onAction: () -> Unit,
)

@Composable
internal fun ComposerAttachments(
    images: List<CaesarImageAttachment>,
    importingImage: Boolean,
    onRemoveImage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty() && !importingImage) return
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        images.forEachIndexed { index, image ->
            Box(Modifier.size(64.dp)) {
                AsyncImage(
                    model = File(image.localPath),
                    contentDescription = "待发送图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                )
                IconButton(
                    onClick = { onRemoveImage(index) },
                    modifier = Modifier.align(Alignment.TopEnd).size(48.dp),
                ) {
                    Icon(Icons.Rounded.Close, "移除图片", tint = Color.White, modifier = Modifier.size(32.dp).background(Color.Black.copy(.68f), CircleShape).padding(7.dp))
                }
            }
        }
        if (importingImage) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun ClassicAiComposer(
    value: String,
    streaming: Boolean,
    motionEnabled: Boolean,
    images: List<CaesarImageAttachment>,
    importingImage: Boolean,
    listening: Boolean,
    callbacks: AiComposerCallbacks,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .35f
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ComposerAttachments(
            images = images,
            importingImage = importingImage,
            onRemoveImage = callbacks.onRemoveImage,
            modifier = Modifier.fillMaxWidth(),
        )
        GlassPanel(
            Modifier.fillMaxWidth().heightIn(min = 58.dp),
            radius = 28,
            emphasized = true,
            shadowed = false,
            opticalPriority = 5,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ClassicMediaAction(
                    icon = Icons.Rounded.Image,
                    label = "照片",
                    enabled = !streaming && !importingImage && images.size < 4,
                    selected = false,
                    onClick = callbacks.onPickImage,
                    modifier = Modifier.weight(1f),
                )
                ClassicMediaAction(
                    icon = Icons.Rounded.CameraAlt,
                    label = "拍照",
                    enabled = !streaming && !importingImage && images.size < 4,
                    selected = false,
                    onClick = callbacks.onCamera,
                    modifier = Modifier.weight(1f),
                )
                ClassicMediaAction(
                    icon = Icons.Rounded.Mic,
                    label = if (listening) "结束" else "语音",
                    enabled = !streaming,
                    selected = listening,
                    onClick = callbacks.onVoice,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f).heightIn(min = 58.dp, max = 132.dp)) {
                GlassPanel(
                    Modifier.fillMaxWidth(),
                    radius = 29,
                    emphasized = true,
                    shadowed = false,
                    opticalPriority = 6,
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = callbacks.onValueChange,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 17.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(SpectraColors.Focus),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (!streaming && value.isNotBlank()) callbacks.onAction() }),
                        decorationBox = { inner ->
                            Box {
                                if (value.isEmpty()) Text("想聊些什么？", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                inner()
                            }
                        },
                    )
                }
                ComposerLoaderVisibility(streaming, motionEnabled, Modifier.matchParentSize())
            }
            GlassPanel(
                Modifier.size(56.dp),
                radius = 28,
                emphasized = true,
                shadowed = true,
                opticalPriority = 5,
            ) {
                IconButton(
                    enabled = streaming || value.isNotBlank() || images.isNotEmpty(),
                    onClick = callbacks.onAction,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Icon(
                        if (streaming) Icons.Rounded.StopCircle else Icons.AutoMirrored.Rounded.Send,
                        if (streaming) "停止生成" else "发送",
                        tint = (if (dark) Color.White else SpectraColors.Ink).copy(if (streaming || value.isNotBlank() || images.isNotEmpty()) 1f else .38f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ClassicMediaAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember(label) { MutableInteractionSource() }
    Row(
        modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) SpectraColors.Focus.copy(.12f) else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(.28f)
                selected -> SpectraColors.Focus
                else -> MaterialTheme.colorScheme.onSurface.copy(.78f)
            },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(.28f)
                selected -> SpectraColors.Focus
                else -> MaterialTheme.colorScheme.onSurface.copy(.72f)
            },
        )
    }
}

@Composable
private fun FluidAiComposer(
    value: String,
    streaming: Boolean,
    motionEnabled: Boolean,
    images: List<CaesarImageAttachment>,
    importingImage: Boolean,
    listening: Boolean,
    callbacks: AiComposerCallbacks,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .35f
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        GlassPanel(
            Modifier.fillMaxWidth(),
            radius = 28,
            emphasized = true,
            shadowed = true,
            opticalPriority = 10,
        ) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                ComposerAttachments(
                    images = images,
                    importingImage = importingImage,
                    onRemoveImage = callbacks.onRemoveImage,
                    modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 5.dp),
                )
                BasicTextField(
                    value = value,
                    onValueChange = callbacks.onValueChange,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp, max = 112.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(SpectraColors.Focus),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (!streaming && value.isNotBlank()) callbacks.onAction() }),
                    decorationBox = { inner ->
                        Box {
                            if (value.isEmpty()) Text("想聊些什么？", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            inner()
                        }
                    },
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ComposerToolAction(
                        icon = Icons.Rounded.Image,
                        label = "照片",
                        enabled = !streaming && !importingImage && images.size < 4,
                        selected = false,
                        onClick = callbacks.onPickImage,
                    )
                    ComposerToolAction(
                        icon = Icons.Rounded.CameraAlt,
                        label = "拍照",
                        enabled = !streaming && !importingImage && images.size < 4,
                        selected = false,
                        onClick = callbacks.onCamera,
                    )
                    ComposerToolAction(
                        icon = Icons.Rounded.Mic,
                        label = if (listening) "结束转写" else "语音",
                        enabled = !streaming,
                        selected = listening,
                        onClick = callbacks.onVoice,
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (dark) Color.White.copy(.92f) else SpectraColors.Ink.copy(.94f)),
                    ) {
                        IconButton(
                            enabled = streaming || value.isNotBlank() || images.isNotEmpty(),
                            onClick = callbacks.onAction,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Icon(
                                if (streaming) Icons.Rounded.StopCircle else Icons.AutoMirrored.Rounded.Send,
                                if (streaming) "停止生成" else "发送",
                                tint = (if (dark) SpectraColors.Ink else Color.White).copy(if (streaming || value.isNotBlank() || images.isNotEmpty()) 1f else .42f),
                            )
                        }
                    }
                }
            }
        }
        ComposerLoaderVisibility(streaming, motionEnabled, Modifier.matchParentSize())
    }
}

@Composable
private fun ComposerToolAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember(label) { MutableInteractionSource() }
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                if (selected) SpectraColors.Focus.copy(.14f)
                else Color.Transparent,
            )
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            label,
            modifier = Modifier.size(20.dp),
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(.30f)
                selected -> SpectraColors.Focus
                else -> MaterialTheme.colorScheme.onSurface.copy(.78f)
            },
        )
    }
}

@Composable
private fun ThinkingStatusVisibility(
    visible: Boolean,
    motionEnabled: Boolean,
    preset: AiVisualPreset,
) {
    AnimatedVisibility(visible = visible, exit = fadeOut(tween(160))) {
        StreamingStatus(motionEnabled, preset)
    }
}

@Composable
private fun ComposerLoaderVisibility(
    visible: Boolean,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(modifier = modifier, visible = visible, exit = fadeOut(tween(160))) {
        ComposerLoaderEdge(motionEnabled, Modifier.fillMaxSize())
    }
}

@Composable
private fun ComposerLoaderEdge(motionEnabled: Boolean, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.onSurface
    val phase = if (motionEnabled) {
        val transition = rememberInfiniteTransition(label = "composer-silver-seam")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing), RepeatMode.Reverse),
            label = "composer-silver-seam-phase",
        )
        animated
    } else .5f
    Canvas(modifier) {
        val inset = 16.dp.toPx()
        val seamHeight = 2.dp.toPx()
        val trackWidth = (size.width - inset * 2f).coerceAtLeast(1f)
        val beadWidth = (48.dp.toPx()).coerceAtMost(trackWidth * .44f)
        val beadX = inset + (trackWidth - beadWidth) * phase
        val y = size.height - 3.dp.toPx()
        drawRoundRect(
            trackColor.copy(.08f),
            topLeft = Offset(inset, y),
            size = Size(trackWidth, seamHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(seamHeight),
        )
        drawRoundRect(
            Brush.horizontalGradient(
                listOf(Color.Transparent, Color.White.copy(.94f), SpectraColors.Silver.copy(.78f), Color.Transparent),
                startX = beadX,
                endX = beadX + beadWidth,
            ),
            topLeft = Offset(beadX, y),
            size = Size(beadWidth, seamHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(seamHeight),
        )
    }
}

@Composable
private fun AiErrorCard(state: AiUiState, error: String, onCloudOnce: (AiProvider) -> Unit) {
    GlassPanel(Modifier.fillMaxWidth(), radius = 16) {
        Column(Modifier.padding(14.dp)) {
            Text("这次没有完成", style = MaterialTheme.typography.titleMedium, color = SpectraColors.Error)
            Spacer(Modifier.size(4.dp))
            Text(error, style = MaterialTheme.typography.bodyMedium)
            Text(
                when (state.errorCode) {
                    "local_model_not_ready", "offline_model_missing", "local_required" -> "前往“我的 → AI 运行方式”查看当前锁定档位；切换档位后需新建会话。"
                    "personal_key_missing" -> "前往“我的 → AI 运行方式”安全保存自己的 DeepSeek Key。"
                    "codex_key_missing" -> "前往“我的 → AI 运行方式”设置 Codex Base URL 并安全保存 Key。"
                    "local_output_rejected" -> "Caesar∞ 没有展示内部过程。可以重试，或切换到 DeepSeek。"
                    else -> "根据上面的原因恢复后可以重试。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(.62f),
            )
            if (state.canUseCloudOnce) {
                state.cloudOnceProviders.forEach { provider ->
                    TextButton(onClick = { onCloudOnce(provider) }) {
                        Text(
                            when (provider) {
                                AiProvider.DEEPSEEK -> "确认：本次使用我的 DeepSeek Key"
                                AiProvider.GOOGLE_GEMINI -> "确认：本次使用我的 Gemini Key"
                                AiProvider.CODEX -> "确认：本次使用我的 Codex 服务"
                                else -> ""
                            },
                        )
                    }
                }
            }
        }
    }
}

private data class AiTaskOption(val task: CampusAiTask, val title: String, val detail: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiTaskSheet(promptAvailable: Boolean, onDismiss: () -> Unit, onSelect: (CampusAiTask) -> Unit) {
    val options = remember {
        listOf(
            AiTaskOption(CampusAiTask.HOME_INSIGHT, "今日洞察", "一句结论和一个行动", Icons.Rounded.Today),
            AiTaskOption(CampusAiTask.TODAY_SUMMARY, "今日总结", "复盘今天的投入和目标", Icons.AutoMirrored.Rounded.EventNote),
            AiTaskOption(CampusAiTask.WEEK_SUMMARY, "本周总结", "查看趋势并规划下周", Icons.Rounded.DateRange),
            AiTaskOption(CampusAiTask.MONTH_SUMMARY, "本月总结", "回顾本月的学习节奏", Icons.Rounded.CalendarMonth),
            AiTaskOption(CampusAiTask.SCHEDULE_CLEANUP, "课程整理", "整理字段并说明已计算冲突", Icons.Rounded.EditCalendar),
            AiTaskOption(CampusAiTask.TIME_PARSE, "解析记录", "把输入框内容整理成时间记录", Icons.Rounded.AutoAwesome),
        )
    }
    SpectraModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("快捷任务", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.size(12.dp))
            GlassPanel(Modifier.fillMaxWidth(), radius = 16, emphasized = true) {
                Column {
                    options.forEachIndexed { index, option ->
                        val enabled = option.task != CampusAiTask.TIME_PARSE || promptAvailable
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = enabled) { onSelect(option.task) }.padding(horizontal = 15.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(option.icon, null, tint = if (enabled) SpectraColors.Violet else MaterialTheme.colorScheme.onSurface.copy(.28f))
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(option.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.copy(if (enabled) 1f else .42f))
                                Text(if (!enabled) "先在输入框写下要解析的记录" else option.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(.58f))
                            }
                        }
                        if (index != options.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.09f))
                    }
                }
            }
        }
    }
}

private fun plainAiText(value: String): String = value
    .replace("**", "")
    .replace("__", "")
    .replace(Regex("(?m)^\\s*#{1,6}\\s+"), "")
    .replace(Regex("(?m)^\\s*[-*]\\s+"), "• ")

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val SPEECH_LOG_TAG = "CaesarSpeech"

private fun caesarSpeechRecognitionIntent(preferOffline: Boolean): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }
