package com.campusai.core.localai

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import com.campusai.core.ai.AiEngine
import com.campusai.core.ai.AiEvent
import com.campusai.core.ai.AiExecutionEngine
import com.campusai.core.ai.AiRequest
import com.campusai.core.ai.ResolvedExecution
import com.campusai.core.agent.CaesarToolCallParser
import com.campusai.core.model.AiProvider
import com.campusai.core.model.LocalModelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

class LocalMnnAiEngine(
    context: Context,
    private val manager: LocalModelManager,
) : AiEngine, ComponentCallbacks2 {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val activeGeneration = AtomicReference<LocalGenerationState?>(null)
    private val performance = LocalPerformanceRecorder(appContext)
    @Volatile private var nativePointer = 0L
    @Volatile private var loadedModelId: String? = null
    private var idleRelease: Job? = null

    init { appContext.registerComponentCallbacks(this) }

    override fun stream(request: AiRequest): Flow<AiEvent> = callbackFlow {
        val runtime = runCatching { manager.runtimeFor(request.localModelId) }.getOrElse { error ->
            close(error)
            return@callbackFlow
        }
        if (runtime.selection.state != LocalModelState.Ready) {
            close(IllegalStateException("${runtime.selection.manifest.displayName} 尚未完成下载与校验。"))
            return@callbackFlow
        }
        val generation = LocalGenerationState(MnnNativeBridge::cancel)
        idleRelease?.cancel()
        val job = scope.launch {
            var performanceMonitor: Job? = null
            try {
                trySend(AiEvent.Status("local_loading", 0))
                val peakPssKb = AtomicLong(Debug.getPss().toLong())
                val temperatureStart = batteryTemperatureC()
                performanceMonitor = scope.launch {
                    while (true) {
                        peakPssKb.accumulateAndGet(Debug.getPss().toLong()) { current, sample -> maxOf(current, sample) }
                        kotlinx.coroutines.delay(100)
                    }
                }
                var loadMs = 0L
                var hadImages = false
                val outputGuard = LocalOutputGuard()
                val rawOutput = StringBuilder()
                val metrics = mutex.withLock {
                    check(activeGeneration.compareAndSet(null, generation)) { "Another local generation is still active" }
                    try {
                        if (generation.cancelled.get()) return@withLock null
                        val loaded = ensureLoaded(runtime)
                        loadMs = loaded.loadMs
                        if (!generation.attach(loaded.pointer)) return@withLock null
                        val messages = LocalPromptPolicy.prepare(request, runtime.selection.manifest.contextTokens)
                        val images = collectImages(messages, request.imagePaths)
                        hadImages = images.isNotEmpty()
                        if (images.isNotEmpty() && runtime.selection.manifest.files.none { it.path == "visual.mnn" }) {
                            trySend(AiEvent.Error("local_vision_unavailable", "当前本地模型不包含视觉组件。"))
                            return@withLock null
                        }
                        trySend(
                            AiEvent.Meta(
                                ResolvedExecution(
                                    provider = AiProvider.LOCAL,
                                    model = "${runtime.selection.manifest.displayName} · ${runtime.selection.manifest.quantization}",
                                    engine = AiExecutionEngine.LOCAL_MNN,
                                    requestId = UUID.randomUUID().toString(),
                                ),
                            ),
                        )
                        trySend(AiEvent.Status("local_generating", 0))
                        MnnNativeBridge.generate(
                            pointer = loaded.pointer,
                            roles = messages.map { it.role }.toTypedArray(),
                            contents = messages.map { it.content }.toTypedArray(),
                            imagePaths = images.map(NativeImageInput::path).toTypedArray(),
                            imageMessageIndexes = images.map(NativeImageInput::messageIndex).toIntArray(),
                            allowedImageRoot = File(appContext.noBackupFilesDir, "ai-conversations").apply { mkdirs() }.canonicalPath,
                            maxTokens = request.maxOutputTokens.coerceAtMost(runtime.selection.manifest.maxOutputTokens),
                            listener = MnnTokenListener { token ->
                                if (!generation.cancelled.get() && token.isNotEmpty()) {
                                    if (rawOutput.length < MAX_CAPTURE_CHARS) rawOutput.append(token)
                                    outputGuard.accept(token).forEach { visible -> trySend(AiEvent.Delta(visible)) }
                                }
                                generation.cancelled.get()
                            },
                        )
                    } finally {
                        generation.detach()
                        activeGeneration.compareAndSet(generation, null)
                    }
                } ?: return@launch
                val toolCall = CaesarToolCallParser.parse(rawOutput.toString())
                if (toolCall == null) {
                    outputGuard.finish().forEach { visible -> trySend(AiEvent.Delta(visible)) }
                }
                performanceMonitor?.cancel()
                performance.record(LocalPerformanceSample(
                    recordedAt = System.currentTimeMillis(),
                    device = LocalPerformanceRecorder.deviceName(),
                    backend = "CPU",
                    threads = 4,
                    loadMs = loadMs,
                    firstTokenMs = metrics.firstTokenMicros / 1_000.0,
                    decodeTokensPerSecond = if (metrics.decodeMicros > 0) metrics.outputTokens * 1_000_000.0 / metrics.decodeMicros else 0.0,
                    peakPssKb = peakPssKb.get(),
                    outputTokens = metrics.outputTokens,
                    elapsedMs = metrics.elapsedMs,
                    visionMs = metrics.visionMicros / 1_000.0,
                    visionPixels = metrics.visionPixels,
                    batteryTemperatureStartC = temperatureStart,
                    batteryTemperatureEndC = batteryTemperatureC(),
                ))
                if (!generation.cancelled.get()) {
                    if (hadImages && (metrics.visionMicros <= 0L || metrics.visionPixels <= 0L)) {
                        trySend(AiEvent.Error("local_vision_decode_failed", "本地模型没有成功读取图片，请重新选择图片后重试。"))
                    } else if (toolCall != null) {
                        trySend(AiEvent.ToolCallRequested(toolCall.name, toolCall.arguments.toString(), toolCall.rawContent))
                    } else if (outputGuard.blocked || !outputGuard.hasVisibleOutput) {
                        trySend(
                            AiEvent.Error(
                                "local_output_rejected",
                                "本地模型只返回了内部过程，没有形成可展示的答案。Caesar∞ 已拦截，请重试；仍然发生时可明确切换到 DeepSeek。",
                            ),
                        )
                    } else {
                        trySend(AiEvent.Done(metrics.elapsedMs, metrics.inputTokens, metrics.outputTokens))
                    }
                }
            } catch (error: Throwable) {
                if (!generation.cancelled.get()) {
                    runCatching { releaseAndWait() }
                    val visionFailure = error.message?.startsWith("local_vision_") == true
                    trySend(
                        AiEvent.Error(
                            if (visionFailure) "local_vision_decode_failed" else "local_inference_failed",
                            if (visionFailure) "本地模型没有成功读取图片，请重新选择图片后重试。" else error.message ?: "本地推理失败，请重试。",
                        ),
                    )
                }
            } finally {
                performanceMonitor?.cancel()
                generation.takeCancellationError()?.let { cancellation ->
                    trySend(AiEvent.Error(cancellation.code, cancellation.message))
                }
                scheduleIdleRelease()
                close()
            }
        }
        awaitClose {
            generation.cancel()
            job.cancel()
        }
    }

    private data class LoadedEngine(val pointer: Long, val loadMs: Long)

    private data class NativeImageInput(val path: String, val messageIndex: Int)

    private fun collectImages(
        messages: List<com.campusai.core.model.AiConversationMessage>,
        currentImagePaths: List<String>,
    ): List<NativeImageInput> {
        val attached = buildList {
            messages.forEachIndexed { index, message ->
                if (message.role == "user") {
                    message.attachmentPaths.take(MAX_IMAGES_PER_MESSAGE).forEach { path -> add(NativeImageInput(path, index)) }
                }
            }
        }
        if (attached.isNotEmpty() || currentImagePaths.isEmpty()) return attached
        val owner = messages.indexOfLast { it.role == "user" }
        if (owner < 0) return emptyList()
        return currentImagePaths.take(MAX_IMAGES_PER_MESSAGE).map { NativeImageInput(it, owner) }
    }

    private fun ensureLoaded(runtime: LocalModelRuntime): LoadedEngine {
        val modelId = runtime.selection.manifest.id
        nativePointer.takeIf { it != 0L && loadedModelId == modelId }?.let { return LoadedEngine(it, 0) }
        if (nativePointer != 0L) releaseLoadedUnsafe()
        check(runtime.storage.isReady()) { "${runtime.selection.manifest.displayName} 文件未处于 Ready 状态。" }
        val cache = File(appContext.cacheDir, "mnn-local/$modelId-${runtime.selection.manifest.version}").apply { mkdirs() }
        val config = File(runtime.storage.activeDirectory, "config.json")
        val started = System.nanoTime()
        val created = MnnNativeBridge.create(config.absolutePath, cache.absolutePath.replace('\\', '/'))
        check(created != 0L) { "MNN 模型加载失败。" }
        nativePointer = created
        loadedModelId = modelId
        return LoadedEngine(created, (System.nanoTime() - started) / 1_000_000L)
    }

    override fun cancel() {
        activeGeneration.get()?.cancel()
    }

    fun release() {
        cancel()
        scope.launch { releaseAndWait() }
    }

    fun shutdown() {
        cancel()
        scope.launch {
            releaseAndWait()
            appContext.unregisterComponentCallbacks(this@LocalMnnAiEngine)
            scope.cancel()
        }
    }

    suspend fun releaseAndWait() = mutex.withLock {
        releaseLoadedUnsafe()
    }

    private fun releaseLoadedUnsafe() {
        val pointer = nativePointer
        nativePointer = 0
        loadedModelId = null
        // Calling the bridge initializes JNI; an idle engine has nothing to unload.
        if (pointer != 0L) MnnNativeBridge.release(pointer)
    }

    private fun scheduleIdleRelease() {
        idleRelease?.cancel()
        idleRelease = scope.launch {
            kotlinx.coroutines.delay(IDLE_RELEASE_MS)
            releaseAndWait()
        }
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) releaseForMemoryPressure()
    }
    override fun onLowMemory() = releaseForMemoryPressure()
    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    private fun releaseForMemoryPressure() {
        activeGeneration.get()?.cancel(
            LocalCancellationError(
                code = "local_memory_pressure",
                message = "设备内存压力过高，Caesar∞ 已释放当前模型。本会话仍保留原模型档位，释放内存后可重试。",
            ),
        )
        scope.launch { releaseAndWait() }
    }

    private fun batteryTemperatureC(): Double? {
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return tenths.takeIf { it != Int.MIN_VALUE }?.div(10.0)
    }

    companion object {
        private const val IDLE_RELEASE_MS = 5 * 60 * 1_000L
        private const val MAX_CAPTURE_CHARS = 32_768
        private const val MAX_IMAGES_PER_MESSAGE = 4
    }
}

internal class LocalGenerationState(
    private val cancelNative: (Long) -> Unit,
) {
    val cancelled = AtomicBoolean(false)
    private val pointer = AtomicLong(0)
    private val cancellationError = AtomicReference<LocalCancellationError?>(null)

    fun attach(value: Long): Boolean {
        pointer.set(value)
        if (!cancelled.get()) return true
        cancelNative(value)
        return false
    }

    fun detach() = pointer.set(0)

    fun cancel(error: LocalCancellationError? = null) {
        if (error != null) cancellationError.compareAndSet(null, error)
        cancelled.set(true)
        pointer.get().takeIf { it != 0L }?.let(cancelNative)
    }

    fun takeCancellationError(): LocalCancellationError? = cancellationError.getAndSet(null)
}

internal data class LocalCancellationError(val code: String, val message: String)
