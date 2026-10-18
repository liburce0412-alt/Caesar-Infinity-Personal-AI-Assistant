package com.campusai.features.ai

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import com.campusai.core.designsystem.CaesarGlassOrb
import com.campusai.core.designsystem.BrandWordmark
import com.campusai.core.designsystem.CaesarOrbSignal
import com.campusai.core.designsystem.CaesarOrbMode
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campusai.core.designsystem.GlassPanel
import com.campusai.core.designsystem.CaesarSlidingSelector
import com.campusai.core.designsystem.SpectraAction
import com.campusai.core.designsystem.SpectraAlertDialog
import com.campusai.core.designsystem.SpectraColors
import com.campusai.core.designsystem.SpectraModalBottomSheet
import com.campusai.core.designsystem.SpectraTheme
import com.campusai.core.designsystem.SpectraVisualStyle
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
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.util.UUID

internal enum class AiVisualPreset(val label: String) {
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
    onOrbInteractionChanged: (CaesarOrbMode?) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val selectedLocalModel by viewModel.localModelSelection.collectAsState()
    val localModelStates by viewModel.localModelStates.collectAsState()
    val history by viewModel.history.collectAsState()
    val historyLimit by viewModel.visibleHistoryLimit.collectAsState()
    val viewingLocalHistory by viewModel.viewingLocalHistory.collectAsState()
    val memories by viewModel.memories.collectAsState()
    val memoryScope by viewModel.memoryScope.collectAsState()
    val viewingLocalMemories by viewModel.viewingLocalMemories.collectAsState()
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
    val orbSignal = remember { CaesarOrbSignal() }
    val playback = rememberAiSpeechPlayback()
    val stopPlayback: () -> Unit = playback::stop
    var orbAnchor by remember { mutableStateOf(Rect.Zero) }
    var orbRoot by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val speech = rememberAiSpeechInput(
        ownerId = snapshot.userId,
        blocked = state.streaming || showHistory,
        orbSignal = orbSignal,
        onTranscript = { prompt = it },
        onBeforeStart = { playback.message = null; stopPlayback() },
        onPaused = {
            if (playback.activeUtterance != null) playback.message = "朗读已停止"
            stopPlayback()
        },
        onNotice = { message -> scope.launch { snackbarHost.showSnackbar(message) } },
    )
    val listening = speech.listening
    val transcribing = speech.transcribing
    val voiceMessage = speech.message
    val currentSpeech by rememberUpdatedState(speech)
    LaunchedEffect(playback) {
        AiIslandNotifications.stopRequests.collect { mode ->
            if (mode == CaesarOrbMode.SPEAKING && playback.activeUtterance != null) {
                stopPlayback()
                playback.message = "朗读已停止"
            } else if (mode in setOf(CaesarOrbMode.LISTENING, CaesarOrbMode.TRANSCRIBING) &&
                (currentSpeech.listening || currentSpeech.transcribing)) {
                currentSpeech.cancel("语音已结束")
            }
        }
    }
    val orbPresentation = aiOrbPresentation(state, listening, transcribing, playback.speaking, voiceMessage, playback.message)
    val interactionMode = orbPresentation.mode.takeIf {
        listening || transcribing || playback.activeUtterance != null || playback.message != null || voiceMessage != null
    }
    LaunchedEffect(interactionMode) { onOrbInteractionChanged(interactionMode) }
    DisposableEffect(Unit) { onDispose { onOrbInteractionChanged(null) } }
    val voiceOrb = listening || transcribing
    val emptyConversation = state.messages.none { it.content.isNotBlank() || it.attachmentPaths.isNotEmpty() } && state.error == null && !state.streaming
    val heroOrb = emptyConversation && !voiceOrb && !playback.speaking && voiceMessage == null && playback.message == null
    val captureOrbAnchor: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit = { orbAnchor = it.boundsInWindow() }

