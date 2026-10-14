package com.campusai.core.ai

import com.campusai.core.model.AiConversationMessage
import com.campusai.core.model.AiMode
import com.campusai.core.model.AiProvider
import kotlinx.coroutines.flow.Flow

enum class AiExecutionEngine {
    CLOUD_OPENAI_COMPATIBLE,
    LOCAL_MNN,
    LOCAL_DETERMINISTIC,
}

/** Immutable identity for one generation. UI and persistence must consume this value verbatim. */
data class ResolvedExecution(
    val provider: AiProvider,
    val model: String,
    val engine: AiExecutionEngine,
    val requestId: String,
)

data class AiRequest(
    val mode: AiMode,
    val messages: List<AiConversationMessage>,
    /** Pre-computed, size-limited structured context. Engines must not recalculate facts. */
    val structuredContextJson: String = "{}",
    val maxOutputTokens: Int = 512,
    /** App-private, normalized image paths. Cloud engines must not upload these implicitly. */
    val imagePaths: List<String> = emptyList(),
    /** Explicit request capability: only the selected Codex provider may encode current-turn JPEGs. */
    val allowCodexImageUpload: Boolean = false,
    /** Only the selected Caesar cloud free route may upload the explicitly attached current image. */
    val allowCloudFreeImageUpload: Boolean = false,
    /** Explicit cloud-only capability: permits projection and execution of the read-only web.search tool. */
    val allowWebSearch: Boolean = false,
    /** A compact JSON schema for the tools projected into this turn. */
    val caesarToolsJson: String = "[]",
    /** Vision, health-live and other device-only turns bypass automatic cloud routing. */
    val requiresLocal: Boolean = false,
    /** Fixed when a conversation first uses a local model; empty means use the persisted selection. */
    val localModelId: String = "",
    val sessionId: String = "",
    val ownerUserId: String = "",
    val userPrompt: String = "",
    /** Default-deny cloud boundary; only a typed daily summary may be explicitly disclosed per turn. */
    val cloudHealthDisclosure: CloudHealthDisclosure = CloudHealthDisclosure.Excluded,
)

sealed interface AiEvent {
    data class Meta(val execution: ResolvedExecution) : AiEvent {
        val model: String get() = execution.model
        val provider: AiProvider get() = execution.provider

        constructor(model: String, provider: AiProvider) : this(
            ResolvedExecution(
                provider = provider,
                model = model,
                engine = if (provider == AiProvider.LOCAL) AiExecutionEngine.LOCAL_MNN else AiExecutionEngine.CLOUD_OPENAI_COMPATIBLE,
                requestId = "",
            ),
        )
    }
    data class Status(val stage: String, val elapsedMs: Long) : AiEvent
    data class Delta(val text: String) : AiEvent
    data class ToolCallRequested(
        val name: String,
        val argumentsJson: String,
        val rawContent: String,
    ) : AiEvent
    data class ToolStarted(val name: String) : AiEvent
    data class ToolFinished(val name: String, val success: Boolean) : AiEvent
    data class Surface(val json: String) : AiEvent
    data class MemoryProposal(val id: String, val summary: String) : AiEvent
    data class Done(
        val elapsedMs: Long,
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        /** Provider-only continuation state; callers may keep it in memory but never persist, render or log it. */
        val providerReasoningContent: String? = null,
    ) : AiEvent
    data class Error(val code: String, val message: String, val recoverable: Boolean = true) : AiEvent
}

interface AiEngine {
    fun stream(request: AiRequest): Flow<AiEvent>
    fun cancel()
}

class AiRoutingException(
    val code: String,
    override val message: String,
    val canUseCloudOnce: Boolean = false,
) : IllegalStateException(message)
