package com.campusai.core.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class MotionMode { ON, OFF }
enum class RenderQuality { AUTO, LOW, HIGH }
/**
 * Persisted by enum name and consumed by the renderer as an ordinal.
 * Keep the legacy entries in their original order; append new environments only.
 */
enum class SpectraEnvironment { ORIGINAL, OCEAN, ULTRAVIOLET, EMBER, AURORA }
enum class AiMode { FAST, DEEP }
enum class AiProvider { AUTO, DEEPSEEK, GOOGLE_GEMINI, CODEX, LOCAL, CAESAR_CLOUD_FREE }

data class LocalImageRef(
    val assetId: String,
    val relativePath: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val sha256: String,
)

sealed interface LocalModelState {
    data object NotDownloaded : LocalModelState
    data object Checking : LocalModelState
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : LocalModelState
    data class Paused(val downloadedBytes: Long, val totalBytes: Long) : LocalModelState
    data object Verifying : LocalModelState
    data object Ready : LocalModelState
    data object Loading : LocalModelState
    data class Error(val code: String, val recoverable: Boolean, val message: String) : LocalModelState
    data class Incompatible(val reason: String) : LocalModelState
}

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Data<T>(val value: T) : UiState<T>
    data object Empty : UiState<Nothing>
    data class Offline<T>(val value: T) : UiState<T>
    data class Error(val message: String, val canRetry: Boolean = true) : UiState<Nothing>
}

enum class SyncState { LocalOnly, Pending, Synced, Conflict, Failed }

data class AiConversationMessage(
    val role: String,
    val content: String,
    val presentationJson: String? = null,
    val attachmentPaths: List<String> = emptyList(),
    /** Stable references persisted with history; absolute paths are runtime-only compatibility data. */
    val attachmentRefs: List<LocalImageRef> = emptyList(),
    /** Runtime presentation state for legacy or deleted attachment files. */
    val missingAttachmentCount: Int = 0,
    /** In-memory provider state needed to continue DeepSeek reasoning; never persisted or rendered. */
    val providerReasoningContent: String? = null,
    /** A one-turn health disclosure must never be replayed into a later cloud request. */
    val cloudHealthSensitive: Boolean = false,
)
data class AiReport(
    val id: String,
    val provider: AiProvider,
    val mode: AiMode,
    val model: String,
    /** Persisted ResolvedExecution identity; kept as a stable enum name at the model boundary. */
    val executionEngine: String = if (provider == AiProvider.LOCAL) "LOCAL_MNN" else "CLOUD_OPENAI_COMPATIBLE",
    val requestId: String = "",
    val title: String,
    val summary: String,
    val messagesJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class DailyGreeting(
    val localDate: String,
    val text: String,
    val provider: AiProvider,
    val generatedAt: Long,
)