    RefreshHealthStatusWhenVisible(showContext || showRuntime, viewModel::refreshHealthStatus)

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
            )
            .onGloballyPositioned { orbRoot = it.boundsInWindow().topLeft },
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
                    onNewConversation = {
                        speech.cancel(null)
                        stopPlayback(); playback.message = null
                        viewModel.newConversation()
                    },
                )
            } else {
                FluidAiHeader(
                    runtimeLabel = runtimeSummary(state.provider, state.model, localModel),
                    showHistory = showHistory,
                    streaming = state.streaming,
                    onBack = onBack,
                    onRuntime = { showRuntime = true },
                    onHistory = { showHistory = !showHistory },
                    onNewConversation = {
                        speech.cancel(null)
                        stopPlayback(); playback.message = null
                        viewModel.newConversation()
                    },
                )
            }

            if (!showHistory && !heroOrb && !voiceOrb) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = SpectraTheme.layout.pageHorizontalPadding, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.size(44.dp).onGloballyPositioned(captureOrbAnchor))
                    Text(orbPresentation.label.takeUnless { orbPresentation.mode == CaesarOrbMode.COMPLETE }.orEmpty(), Modifier.weight(1f).padding(start = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (playback.speaking) TextButton(onClick = { stopPlayback(); playback.message = "已停止朗读" }) { Text("停止朗读") }
                }
            }
            if (showHistory) {
                if (snapshot.userId.isNotBlank()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    TextButton(onClick = { viewModel.showLocalHistory(false) }, enabled = viewingLocalHistory) { Text("此账号") }
                    TextButton(onClick = { viewModel.showLocalHistory(true) }, enabled = !viewingLocalHistory) { Text("本机旧记录") }
                }
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
                    items(history.take(historyLimit), key = { it.id }) { report ->
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
                                    scope.launch {
                                        if (viewModel.deleteConversation(report)) {
                                            if (snackbarHost.showSnackbar("已删除对话", "撤销") == SnackbarResult.ActionPerformed) {
                                                if (!viewModel.undoDeleteConversation(report.id)) {
                                                    snackbarHost.showSnackbar("未能恢复对话，请查看错误提示。")
                                                }
                                            }
                                        } else if (viewModel.state.value.errorCode == "history_delete_failed") {
                                            snackbarHost.showSnackbar("删除失败，请重试。")
                                        }
                                    }
                                }) { Icon(Icons.Rounded.DeleteOutline, "删除对话", tint = MaterialTheme.colorScheme.onSurface.copy(.56f)) }
                            }
                        }
                    }
                    if (history.size > historyLimit) item {
                        TextButton(onClick = viewModel::loadMoreHistory, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
                    }
                }
                SnackbarHost(
                    hostState = snackbarHost,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SpectraTheme.layout.pageHorizontalPadding, vertical = 6.dp),
                )
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (emptyConversation) {
                        QuietEmptyState(
                            if (localRouteSelected) localModelStatus(localModel) else selectedCloudProviderStatus(state.provider),
                            motionEnabled,
                            visualPreset,
                            Modifier.fillMaxSize(),
                            headline = "现在，想做什么？",
                            orbVisible = heroOrb,
                            onOrbPositioned = captureOrbAnchor,
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
                                                        if (playback.activeUtterance != null) {
                                                            stopPlayback(); playback.message = "已停止朗读"
                                                        } else if (!playback.ready) {
                                                            scope.launch { snackbarHost.showSnackbar("朗读引擎尚未就绪或缺少中文语音，请稍后重试。") }
                                                        } else {
                                                            speech.cancel(null)
                                                            playback.message = "正在准备朗读"
                                                            playback.speak(plainAiText(message.content))
                                                        }
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
                            state.error?.let { error -> item { AiErrorCard(state, error, viewModel::useCloudOnce) } }
                        }
                    }
                }
                SnackbarHost(
                    hostState = snackbarHost,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SpectraTheme.layout.pageHorizontalPadding, vertical = 4.dp),
                )
                if (voiceOrb) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center) {
                        Spacer(Modifier.size(92.dp).onGloballyPositioned(captureOrbAnchor))
                        Text(orbPresentation.label, Modifier.weight(1f).padding(start = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        if (transcribing) TextButton(onClick = {
                            speech.cancel(null)
                        }) { Text("取消") }
                    }
                }
                if (state.pendingImages.isNotEmpty() && state.provider == AiProvider.CAESAR_CLOUD_FREE) {
                    Text("当前图片将发送到 Caesar cloud free · GLM-4V-Flash（每次一张）",
                        Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
                    onVoice = speech.toggle,
                    onAction = {
                        if (state.streaming) viewModel.cancel()
                        else if (prompt.isNotBlank() || state.pendingImages.isNotEmpty()) {
                            speech.cancel(null)
                            stopPlayback(); playback.message = null
                            val submittedDraft = prompt
                            val value = prompt.ifBlank { "请分析这些图片。" }
                            viewModel.send(value, snapshot) {
                                if (prompt == submittedDraft) prompt = ""
                            }
                        }
                    },
                )
            }
        }
        if (!showHistory && orbAnchor.width > 0f) {
            CaesarGlassOrb(
                modifier = Modifier.offset { IntOffset((orbAnchor.left - orbRoot.x).roundToInt(), (orbAnchor.top - orbRoot.y).roundToInt()) },
                size = with(density) { orbAnchor.width.toDp() },
                mode = orbPresentation.mode, motionEnabled = motionEnabled, signal = orbSignal,
            )
        }
    }

    if (speech.showSystemConsent) {
        SpectraAlertDialog(
            onDismissRequest = speech.dismissSystemConsent,
            title = "使用系统语音识别？",
            message = "这台设备没有可用的端侧识别器。继续后，录音可能由系统语音服务联网处理。" +
                "Caesar∞ 不会将录音写入记忆或 Trace；本次进入 AI 页期间只询问一次。",
            confirmLabel = "同意并继续",
            dismissLabel = "取消",
            onConfirm = speech.confirmSystemConsent,
        )
    }

    if (speech.showPermissionRecovery) {
        SpectraAlertDialog(
            onDismissRequest = speech.dismissPermissionRecovery,
            title = "恢复麦克风权限",
            message = "麦克风权限已被关闭且系统不再显示授权弹窗。请在应用设置中将“麦克风”改为允许，返回后再点击语音。",
            confirmLabel = "打开设置",
            dismissLabel = "取消",
            onConfirm = {
                speech.dismissPermissionRecovery()
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
                    viewModel.sendTask(task, snapshot, value) {
                        if (prompt == value) prompt = ""
                    }
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
            memoryScopeKey = "${memoryScope.first}:${memoryScope.second}",
            viewingLocalMemories = viewingLocalMemories,
            onShowLocalMemories = viewModel::showLocalMemories,
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
): String = if (provider == AiProvider.CAESAR_CLOUD_FREE) when (resolvedModel) {
    "glm-4-flash-250414" -> "GLM-4-Flash-250414"
    "glm-4v-flash" -> "GLM-4V-Flash"
    else -> resolvedModel.takeIf(String::isNotBlank) ?: "Caesar cloud free"
} else resolvedModel.takeIf(String::isNotBlank) ?: when (provider) {
    AiProvider.CAESAR_CLOUD_FREE -> "Caesar cloud free"
    AiProvider.AUTO -> "自动 · ${localModel.mode.displayLabel()}优先"
    AiProvider.LOCAL -> "本机 · ${localModel.mode.displayLabel()}"
    AiProvider.DEEPSEEK -> "DeepSeek"
    AiProvider.GOOGLE_GEMINI -> "Google Gemini"
    AiProvider.CODEX -> "Codex · 私有服务"
}

private val AI_RUNTIME_PROVIDERS = listOf(
    AiProvider.CAESAR_CLOUD_FREE,
    AiProvider.AUTO,
    AiProvider.LOCAL,
    AiProvider.CODEX,
    AiProvider.DEEPSEEK,
    AiProvider.GOOGLE_GEMINI,
)

private fun providerIndex(provider: AiProvider): Int = AI_RUNTIME_PROVIDERS.indexOf(provider).coerceAtLeast(0)

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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandWordmark(width = 124.dp)
            Text(
                runtimeLabel,
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
                BrandWordmark(width = 136.dp)
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

    AiProviderPicker(provider, { onProviderSelected(providerIndex(it)) },
        Modifier.fillMaxWidth().padding(horizontal = SpectraTheme.layout.pageHorizontalPadding), enabled = !streaming)
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
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "回答与界面设置",
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                BrandWordmark()
                Text("AI 与界面", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            }
            Text("选择 AI", style = MaterialTheme.typography.labelLarge)
            AiProviderChoices(provider, !streaming) { onProviderSelected(providerIndex(it)) }
            if (provider == AiProvider.CAESAR_CLOUD_FREE || provider == AiProvider.DEEPSEEK || provider == AiProvider.GOOGLE_GEMINI || provider == AiProvider.CODEX) {
                AiCloudAnswerMode(provider, mode, !streaming, motionEnabled, onModeSelected)
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
            Text("界面预设", style = MaterialTheme.typography.labelLarge)
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
    AiProvider.CAESAR_CLOUD_FREE -> "Caesar cloud free"
    AiProvider.LOCAL -> "本地"
    AiProvider.DEEPSEEK -> "DeepSeek"
    AiProvider.GOOGLE_GEMINI -> "Gemini"
    AiProvider.CODEX -> "Codex"
    AiProvider.AUTO -> "自动"
}

internal fun selectedCloudProviderStatus(provider: AiProvider): String = when (provider) {
    AiProvider.CAESAR_CLOUD_FREE -> "Caesar cloud free"
    AiProvider.DEEPSEEK -> "DeepSeek"
    AiProvider.GOOGLE_GEMINI -> "Gemini"
    AiProvider.CODEX -> "Codex"
    AiProvider.LOCAL, AiProvider.AUTO -> "本机"
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
    DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.ofEpochMilli(value).atZone(com.campusai.core.time.CaesarTime.zone))
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
            if (surface.title.isBlank() || surface.title == "Caesar∞") BrandWordmark()
            else Text(surface.title, style = MaterialTheme.typography.titleLarge)
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
internal fun QuietEmptyState(
    label: String,
    motionEnabled: Boolean,
    preset: AiVisualPreset,
    modifier: Modifier = Modifier,
    headline: String? = null,
    orbVisible: Boolean = false,
    onOrbPositioned: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit = {},
) {
    if (headline == null) {
        Text(label, modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    BoxWithConstraints(modifier) {
        val orbSize = minOf(204.dp, maxWidth * .56f, maxHeight * .53f).coerceAtLeast(48.dp)
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.BiasAlignment(0f, if (maxHeight > 400.dp) -.28f else 0f)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (orbVisible) Spacer(Modifier.size(orbSize).onGloballyPositioned(onOrbPositioned))
            Spacer(Modifier.height(12.dp))
            Text(headline, style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(.90f))
            if (preset == AiVisualPreset.CLASSIC) {
                Spacer(Modifier.height(10.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        }
    }
}

@Composable
internal fun AiComposer(
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
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        val compact = maxWidth >= 340.dp && LocalDensity.current.fontScale <= 1.3f
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
                if (compact) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ComposerToolAction(Icons.Rounded.Image, "照片", !streaming && !importingImage && images.size < 4, false, callbacks.onPickImage)
                        BasicTextField(value, callbacks.onValueChange,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 112.dp).padding(vertical = 13.dp, horizontal = 4.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(SpectraColors.Focus), maxLines = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { if (!streaming && value.isNotBlank()) callbacks.onAction() }),
                            decorationBox = { inner ->
                                Box { if (value.isEmpty()) Text("想聊些什么？", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    inner() }
                            })
                        ComposerToolAction(Icons.Rounded.CameraAlt, "拍照", !streaming && !importingImage && images.size < 4, false, callbacks.onCamera)
                        ComposerToolAction(Icons.Rounded.Mic, if (listening) "结束转写" else "语音", !streaming, listening, callbacks.onVoice)
                        IconButton(onClick = callbacks.onAction, enabled = streaming || value.isNotBlank() || images.isNotEmpty(),
                            modifier = Modifier.size(48.dp).clip(CircleShape).background(SpectraColors.Focus.copy(if (dark) .34f else .16f))) {
                            Icon(if (streaming) Icons.Rounded.StopCircle else Icons.AutoMirrored.Rounded.Send,
                                if (streaming) "停止生成" else "发送")
                        }
                    }
                } else {
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
        }
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
