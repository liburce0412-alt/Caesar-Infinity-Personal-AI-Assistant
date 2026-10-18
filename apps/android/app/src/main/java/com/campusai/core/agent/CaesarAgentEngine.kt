package com.campusai.core.agent

import com.campusai.core.ai.AiEngine
import com.campusai.core.ai.AiEvent
import com.campusai.core.ai.AiRequest
import com.campusai.core.model.AiConversationMessage
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

fun interface CaesarTraceSink {
    suspend fun record(event: CaesarTraceEvent)
}

data class CaesarTraceEvent(
    val sessionId: String,
    val kind: String,
    val name: String,
    val durationMs: Long,
    val success: Boolean,
    val errorCode: String? = null,
    val ownerUserId: String = "local_user",
)

class CaesarAgentEngine(
    private val delegate: AiEngine,
    private val tools: CaesarToolRegistry,
    private val traceSink: CaesarTraceSink = CaesarTraceSink { },
) : AiEngine {
    private val activeCall = AtomicReference<String?>(null)
    private val pendingConfirmation = AtomicReference<PendingConfirmation?>(null)

    override fun stream(request: AiRequest): Flow<AiEvent> = flow {
        val sessionId = request.sessionId.ifBlank { UUID.randomUUID().toString() }
        val prompt = request.userPrompt.ifBlank { request.messages.lastOrNull { it.role == "user" }?.content.orEmpty() }
        val turnRequest = request.copy(
            sessionId = sessionId,
            toolActionScope = request.toolActionScope.ifBlank {
                "turn-${request.messages.count { it.role == "user" }.coerceAtLeast(1)}"
            },
        )
        // Device health tools may expose source packages, timestamps and minute-level details.
        // Only requests already forced onto the local engine may see them; cloud turns receive
        // the separately typed, consented daily summary carried by CloudHealthDisclosure.
        val projected = tools.project(prompt).filter { definition ->
            (request.allowedToolNames == null || definition.name in request.allowedToolNames) &&
            (definition.name != "memory.list" || request.requiresLocal || request.allowLocalMemoryRead) &&
            (request.requiresLocal || !definition.name.startsWith(HEALTH_TOOL_PREFIX)) &&
                (!definition.name.startsWith(WEB_TOOL_PREFIX) ||
                    (request.allowWebSearch && !request.requiresLocal))
        }
        val projectedToolNames = projected.mapTo(linkedSetOf()) { it.name }
        val current = turnRequest.copy(
            userPrompt = prompt,
            caesarToolsJson = tools.promptSchema(projected),
            structuredContextJson = JSONObject(request.structuredContextJson)
                .put("clock", com.campusai.core.time.AiClock.context()).toString(),
        )
        pendingConfirmation.set(null)
        runLoop(turnRequest, current, CaesarDagState(), projectedToolNames).collect { emit(it) }
    }

    fun confirm(actionId: String): Flow<AiEvent> = flow {
        val pending = pendingConfirmation.getAndSet(null)
        if (pending == null || actionId != "confirm:${pending.idempotencyKey}") {
            emit(AiEvent.Error("confirmation_expired", "这个确认已过期，请重新发起操作。"))
            return@flow
        }
        emit(AiEvent.ToolStarted(pending.call.name))
        val executed = execute(
            pending.request,
            pending.call,
            pending.arguments,
            pending.projectedToolNames,
            confirmationGranted = true,
        )
        emit(AiEvent.ToolFinished(pending.call.name, executed.result is CaesarToolResult.Success))
        when (val result = executed.result) {
            is CaesarToolResult.Success -> {
                result.surface?.let { emit(AiEvent.Surface(it.toJson())) }
                val next = pending.current.withToolResult(pending.call, result.contentJson)
                runLoop(pending.request, next, pending.dag, pending.projectedToolNames).collect { emit(it) }
            }
            is CaesarToolResult.Denied -> emit(AiEvent.Error(result.code, result.message))
            is CaesarToolResult.Unavailable -> emit(AiEvent.Error(result.code, result.message))
            is CaesarToolResult.RetryableError -> emit(AiEvent.Error(result.code, result.message))
            is CaesarToolResult.NeedsConfirmation -> emit(AiEvent.Error("confirmation_failed", "确认状态无法验证。"))
        }
    }

    private fun runLoop(
        request: AiRequest,
        initial: AiRequest,
        dag: CaesarDagState,
        projectedToolNames: Set<String>,
    ): Flow<AiEvent> = flow {
        var current = initial
        var outputFormatRetryAvailable = true
        generation@ while (dag.toolCalls < CaesarDagState.MAX_TOOL_CALLS) {
            var requested: AiEvent.ToolCallRequested? = null
            var terminal: AiEvent? = null
            var visibleOutputEmitted = false
            delegate.stream(current).collect { event ->
                when (event) {
                    is AiEvent.ToolCallRequested -> requested = event
                    is AiEvent.Done, is AiEvent.Error -> terminal = event
                    else -> {
                        if (event is AiEvent.Delta && event.text.isNotEmpty()) visibleOutputEmitted = true
                        emit(event)
                    }
                }
            }
            val outputError = terminal as? AiEvent.Error
            if (
                requested == null &&
                outputError?.code == "local_output_rejected" &&
                !visibleOutputEmitted &&
                outputFormatRetryAvailable &&
                dag.toolCalls == 0 &&
                current.caesarToolsJson.trim() == "[]"
            ) {
                outputFormatRetryAvailable = false
                current = current.forOutputRepair()
                emit(AiEvent.Status("本地回答格式异常，正在以简洁回答模式重试", 0))
                continue@generation
            }
            val call = requested ?: run {
                terminal?.let { emit(it) }
                return@flow
            }
            activeCall.set(call.name)
            emit(AiEvent.ToolStarted(call.name))
            val arguments = if (call.name in projectedToolNames) {
                runCatching { JSONObject(call.argumentsJson) }.getOrElse {
                    emit(AiEvent.Error("invalid_tool_call", "模型生成了无法解析的工具参数。"))
                    return@flow
                }
            } else {
                JSONObject()
            }
            val canonicalArguments = CaesarIntentEvidence.canonicalArguments(arguments)
            val node = runCatching { dag.begin(call.name, canonicalArguments) }.getOrElse { error ->
                emit(AiEvent.Error(error.message ?: "dag_limit_reached", "任务图已达安全上限，已停止执行。"))
                return@flow
            }
            val idempotencyKey = CaesarIntentEvidence.idempotencyKey(
                request.sessionId, call.name, canonicalArguments, request.ownerUserId, request.toolActionScope,
            )
            val executed = execute(request, call, arguments, projectedToolNames, confirmationGranted = false)
            val result = executed.result
            val elapsedMs = executed.elapsedMs
            val success = result is CaesarToolResult.Success
            runCatching { dag.complete(node, success || result is CaesarToolResult.NeedsConfirmation) }.getOrElse { error ->
                emit(AiEvent.Error(error.message ?: "replan_limit_reached", "任务连续重规划失败，已在安全上限停止。"))
                return@flow
            }
            emit(AiEvent.ToolFinished(call.name, success))
            activeCall.set(null)
            when (result) {
                is CaesarToolResult.NeedsConfirmation -> {
                    pendingConfirmation.set(
                        PendingConfirmation(request, current, call, arguments, dag, idempotencyKey, projectedToolNames),
                    )
                    val surface = CaesarSurface(
                        id = "confirmation-$idempotencyKey",
                        title = result.title,
                        components = listOf(
                            CaesarComponent.Text(result.description),
                            CaesarComponent.Button("确认执行", result.actionId),
                        ),
                    )
                    emit(AiEvent.Surface(surface.toJson()))
                    emit(AiEvent.Delta("这个操作需要你确认后才能继续。"))
                    emit(AiEvent.Done(elapsedMs))
                    return@flow
                }
                is CaesarToolResult.Success -> {
                    result.surface?.let { emit(AiEvent.Surface(it.toJson())) }
                    current = current.withToolResult(call, result.contentJson)
                }
                is CaesarToolResult.Denied -> {
                    current = current.withToolFailure(call, result.code, result.message)
                }
                is CaesarToolResult.Unavailable -> {
                    current = current.withToolFailure(call, result.code, result.message)
                }
                is CaesarToolResult.RetryableError -> {
                    current = current.withToolFailure(call, result.code, result.message)
                }
            }
        }
        emit(AiEvent.Error("tool_limit_reached", "本次任务已达到 12 次工具调用上限，已安全停止。"))
    }

    private suspend fun execute(
        request: AiRequest,
        call: AiEvent.ToolCallRequested,
        arguments: JSONObject,
        projectedToolNames: Set<String>,
        confirmationGranted: Boolean,
    ): ExecutedTool {
        val started = System.nanoTime()
        val prompt = request.userPrompt.ifBlank { request.messages.lastOrNull { it.role == "user" }?.content.orEmpty() }
        val idempotencyKey = CaesarIntentEvidence.idempotencyKey(
            request.sessionId,
            call.name,
            CaesarIntentEvidence.canonicalArguments(arguments),
            request.ownerUserId,
            request.toolActionScope,
        )
        val context = ToolExecutionContext(
            sessionId = request.sessionId,
            ownerUserId = request.ownerUserId,
            userPrompt = prompt,
            autonomyMode = AutonomyMode.OWNER_DIRECT,
            explicitUserIntent = CaesarIntentEvidence.isExplicit(prompt, call.name),
            idempotencyKey = idempotencyKey,
            confirmationGranted = confirmationGranted,
        )
        var result = if (call.name in projectedToolNames) {
            tools.execute(call.name, arguments, context)
        } else {
            CaesarToolResult.Denied("tool_not_projected", "本轮未授权执行这个工具。")
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        traceSink.record(CaesarTraceEvent(request.sessionId, "tool", call.name, elapsedMs, result is CaesarToolResult.Success, result.errorCode(), request.ownerUserId.ifBlank { "local_user" }))
        return ExecutedTool(result, elapsedMs)
    }

    private fun AiRequest.withToolResult(call: AiEvent.ToolCallRequested, resultJson: String) = copy(
        // OpenAI chat completions are stateless: a Codex vision turn must resend its current-turn
        // image while completing a tool loop. Saved historical images are never placed here.
        imagePaths = if (allowCodexImageUpload) imagePaths else emptyList(),
        messages = messages + AiConversationMessage("assistant", call.rawContent, cloudHealthSensitive = call.isLocalPrivateRead()) +
            AiConversationMessage("tool", resultJson, cloudHealthSensitive = call.isLocalPrivateRead()),
    )

    private fun AiRequest.withToolFailure(call: AiEvent.ToolCallRequested, code: String, message: String) = copy(
        imagePaths = if (allowCodexImageUpload) imagePaths else emptyList(),
        messages = messages +
            AiConversationMessage("assistant", call.rawContent, cloudHealthSensitive = call.isLocalPrivateRead()) +
            AiConversationMessage("tool", JSONObject().put("ok", false).put("code", code).put("message", message).toString(), cloudHealthSensitive = call.isLocalPrivateRead()),
    )

    private fun AiEvent.ToolCallRequested.isLocalPrivateRead() = name == "memory.list" || name.startsWith(HEALTH_TOOL_PREFIX)

    private companion object {
        const val HEALTH_TOOL_PREFIX = "health."
        const val WEB_TOOL_PREFIX = "web."
    }

    private fun AiRequest.forOutputRepair(): AiRequest {
        val lastUser = messages.indexOfLast { it.role == "user" }
        if (lastUser < 0) return copy(caesarToolsJson = "[]", maxOutputTokens = minOf(maxOutputTokens, 192))
        val repaired = messages.toMutableList()
        val original = repaired[lastUser]
        repaired[lastUser] = original.copy(content = original.content.trimEnd() + OUTPUT_REPAIR_SUFFIX)
        return copy(
            messages = repaired,
            caesarToolsJson = "[]",
            maxOutputTokens = minOf(maxOutputTokens, 192),
        )
    }

    override fun cancel() {
        activeCall.set(null)
        pendingConfirmation.set(null)
        delegate.cancel()
    }

    private fun CaesarToolResult.errorCode(): String? = when (this) {
        is CaesarToolResult.Denied -> code
        is CaesarToolResult.RetryableError -> code
        is CaesarToolResult.Unavailable -> code
        else -> null
    }

    private data class PendingConfirmation(
        val request: AiRequest,
        val current: AiRequest,
        val call: AiEvent.ToolCallRequested,
        val arguments: JSONObject,
        val dag: CaesarDagState,
        val idempotencyKey: String,
        val projectedToolNames: Set<String>,
    )
    private data class ExecutedTool(val result: CaesarToolResult, val elapsedMs: Long)

}

private const val OUTPUT_REPAIR_SUFFIX = """

[Caesar 本地输出修复]
直接回答上面的原始问题，只输出一到两句给用户看的最终答案正文。不要复述本说明，不要分析，不要输出标签、JSON、XML 或工具调用。
"""
