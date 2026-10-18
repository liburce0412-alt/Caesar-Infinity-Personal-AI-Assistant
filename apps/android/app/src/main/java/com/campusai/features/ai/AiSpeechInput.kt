package com.campusai.features.ai

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.campusai.core.designsystem.CaesarOrbSignal
import java.util.Locale

internal data class AiSpeechInput(
    val listening: Boolean,
    val transcribing: Boolean,
    val message: String?,
    val showSystemConsent: Boolean,
    val showPermissionRecovery: Boolean,
    val toggle: () -> Unit,
    val cancel: (String?) -> Unit,
    val confirmSystemConsent: () -> Unit,
    val dismissSystemConsent: () -> Unit,
    val dismissPermissionRecovery: () -> Unit,
)

/** Page-scoped recognition: only edits the draft; the page owns sending, TTS and dialog UI. */
@Composable
internal fun rememberAiSpeechInput(
    ownerId: String,
    blocked: Boolean,
    orbSignal: CaesarOrbSignal,
    onTranscript: (String) -> Unit,
    onBeforeStart: () -> Unit,
    onPaused: () -> Unit,
    onNotice: (String) -> Unit,
): AiSpeechInput {
    val context = LocalContext.current
    val currentOwner by rememberUpdatedState(ownerId)
    val blockedNow by rememberUpdatedState(blocked)
    val onTranscriptNow by rememberUpdatedState(onTranscript)
    val onBeforeStartNow by rememberUpdatedState(onBeforeStart)
    val onPausedNow by rememberUpdatedState(onPaused)
    val onNoticeNow by rememberUpdatedState(onNotice)
    var listening by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    var voiceSessionActive by remember { mutableStateOf(false) }
    var voiceMessage by remember { mutableStateOf<String?>(null) }
    val resultGuard = remember { AiSpeechResultGuard() }
    var permissionTicket by remember { mutableStateOf<AiSpeechTicket?>(null) }
    var activityTicket by remember { mutableStateOf<AiSpeechTicket?>(null) }
    DisposableEffect(resultGuard) { onDispose { resultGuard.close() } }

    var voiceStartRequest by remember { mutableIntStateOf(0) }
    var consumedVoiceStartRequest by remember { mutableIntStateOf(0) }
    fun startRecognition() {
        resultGuard.cancel()
        voiceStartRequest += 1
    }
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
        val completedTicket = activityTicket
        activityTicket = null
        val completedRoute = systemSpeechActivityRoute
        systemSpeechActivityRoute = CaesarSpeechFallbackRoute.STANDARD_RECOGNITION_ACTIVITY
        systemSpeechActivityInFlight = false
        if (!resultGuard.accepts(completedTicket, currentOwner) || blockedNow) return@rememberLauncherForActivityResult
        listening = false
        transcribing = false
        orbSignal.reset()
        if (completedRoute == CaesarSpeechFallbackRoute.XIAOMI_ENGINE_PERMISSION_RECOVERY) {
            onNoticeNow("完成系统语音引擎的麦克风授权后，请返回并再点一次语音。")
        } else if (result.resultCode == Activity.RESULT_OK) {
            val transcript = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (transcript.isNotEmpty()) onTranscriptNow(transcript)
            else onNoticeNow("没有听清，请再说一次。")
        }
    }
    val launchSystemSpeechActivity: (CaesarSpeechFallbackRoute) -> Unit = { route ->
        if (!systemSpeechActivityInFlight && !blockedNow) {
            activityTicket = resultGuard.begin(currentOwner)
            systemSpeechActivityInFlight = true
            systemSpeechActivityRoute = route
            listening = true
            voiceMessage = null
            onBeforeStartNow()
            orbSignal.reset()
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
                onNoticeNow("系统语音输入无法启动，请检查默认语音识别服务。")
            }
        }
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val completedTicket = permissionTicket
        permissionTicket = null
        if (!resultGuard.accepts(completedTicket, currentOwner) || blockedNow) return@rememberLauncherForActivityResult
        if (granted) {
            startRecognition()
        } else {
            val canAskAgain = context.findActivity()?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
            } == true
            if (canAskAgain) onNoticeNow("需要麦克风权限才能进行语音转写。") else {
                showMicrophoneSettingsRecovery = true
            }
        }
    }
    val requestMicrophonePermission = {
        permissionTicket = resultGuard.begin(currentOwner)
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
        ownerId,
        voiceStartRequest,
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
    val cancel: (String?) -> Unit = { message ->
        resultGuard.cancel()
        consumedVoiceStartRequest = voiceStartRequest
        voiceSessionActive = false
        speechRecognizer?.cancel()
        listening = false
        transcribing = false
        voiceMessage = message
        showSystemSpeechConsent = false
        showMicrophoneSettingsRecovery = false
        orbSignal.reset()
    }
    DisposableEffect(speechRecognizer) {
        val listenerOwner = ownerId
        val listenerRequest = voiceStartRequest
        fun acceptsResult() = voiceSessionActive && !blockedNow && currentOwner == listenerOwner && voiceStartRequest == listenerRequest
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (acceptsResult() && !transcribing) listening = true }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) { if (acceptsResult() && listening) orbSignal.onRms(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { if (acceptsResult()) { listening = false; transcribing = true; orbSignal.reset() } }
            override fun onError(error: Int) {
                if (!acceptsResult()) return
                voiceSessionActive = false
                listening = false
                transcribing = false
                orbSignal.reset()
                voiceMessage = "语音输入未完成 · 请重试"
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
                    if (systemSpeechConsentGranted) startRecognition()
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
                message?.let { onNoticeNow(it) }
            }
            override fun onResults(results: Bundle?) {
                if (!acceptsResult()) return
                voiceSessionActive = false
                listening = false
                transcribing = false
                orbSignal.reset()
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onTranscriptNow(it) }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (!acceptsResult()) return
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onTranscriptNow(it) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose {
            voiceSessionActive = false
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            orbSignal.reset()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner, speechRecognizer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) foreground = true
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                foreground = false
                voiceSessionActive = false
                speechRecognizer?.cancel()
                listening = false
                transcribing = false
                orbSignal.reset()
                consumedVoiceStartRequest = voiceStartRequest
                onPausedNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(voiceStartRequest, speechRecognizer, foreground) {
        if (!foreground || blockedNow || voiceStartRequest == 0 || consumedVoiceStartRequest == voiceStartRequest) return@LaunchedEffect
        val recognizer = speechRecognizer ?: return@LaunchedEffect
        consumedVoiceStartRequest = voiceStartRequest
        voiceSessionActive = true
        listening = true
        transcribing = false
        voiceMessage = null
        onBeforeStartNow()
        orbSignal.reset()
        runCatching {
            recognizer.startListening(caesarSpeechRecognitionIntent(speechPolicy.preferOffline))
        }.onFailure { error ->
            voiceSessionActive = false
            listening = false
            transcribing = false
            voiceMessage = "语音输入无法启动 · 请重试"
            Log.w(SPEECH_LOG_TAG, "Recognizer start failed for ${speechPolicy.recognizerKind}", error)
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                showMicrophoneSettingsRecovery = true
            } else {
                onNoticeNow("语音识别启动失败，请稍后重试。")
            }
        }
    }
    // A service which never returns a final result must not leave the composer stuck forever.
    LaunchedEffect(transcribing) {
        if (transcribing) {
            kotlinx.coroutines.delay(12_000)
            if (transcribing) {
                voiceSessionActive = false
                speechRecognizer?.cancel()
                transcribing = false
                voiceMessage = "转写超时 · 请重试"
            }
        }
    }
    var previousOwner by remember { mutableStateOf(ownerId) }
    LaunchedEffect(blocked, ownerId) {
        if (blocked || previousOwner != ownerId) {
            cancel(null)
            onBeforeStartNow()
            if (previousOwner != ownerId) systemSpeechConsentGranted = false
        }
        previousOwner = ownerId
    }

    return AiSpeechInput(
        listening = listening,
        transcribing = transcribing,
        message = voiceMessage,
        showSystemConsent = showSystemSpeechConsent,
        showPermissionRecovery = showMicrophoneSettingsRecovery,
        toggle = {
            when {
                blockedNow -> Unit
                listening -> {
                    speechRecognizer?.stopListening()
                    listening = false
                    transcribing = true
                    orbSignal.reset()
                }
                transcribing -> cancel(null)
                speechPolicy.recognizerKind == CaesarSpeechRecognizerKind.UNAVAILABLE ->
                    onNoticeNow("当前设备没有可用的语音识别服务。")
                speechPolicy.requiresSystemConsent -> showSystemSpeechConsent = true
                speechPolicy.recognizerKind == CaesarSpeechRecognizerKind.SYSTEM && preferSystemSpeechActivity ->
                    launchSystemSpeechActivity(CaesarSpeechFallbackRoute.STANDARD_RECOGNITION_ACTIVITY)
                speechRecognizer == null -> onNoticeNow("系统语音识别器初始化失败，请稍后重试。")
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                    startRecognition()
                else -> requestMicrophonePermission()
            }
        },
        cancel = cancel,
        confirmSystemConsent = {
            showSystemSpeechConsent = false
            if (!blockedNow) {
                systemSpeechConsentGranted = true
                if (preferSystemSpeechActivity) launchSystemSpeechActivity(CaesarSpeechFallbackRoute.STANDARD_RECOGNITION_ACTIVITY)
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecognition()
                else requestMicrophonePermission()
            }
        },
        dismissSystemConsent = { showSystemSpeechConsent = false },
        dismissPermissionRecovery = { showMicrophoneSettingsRecovery = false },
    )
}

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
