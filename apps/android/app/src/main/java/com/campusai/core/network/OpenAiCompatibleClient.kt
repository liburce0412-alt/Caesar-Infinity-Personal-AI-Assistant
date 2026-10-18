package com.campusai.core.network

import com.campusai.core.ai.AiEvent
import com.campusai.core.ai.AiExecutionEngine
import com.campusai.core.ai.AiRequest
import com.campusai.core.ai.AiSystemPolicy
import com.campusai.core.ai.CloudAiProvider
import com.campusai.core.ai.CloudHealthDisclosure
import com.campusai.core.ai.CloudProviderConnection
import com.campusai.core.ai.CloudProviderModel
import com.campusai.core.ai.ResolvedExecution
import com.campusai.core.model.AiConversationMessage
import com.campusai.core.model.AiMode
import com.campusai.core.security.PersonalAiProviderStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject

class PersonalCloudClient internal constructor(
    val provider: CloudAiProvider,
    private val credential: () -> String,
    private val selectedModel: () -> String,
    private val baseUrl: () -> String = { provider.defaultBaseUrl },
    private val client: OkHttpClient,
) {
    constructor(provider: CloudAiProvider, store: PersonalAiProviderStore) : this(
        provider = provider,
        credential = { store.readCredential(provider)?.value.orEmpty() },
        selectedModel = { store.selectedModel(provider) },
        baseUrl = { store.baseUrl(provider) },
        client = defaultCloudHttpClient(),
    )

    private val activeCall = AtomicReference<Call?>(null)

    suspend fun stream(
        request: AiRequest,
        withSubmissionLease: suspend (submit: () -> Unit) -> Boolean = { submit -> submit(); true },
        onEvent: suspend (AiEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val apiKey = requireCredential()
        val model = try {
            provider.resolveModel(request.mode, selectedModel())
        } catch (_: IllegalArgumentException) {
            throw CloudProviderException("model_invalid", "已选择的 ${provider.displayName} 模型 ID 无效。", false)
        }
        val prepared = OpenAiCompatibleRequestFactory.prepare(provider, request, model)
        val httpRequest = authenticatedRequest(provider.chatCompletionsUrl(requireBaseUrl()), apiKey)
            .post(prepared.payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val call = client.newCall(httpRequest)
        activeCall.set(call)
        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        val started = System.currentTimeMillis()
        var inputTokens: Long? = null
        var outputTokens: Long? = null
        var streamCompleted = false
        var streamStarted = false
        val assistantContent = StringBuilder()
        val reasoningContent = StringBuilder()
        val toolCalls = OpenAiToolCallAccumulator(provider, prepared.originalToolNamesByWireName)
        val outputGuard = CloudOutputIntegrityGuard(request.maxOutputTokens)
        try {
            onEvent(
                AiEvent.Meta(
                    ResolvedExecution(
                        provider = provider.appProvider,
                        model = model,
                        engine = AiExecutionEngine.CLOUD_OPENAI_COMPATIBLE,
                        requestId = UUID.randomUUID().toString(),
                    ),
                ),
            )
            onEvent(AiEvent.Status(if (request.mode == AiMode.DEEP) "planning" else "responding", 0))
            call.awaitResponse(withSubmissionLease).use { response ->
                if (!response.isSuccessful) throw providerError(provider, response.code)
                val source = response.body?.source()
                    ?: throw CloudProviderException("empty_response", "${provider.displayName} 没有返回数据流，请重试。")
                streamStarted = true
                val events = SseEventReader(source)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val data = events.next()?.data?.trim() ?: break
                    if (data == "[DONE]") {
                        streamCompleted = true
                        break
                    }
                    if (OpenAiCompatibleStreamParser.containsProviderError(data)) {
                        throw CloudProviderException(
                            "provider_stream_error",
                            "${provider.displayName} 数据流返回了错误，请重试。",
                        )
                    }
                    val chunk = OpenAiCompatibleStreamParser.parse(data)
                        ?: throw CloudProviderException(
                            "provider_stream_malformed",
                            "${provider.displayName} 返回了损坏的数据流，请重试。",
                        )
                    chunk.delta?.takeIf(String::isNotEmpty)?.let { delta ->
                        if (assistantContent.length + delta.length > MAX_PROVIDER_ASSISTANT_CHARS) {
                            throw CloudProviderException("provider_response_too_large", "${provider.displayName} 返回的回复过大，已安全停止。", false)
                        }
                        assistantContent.append(delta)
                        outputGuard.accept(delta).forEach { visible -> onEvent(AiEvent.Delta(visible)) }
                    }
                    chunk.reasoningContentDelta?.takeIf(String::isNotEmpty)?.let { delta ->
                        if (reasoningContent.length + delta.length > MAX_PROVIDER_REASONING_CHARS) {
                            throw CloudProviderException("provider_response_too_large", "${provider.displayName} 返回的推理状态过大，已安全停止。", false)
                        }
                        reasoningContent.append(delta)
                    }
                    chunk.toolCallFragments.forEach(toolCalls::append)
                    if (chunk.inputTokens != null) inputTokens = chunk.inputTokens
                    if (chunk.outputTokens != null) outputTokens = chunk.outputTokens
                }
            }
            if (!streamCompleted) {
                throw providerStreamInterruptedError(provider)
            }
            outputGuard.finish(outputTokens).forEach { visible -> onEvent(AiEvent.Delta(visible)) }
            val replayReasoning = reasoningContent.toString().takeIf(String::isNotBlank)
            val toolCall = toolCalls.complete(
                assistantContent = assistantContent.toString(),
                reasoningContent = replayReasoning,
            )
            if (toolCall != null) {
                if (provider == CloudAiProvider.DEEPSEEK && request.mode == AiMode.DEEP && replayReasoning == null) {
                    throw CloudProviderException(
                        "provider_reasoning_missing",
                        "DeepSeek 未返回继续工具调用所需的推理状态，请重试。",
                    )
                }
                onEvent(
                    AiEvent.ToolCallRequested(
                        name = toolCall.originalName,
                        argumentsJson = toolCall.argumentsJson,
                        rawContent = ProviderToolCallEnvelope.encode(toolCall),
                    ),
                )
            } else {
                onEvent(
                    AiEvent.Done(
                        elapsedMs = System.currentTimeMillis() - started,
                        inputTokens = inputTokens,
                        outputTokens = outputTokens,
                        providerReasoningContent = replayReasoning.takeIf { provider == CloudAiProvider.DEEPSEEK },
                    ),
                )
            }
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            throw if (streamStarted) providerStreamInterruptedError(provider) else providerConnectivityError(provider)
        } finally {
            cancellationWatcher.cancel()
            activeCall.compareAndSet(call, null)
        }
    }

    suspend fun listModels(): List<CloudProviderModel> = withContext(Dispatchers.IO) {
        val apiKey = requireCredential()
        val call = client.newCall(authenticatedRequest(provider.modelsUrl(requireBaseUrl()), apiKey).get().build())
        activeCall.set(call)
        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.awaitResponse().use { response ->
                if (!response.isSuccessful) throw providerError(provider, response.code)
                val raw = response.body?.string()
                    ?: throw CloudProviderException("empty_response", "${provider.displayName} 没有返回模型列表。")
                parseLiveModelCatalog(provider, raw)
            }
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            throw providerConnectivityError(provider)
        } finally {
            cancellationWatcher.cancel()
            activeCall.compareAndSet(call, null)
        }
    }

    suspend fun validateConnection(modelId: String = selectedModel()): CloudProviderConnection {
        val started = System.currentTimeMillis()
        val resolved = try {
            provider.resolveModel(AiMode.FAST, modelId)
        } catch (_: IllegalArgumentException) {
            throw CloudProviderException("model_invalid", "已选择的 ${provider.displayName} 模型 ID 无效。", false)
        }
        val models = listModels()
        if (models.none { it.id == resolved }) {
            throw CloudProviderException("model_unavailable", "${provider.displayName} 账户当前不可使用模型 $resolved。", false)
        }
        val chatVerified = provider == CloudAiProvider.CODEX && validateChatCompletion(resolved)
        val toolCallsVerified = provider == CloudAiProvider.CODEX && validateToolCall(resolved)
        return CloudProviderConnection(
            provider = provider,
            selectedModelId = resolved,
            models = models,
            latencyMs = System.currentTimeMillis() - started,
            chatVerified = chatVerified,
            toolCallsVerified = toolCallsVerified,
        )
    }

    fun cancel() {
        activeCall.getAndSet(null)?.cancel()
    }

    private fun requireCredential(): String = credential().takeIf(String::isNotBlank)
        ?: throw CloudProviderException("provider_key_missing", "尚未保存个人 ${provider.displayName} Key。请前往设置保存后重试。", false)

    private fun requireBaseUrl(): String = try {
        provider.resolveBaseUrl(baseUrl())
    } catch (_: IllegalArgumentException) {
        throw CloudProviderException("base_url_invalid", "${provider.displayName} Base URL 无效，请在设置中修正。", false)
    }

    private suspend fun validateChatCompletion(model: String): Boolean = withContext(Dispatchers.IO) {
        val apiKey = requireCredential()
        val payload = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
            .put("max_tokens", 16)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", "只回复 OK")),
            )
        val request = authenticatedRequest(provider.chatCompletionsUrl(requireBaseUrl()), apiKey)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val call = client.newCall(request)
        activeCall.set(call)
        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        val answer = StringBuilder()
        var completed = false
        var streamStarted = false
        try {
            call.awaitResponse().use { response ->
                if (!response.isSuccessful) throw providerError(provider, response.code)
                val source = response.body?.source()
                    ?: throw CloudProviderException("empty_response", "${provider.displayName} 没有返回对话数据流。")
                streamStarted = true
                val events = SseEventReader(source)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val data = events.next()?.data?.trim() ?: break
                    if (data == "[DONE]") {
                        completed = true
                        break
                    }
                    if (OpenAiCompatibleStreamParser.containsProviderError(data)) {
                        throw CloudProviderException("provider_stream_error", "${provider.displayName} 真实对话验证返回了错误。")
                    }
                    val chunk = OpenAiCompatibleStreamParser.parse(data)
                        ?: throw CloudProviderException("provider_stream_malformed", "${provider.displayName} 真实对话验证返回了损坏的数据流。")
                    chunk.delta?.let { delta ->
                        if (answer.length + delta.length > MAX_CONNECTION_TEST_CHARS) {
                            throw CloudProviderException("provider_chat_verification_failed", "${provider.displayName} 真实对话验证返回内容异常。", false)
                        }
                        answer.append(delta)
                    }
                }
            }
            if (!completed) {
                throw providerStreamInterruptedError(provider, verification = true)
            }
            if (answer.toString().trim() != "OK") {
                throw CloudProviderException(
                    "provider_chat_verification_failed",
                    "${provider.displayName} 模型列表可用，但真实对话没有按要求回复 OK。",
                    false,
                )
            }
            true
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            throw if (streamStarted) {
                providerStreamInterruptedError(provider, verification = true)
            } else {
                providerConnectivityError(provider)
            }
        } finally {
            cancellationWatcher.cancel()
            activeCall.compareAndSet(call, null)
        }
    }

    /** Verifies OpenAI-compatible tool_calls without executing any application action. */
    private suspend fun validateToolCall(model: String): Boolean = withContext(Dispatchers.IO) {
        val apiKey = requireCredential()
        val parameters = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "value",
                    JSONObject()
                        .put("type", "string")
                        .put("enum", JSONArray().put(CONNECTION_PROBE_VALUE)),
                ),
            )
            .put("required", JSONArray().put("value"))
            .put("additionalProperties", false)
        val payload = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
            .put("max_tokens", 64)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", "这是连接能力测试。只调用指定函数，不要输出正文。"),
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", "请调用 connection_probe，并将 value 设为 OK。"),
                    ),
            )
            .put(
                "tools",
                JSONArray().put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", CONNECTION_PROBE_TOOL)
                                .put("description", "无副作用的连接能力探针；不会执行任何业务动作。")
                                .put("parameters", parameters),
                        ),
                ),
            )
            .put(
                "tool_choice",
                JSONObject()
                    .put("type", "function")
                    .put("function", JSONObject().put("name", CONNECTION_PROBE_TOOL)),
            )
            .put("parallel_tool_calls", false)
        val request = authenticatedRequest(provider.chatCompletionsUrl(requireBaseUrl()), apiKey)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val call = client.newCall(request)
        activeCall.set(call)
        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        val toolCalls = OpenAiToolCallAccumulator(
            provider = provider,
            originalNamesByWireName = mapOf(CONNECTION_PROBE_TOOL to CONNECTION_PROBE_TOOL),
        )
        var completed = false
        var streamStarted = false
        var visibleChars = 0
        try {
            call.awaitResponse().use { response ->
                if (!response.isSuccessful) throw providerError(provider, response.code)
                val source = response.body?.source()
                    ?: throw CloudProviderException("empty_response", "${provider.displayName} 没有返回工具调用数据流。")
                streamStarted = true
                val events = SseEventReader(source)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val data = events.next()?.data?.trim() ?: break
                    if (data == "[DONE]") {
                        completed = true
                        break
                    }
                    if (OpenAiCompatibleStreamParser.containsProviderError(data)) {
                        throw CloudProviderException("provider_stream_error", "${provider.displayName} 工具调用验证返回了错误。")
                    }
                    val chunk = OpenAiCompatibleStreamParser.parse(data)
                        ?: throw CloudProviderException("provider_stream_malformed", "${provider.displayName} 工具调用验证返回了损坏的数据流。")
                    visibleChars += chunk.delta?.length ?: 0
                    if (visibleChars > MAX_CONNECTION_TEST_CHARS) throw toolVerificationFailed()
                    chunk.toolCallFragments.forEach(toolCalls::append)
                }
            }
            if (!completed) throw providerStreamInterruptedError(provider, verification = true)
            val toolCall = runCatching { toolCalls.complete() }.getOrNull()
                ?: throw toolVerificationFailed()
            val arguments = runCatching { JSONObject(toolCall.argumentsJson) }.getOrNull()
                ?: throw toolVerificationFailed()
            val keys = arguments.keys().asSequence().toSet()
            if (
                toolCall.originalName != CONNECTION_PROBE_TOOL ||
                keys != setOf("value") ||
                arguments.optString("value") != CONNECTION_PROBE_VALUE
            ) {
                throw toolVerificationFailed()
            }
            true
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            throw if (streamStarted) {
                providerStreamInterruptedError(provider, verification = true)
            } else {
                providerConnectivityError(provider)
            }
        } finally {
            cancellationWatcher.cancel()
            activeCall.compareAndSet(call, null)
        }
    }

    private fun toolVerificationFailed() = CloudProviderException(
        "provider_tool_verification_failed",
        "${provider.displayName} 模型列表和对话可用，但未通过 tool_calls 能力验证。",
        false,
    )

    private fun authenticatedRequest(url: String, apiKey: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")

    /** Enqueue is the exact non-blocking submission boundary used by task leases. */
    private suspend fun Call.awaitResponse(
        withSubmissionLease: suspend (submit: () -> Unit) -> Boolean = { submit -> submit(); true },
    ): okhttp3.Response {
        val responseRef = AtomicReference<okhttp3.Response?>(null)
        val deferred = CompletableDeferred<okhttp3.Response>()
        val callback = object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                deferred.completeExceptionally(e)
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                responseRef.set(response)
                if (!deferred.complete(response)) {
                    responseRef.compareAndSet(response, null)
                    response.close()
                }
            }
        }
        val submitted = try {
            withSubmissionLease { enqueue(callback) }
        } catch (failure: Throwable) {
            deferred.cancel()
            responseRef.getAndSet(null)?.close()
            cancel()
            throw failure
        }
        if (!submitted) {
            deferred.cancel()
            responseRef.getAndSet(null)?.close()
            cancel()
            throw CloudProviderException("request_superseded", "请求已被新的设置取消。", false)
        }
        return try {
            deferred.await().also { responseRef.compareAndSet(it, null) }
        } catch (cancelled: CancellationException) {
            deferred.cancel(cancelled)
            responseRef.getAndSet(null)?.close()
            cancel()
            throw cancelled
        }
    }

    private companion object {
        const val CONNECTION_PROBE_TOOL = "connection_probe"
        const val CONNECTION_PROBE_VALUE = "OK"
        const val MAX_CONNECTION_TEST_CHARS = 128
        const val MAX_PROVIDER_ASSISTANT_CHARS = 65_536
        const val MAX_PROVIDER_REASONING_CHARS = 262_144
    }
}

class CloudProviderException(
    val code: String,
    override val message: String,
    val recoverable: Boolean = true,
) : IllegalStateException(message)

internal data class PreparedOpenAiRequest(
    val payload: JSONObject,
    val originalToolNamesByWireName: Map<String, String>,
)

internal object OpenAiCompatibleRequestFactory {
    fun prepare(provider: CloudAiProvider, request: AiRequest, model: String): PreparedOpenAiRequest {
        val context = CloudRequestBoundary.sanitizeStructuredContext(request.structuredContextJson)
        val tools = OpenAiToolProjection.parse(request.caesarToolsJson)
        val codexImageDataUris = CodexVisionPayloadEncoder.encode(provider, request)
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", AiSystemPolicy.instruction(context)))
            if (context != "{}") {
                put(JSONObject().put("role", "system").put("content", "<private_context>$context</private_context>"))
            }
            val disclosure = request.cloudHealthDisclosure
            if (disclosure is CloudHealthDisclosure.Included) {
                disclosure.summary.toAllowedJsonOrNull()?.let { summary ->
                    put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", "<health_summary>$summary</health_summary>"),
                    )
                }
            }
            encodeConversation(
                provider = provider,
                messages = request.messages,
                deepSeekThinkingTools = provider == CloudAiProvider.DEEPSEEK &&
                    request.mode == AiMode.DEEP &&
                    tools.wireTools.length() > 0,
                codexImageDataUris = codexImageDataUris,
            ).forEach(::put)
        }
        val payload = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
            .put("max_tokens", request.maxOutputTokens.coerceIn(1, 2048))
            .put("messages", messages)
        when (provider) {
            CloudAiProvider.DEEPSEEK -> {
                payload.put("temperature", if (request.mode == AiMode.FAST) .45 else .3)
                payload.put("thinking", JSONObject().put("type", if (request.mode == AiMode.FAST) "disabled" else "enabled"))
                payload.put("reasoning_effort", if (request.mode == AiMode.FAST) "low" else "high")
            }
            // Gemini 3.7's current migration contract deprecates OpenAI-shape sampling fields.
            // Let the model use its supported defaults and control only reasoning effort.
            CloudAiProvider.GOOGLE_GEMINI ->
                payload.put("reasoning_effort", if (request.mode == AiMode.FAST) "low" else "high")
            CloudAiProvider.CODEX ->
                payload.put("reasoning_effort", if (request.mode == AiMode.FAST) "low" else "high")
        }
        if (tools.wireTools.length() > 0) {
            payload.put("tools", tools.wireTools)
            // DeepSeek V4 thinking mode rejects tool_choice, even though the rest of the
            // request follows the OpenAI shape. Gemini accepts the explicit auto choice.
            if (provider != CloudAiProvider.DEEPSEEK) payload.put("tool_choice", "auto")
            payload.put("parallel_tool_calls", false)
        }
        return PreparedOpenAiRequest(payload, tools.originalNamesByWireName)
    }

    private fun encodeConversation(
        provider: CloudAiProvider,
        messages: List<AiConversationMessage>,
        deepSeekThinkingTools: Boolean,
        codexImageDataUris: List<String>,
    ): List<JSONObject> = buildList {
        val visionMessageIndex = if (codexImageDataUris.isEmpty()) {
            -1
        } else {
            messages.indexOfLast { it.role == "user" }
        }
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            if (message.cloudHealthSensitive) {
                index++
                continue
            }
            val envelope = message.takeIf { it.role == "assistant" }
                ?.let { ProviderToolCallEnvelope.decode(it.content) }
            if (envelope != null && !envelope.originalName.startsWith("health.") && envelope.originalName != "memory.list" &&
                messages.getOrNull(index + 1)?.let { it.role == "tool" && !it.cloudHealthSensitive } == true) {
                add(ProviderToolCallEnvelope.toAssistantMessage(envelope))
                add(
                    JSONObject()
                        .put("role", "tool")
                        .put("name", envelope.wireName)
                        .put("tool_call_id", envelope.id)
                        .put("content", messages[index + 1].content.take(MAX_TOOL_RESULT_CHARS)),
                )
                index += 2
                continue
            }
            if (envelope != null) {
                index += if (messages.getOrNull(index + 1)?.role == "tool") 2 else 1
                continue
            }
            if (message.role == "user" || message.role == "assistant") {
                val content: Any = if (index == visionMessageIndex && message.role == "user") {
                    JSONArray()
                        .put(JSONObject().put("type", "text").put("text", message.content))
                        .apply {
                            codexImageDataUris.forEach { dataUri ->
                                put(
                                    JSONObject()
                                        .put("type", "image_url")
                                        .put(
                                            "image_url",
                                            JSONObject()
                                                .put("url", dataUri)
                                                .put("detail", "auto"),
                                        ),
                                )
                            }
                        }
                } else {
                    message.content
                }
                add(
                    JSONObject()
                        .put("role", message.role)
                        .put("content", content)
                        .apply {
                            if (provider == CloudAiProvider.DEEPSEEK && message.role == "assistant") {
                                val reasoning = message.providerReasoningContent
                                    ?.takeIf { it.length <= MAX_REASONING_CONTENT_CHARS }
                                if (reasoning != null || deepSeekThinkingTools) {
                                    put("reasoning_content", reasoning.orEmpty())
                                }
                            }
                        },
                )
            }
            index++
        }
    }

    private const val MAX_TOOL_RESULT_CHARS = 32_768
    private const val MAX_REASONING_CONTENT_CHARS = 262_144
}

/**
 * Encodes only normalized JPEGs attached to the current turn. Saved historical attachments are
 * deliberately ignored, so a later text-only turn never re-uploads earlier images.
 */
internal object CodexVisionPayloadEncoder {
    fun encode(provider: CloudAiProvider, request: AiRequest): List<String> {
        if (
            provider != CloudAiProvider.CODEX ||
            !request.allowCodexImageUpload ||
            request.imagePaths.isEmpty()
        ) return emptyList()
        if (request.imagePaths.size > MAX_IMAGES_PER_TURN) {
            throw CloudProviderException(
                "codex_image_limit",
                "Codex 每次最多可上传 $MAX_IMAGES_PER_TURN 张图片。",
                false,
            )
        }
        val currentUserPaths = request.messages.lastOrNull { it.role == "user" }?.attachmentPaths.orEmpty()
        if (request.imagePaths != currentUserPaths) {
            throw CloudProviderException(
                "codex_image_ownership_invalid",
                "Codex 只能上传当前输入中已导入的图片，请重新选择。",
                false,
            )
        }

        var totalBytes = 0L
        return request.imagePaths.map { rawPath ->
            val file = runCatching { File(rawPath).canonicalFile }.getOrNull()
                ?.takeIf { it.isAbsolute && it.isFile && it.extension.equals("jpg", ignoreCase = true) }
                ?: throw imageUnavailable()
            val declaredSize = file.length()
            if (declaredSize !in 1..MAX_IMAGE_BYTES) throw imageTooLarge()
            if (totalBytes + declaredSize > MAX_TOTAL_IMAGE_BYTES) throw totalImagesTooLarge()
            val bytes = runCatching { file.readBounded(MAX_IMAGE_BYTES) }.getOrElse {
                if (it is CloudProviderException) throw it
                throw imageUnavailable()
            }
            if (totalBytes + bytes.size > MAX_TOTAL_IMAGE_BYTES) throw totalImagesTooLarge()
            if (!bytes.isNormalizedJpeg()) {
                throw CloudProviderException(
                    "codex_image_invalid",
                    "Codex 只会上传设备端已去除元数据的 JPEG 图片。请重新选择图片。",
                    false,
                )
            }
            totalBytes += bytes.size.toLong()
            "data:image/jpeg;base64,${bytes.toByteString().base64()}"
        }
    }

    private fun File.readBounded(limit: Long): ByteArray = inputStream().buffered().use { input ->
        val output = ByteArrayOutputStream(length().coerceAtMost(limit).toInt())
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > limit) throw imageTooLarge()
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private fun ByteArray.isNormalizedJpeg(): Boolean =
        size >= 4 &&
            this[0] == JPEG_START_FIRST &&
            this[1] == JPEG_START_SECOND &&
            this[lastIndex - 1] == JPEG_END_FIRST &&
            this[lastIndex] == JPEG_END_SECOND

    private fun imageUnavailable() = CloudProviderException(
        "codex_image_unavailable",
        "Codex 无法读取当前图片，请重新选择后再试。",
        false,
    )

    private fun imageTooLarge() = CloudProviderException(
        "codex_image_too_large",
        "单张 Codex 图片处理后不能超过 4 MiB，请重新选择。",
        false,
    )

    private fun totalImagesTooLarge() = CloudProviderException(
        "codex_images_too_large",
        "本次 Codex 图片总大小不能超过 8 MiB，请减少图片。",
        false,
    )

    private const val MAX_IMAGES_PER_TURN = 4
    private const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
    private const val MAX_TOTAL_IMAGE_BYTES = 8L * 1024 * 1024
    private const val JPEG_START_FIRST: Byte = -1
    private const val JPEG_START_SECOND: Byte = -40
    private const val JPEG_END_FIRST: Byte = -1
    private const val JPEG_END_SECOND: Byte = -39
}

internal object CloudRequestBoundary {
    fun sanitizeStructuredContext(raw: String): String = runCatching {
        sanitizeObject(JSONObject(raw)).toString()
    }.getOrDefault("{}")

    private fun sanitizeObject(source: JSONObject): JSONObject = JSONObject().apply {
        source.keys().asSequence().forEach { key ->
            if (!isSensitiveKey(normalizeKey(key))) put(key, sanitizeValue(source.opt(key)))
        }
    }

    private fun sanitizeArray(source: JSONArray): JSONArray = JSONArray().apply {
        repeat(source.length()) { index -> put(sanitizeValue(source.opt(index))) }
    }

    private fun sanitizeValue(value: Any?): Any? = when (value) {
        is JSONObject -> sanitizeObject(value)
        is JSONArray -> sanitizeArray(value)
        else -> value
    }

    private fun normalizeKey(key: String): String = key.lowercase().filter(Char::isLetterOrDigit)

    private fun isSensitiveKey(key: String): Boolean =
        key in SENSITIVE_KEYS || SENSITIVE_KEY_FRAGMENTS.any(key::contains)

    private val SENSITIVE_KEYS = setOf(
        "confirmedmemories",
        "health",
        "healthdata",
        "healthsnapshot",
        "healthsummary",
        "rawhealthdata",
        "band",
        "banddata",
        "bandlive",
        "deviceid",
        "deviceids",
        "originpackages",
        "sourceids",
        "source",
        "sources",
        "imageocr",
        "ocrtext",
        "steps",
        "stepcount",
        "steptotal",
        "distance",
        "distancemeters",
        "calories",
        "activecalories",
        "activecalorieskcal",
        "activityminutes",
        "sleep",
        "sleepminutes",
        "sleepstages",
        "heartrate",
        "averageheartratebpm",
        "bloodoxygen",
        "oxygensaturation",
        "averageoxygensaturationpercent",
        "spo2",
        "stress",
        "averagestressscore",
        "workout",
        "workouts",
        "workoutcount",
        "samples",
        "intervals",
    )

    private val SENSITIVE_KEY_FRAGMENTS = setOf(
        "health",
        "mifitness",
        "banddata",
        "bandsnapshot",
        "deviceid",
        "deviceidentifier",
        "devicemac",
        "macaddress",
        "originpackage",
        "sourceid",
    )
}

private data class OpenAiToolProjection(
    val wireTools: JSONArray,
    val originalNamesByWireName: Map<String, String>,
) {
    companion object {
        fun parse(raw: String): OpenAiToolProjection {
            val tools = JSONArray()
            val names = linkedMapOf<String, String>()
            val definitions = runCatching { JSONArray(raw) }.getOrElse { return OpenAiToolProjection(tools, names) }
            repeat(minOf(definitions.length(), MAX_TOOLS)) { index ->
                val definition = definitions.optJSONObject(index) ?: return@repeat
                val originalName = definition.optString("name")
                if (!ORIGINAL_TOOL_NAME.matches(originalName) || originalName.startsWith("health.") || originalName == "memory.list") return@repeat
                val wireName = wireName(originalName)
                if (names.putIfAbsent(wireName, originalName) != null) return@repeat
                val properties = JSONObject()
                val required = JSONArray()
                val parameters = definition.optJSONArray("parameters") ?: JSONArray()
                repeat(parameters.length()) parameterLoop@ { parameterIndex ->
                    val parameter = parameters.optJSONObject(parameterIndex) ?: return@parameterLoop
                    val name = parameter.optString("name")
                    val type = parameter.optString("type")
                    if (!PARAMETER_NAME.matches(name) || type !in PARAMETER_TYPES) return@parameterLoop
                    properties.put(
                        name,
                        JSONObject()
                            .put("type", type)
                            .put("description", parameter.optString("description").take(512)),
                    )
                    if (parameter.optBoolean("required")) required.put(name)
                }
                val schema = JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", required)
                    .put("additionalProperties", false)
                tools.put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", wireName)
                                .put("description", definition.optString("description").take(512))
                                .put("parameters", schema),
                        ),
                )
            }
            return OpenAiToolProjection(tools, names)
        }

        private fun wireName(originalName: String): String {
            val normalized = originalName.replace('.', '_')
            if (normalized.length <= 64) return normalized
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(originalName.toByteArray())
                .take(5)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "${normalized.take(53)}_$digest"
        }

        private const val MAX_TOOLS = 128
        private val ORIGINAL_TOOL_NAME = Regex("[a-z][a-z0-9_.-]{2,80}")
        private val PARAMETER_NAME = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
        private val PARAMETER_TYPES = setOf("string", "integer", "number", "boolean", "object", "array")
    }
}

internal data class OpenAiToolCallFragment(
    val index: Int?,
    val id: String?,
    val wireName: String?,
    val argumentsFragment: String?,
    val thoughtSignature: String?,
)

internal data class OpenAiCompatibleChunk(
    val delta: String?,
    val reasoningContentDelta: String?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val toolCallFragments: List<OpenAiToolCallFragment>,
)

internal object OpenAiCompatibleStreamParser {
    fun containsProviderError(raw: String): Boolean = runCatching {
        JSONObject(raw).optJSONObject("error") != null
    }.getOrDefault(false)

    fun parse(raw: String): OpenAiCompatibleChunk? {
        if (raw.isBlank() || raw == "[DONE]") return null
        return runCatching {
            val packet = JSONObject(raw)
            val choice = packet.optJSONArray("choices")?.optJSONObject(0)
            val delta = choice?.optJSONObject("delta")
            val usage = packet.optJSONObject("usage")
            val fragments = buildList {
                val calls = delta?.optJSONArray("tool_calls") ?: JSONArray()
                repeat(calls.length()) { index ->
                    val call = calls.optJSONObject(index) ?: return@repeat
                    val function = call.optJSONObject("function")
                    val signature = call.optJSONObject("extra_content")
                        ?.optJSONObject("google")
                        ?.optString("thought_signature")
                        ?.takeIf(String::isNotBlank)
                    add(
                        OpenAiToolCallFragment(
                            index = call.optInt("index").takeIf { call.has("index") }
                                ?: index.takeIf { calls.length() > 1 },
                            id = call.optString("id").takeIf(String::isNotBlank),
                            wireName = function?.optString("name")?.takeIf(String::isNotBlank),
                            argumentsFragment = function
                                ?.takeUnless { it.isNull("arguments") }
                                ?.optString("arguments")
                                ?.takeIf(String::isNotEmpty),
                            thoughtSignature = signature,
                        ),
                    )
                }
            }
            OpenAiCompatibleChunk(
                delta = delta
                    ?.takeUnless { it.isNull("content") }
                    ?.optString("content")
                    ?.takeIf(String::isNotEmpty),
                reasoningContentDelta = delta
                    ?.takeUnless { it.isNull("reasoning_content") }
                    ?.optString("reasoning_content")
                    ?.takeIf(String::isNotEmpty),
                inputTokens = usage?.takeIf { it.has("prompt_tokens") }?.optLong("prompt_tokens"),
                outputTokens = usage?.takeIf { it.has("completion_tokens") }?.optLong("completion_tokens"),
                toolCallFragments = fragments,
            )
        }.getOrNull()
    }
}

internal data class OpenAiProviderToolCall(
    val id: String,
    val wireName: String,
    val originalName: String,
    val argumentsJson: String,
    val thoughtSignature: String?,
    val assistantContent: String = "",
    val reasoningContent: String? = null,
)

internal class OpenAiToolCallAccumulator(
    private val provider: CloudAiProvider,
    private val originalNamesByWireName: Map<String, String>,
) {
    private val calls = linkedMapOf<Int, MutableToolCall>()

    fun append(fragment: OpenAiToolCallFragment) {
        val key = fragment.index ?: when {
            fragment.id != null -> calls.entries.firstOrNull { it.value.id == fragment.id }?.key
            calls.size == 1 -> calls.keys.first()
            else -> null
        } ?: calls.size
        val call = calls.getOrPut(key) { MutableToolCall() }
        fragment.id?.let { call.id = it }
        fragment.wireName?.let { call.wireName = it }
        fragment.argumentsFragment?.let {
            if (call.arguments.length + it.length > MAX_ARGUMENT_CHARS) {
                throw CloudProviderException("invalid_tool_call", "模型生成的工具参数过大。", false)
            }
            call.arguments.append(it)
        }
        if (provider == CloudAiProvider.GOOGLE_GEMINI && fragment.thoughtSignature != null) {
            if (fragment.thoughtSignature.length > MAX_SIGNATURE_CHARS) {
                throw CloudProviderException("invalid_tool_call", "模型返回的工具签名过大。", false)
            }
            call.thoughtSignature = fragment.thoughtSignature
        }
    }

    fun complete(
        assistantContent: String = "",
        reasoningContent: String? = null,
    ): OpenAiProviderToolCall? {
        if (calls.isEmpty()) return null
        if (calls.size != 1) {
            throw CloudProviderException("parallel_tool_calls_unsupported", "模型同时请求了多个工具；本轮已安全停止。")
        }
        val call = calls.values.single()
        val wireName = call.wireName.takeIf(WIRE_TOOL_NAME::matches)
            ?: throw CloudProviderException("invalid_tool_call", "模型返回了无效的工具名称。", false)
        val id = call.id.takeIf(TOOL_CALL_ID::matches)
            ?: throw CloudProviderException("invalid_tool_call", "模型返回了无效的工具调用标识。", false)
        val arguments = call.arguments.toString().ifBlank { "{}" }
        val originalName = originalNamesByWireName[wireName]
            ?: throw CloudProviderException("tool_not_projected", "模型请求了本轮未授权的工具。", false)
        return OpenAiProviderToolCall(
            id = id,
            wireName = wireName,
            originalName = originalName,
            argumentsJson = arguments,
            thoughtSignature = call.thoughtSignature,
            assistantContent = assistantContent,
            reasoningContent = reasoningContent.takeIf { provider == CloudAiProvider.DEEPSEEK },
        )
    }

    private class MutableToolCall {
        var id: String = ""
        var wireName: String = ""
        val arguments = StringBuilder()
        var thoughtSignature: String? = null
    }

    private companion object {
        const val MAX_ARGUMENT_CHARS = 65_536
        const val MAX_SIGNATURE_CHARS = 65_536
        val WIRE_TOOL_NAME = Regex("[A-Za-z0-9_-]{1,64}")
        val TOOL_CALL_ID = Regex("[A-Za-z0-9_.:-]{1,200}")
    }
}

internal object ProviderToolCallEnvelope {
    private const val TYPE = "campusai_provider_tool_call_v1"

    fun encode(call: OpenAiProviderToolCall): String = JSONObject()
        .put("type", TYPE)
        .put("id", call.id)
        .put("wireName", call.wireName)
        .put("originalName", call.originalName)
        .put("arguments", call.argumentsJson)
        .put("assistantContent", call.assistantContent)
        .apply { call.thoughtSignature?.let { put("thoughtSignature", it) } }
        .apply { call.reasoningContent?.let { put("reasoningContent", it) } }
        .toString()

    fun decode(raw: String): OpenAiProviderToolCall? = runCatching {
        val value = JSONObject(raw)
        if (value.optString("type") != TYPE) return@runCatching null
        val id = value.optString("id")
        val wireName = value.optString("wireName")
        val originalName = value.optString("originalName")
        val arguments = value.optString("arguments")
        val assistantContent = value.optString("assistantContent")
        if (!ID_PATTERN.matches(id) || !WIRE_NAME_PATTERN.matches(wireName) || !ORIGINAL_NAME_PATTERN.matches(originalName)) return@runCatching null
        if (arguments.length > 65_536) return@runCatching null
        if (assistantContent.length > MAX_ASSISTANT_CONTENT_CHARS) return@runCatching null
        OpenAiProviderToolCall(
            id = id,
            wireName = wireName,
            originalName = originalName,
            argumentsJson = arguments,
            thoughtSignature = value.optString("thoughtSignature").takeIf(String::isNotBlank)?.also {
                if (it.length > MAX_PROVIDER_STATE_CHARS) return@runCatching null
            },
            assistantContent = assistantContent,
            reasoningContent = value.optString("reasoningContent").takeIf(String::isNotBlank)?.also {
                if (it.length > MAX_PROVIDER_STATE_CHARS) return@runCatching null
            },
        )
    }.getOrNull()

    fun toAssistantMessage(call: OpenAiProviderToolCall): JSONObject = with(call) { JSONObject()
        .put("role", "assistant")
        .put("content", assistantContent)
        .apply { reasoningContent?.let { put("reasoning_content", it) } }
        .put(
            "tool_calls",
            JSONArray().put(
                JSONObject()
                    .put("id", id)
                    .put("type", "function")
                    .put("function", JSONObject().put("name", wireName).put("arguments", argumentsJson))
                    .apply {
                        thoughtSignature?.let { signature ->
                            put("extra_content", JSONObject().put("google", JSONObject().put("thought_signature", signature)))
                        }
                    },
            ),
        ) }

    private val ID_PATTERN = Regex("[A-Za-z0-9_.:-]{1,200}")
    private val WIRE_NAME_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    private val ORIGINAL_NAME_PATTERN = Regex("[a-z][a-z0-9_.-]{2,80}")
    private const val MAX_ASSISTANT_CONTENT_CHARS = 65_536
    private const val MAX_PROVIDER_STATE_CHARS = 262_144
}

internal object OpenAiCompatibleModelParser {
    fun parse(provider: CloudAiProvider, raw: String): List<CloudProviderModel> = runCatching {
        val data = JSONObject(raw).optJSONArray("data") ?: return@runCatching emptyList()
        buildList {
            repeat(data.length()) { index ->
                val row = data.optJSONObject(index) ?: return@repeat
                val id = provider.normalizeModelId(row.optString("id"))
                if (provider.acceptsModelId(id)) add(CloudProviderModel(id))
            }
        }.distinctBy(CloudProviderModel::id)
    }.getOrDefault(emptyList())
}

internal fun parseLiveModelCatalog(provider: CloudAiProvider, raw: String): List<CloudProviderModel> {
    val payload = try {
        JSONObject(raw)
    } catch (_: Exception) {
        throw CloudProviderException(
            "provider_response_invalid",
            "${provider.displayName} 返回了无法识别的模型列表。",
            false,
        )
    }
    if (payload.optJSONArray("data") == null) {
        throw CloudProviderException(
            "provider_response_invalid",
            "${provider.displayName} 返回的模型列表结构无效。",
            false,
        )
    }
    return OpenAiCompatibleModelParser.parse(provider, raw)
}

private fun providerError(provider: CloudAiProvider, status: Int): CloudProviderException = when (status) {
    400 -> CloudProviderException("provider_request_rejected", "${provider.displayName} 拒绝了当前请求。请检查模型设置后重试。", false)
    401, 403 -> CloudProviderException("provider_key_invalid", "个人 ${provider.displayName} Key 无效、无权限或已被撤销。", false)
    402 -> CloudProviderException("provider_balance_empty", "个人 ${provider.displayName} 账户余额或配额不足。", false)
    404 -> CloudProviderException("model_unavailable", "所选 ${provider.displayName} 模型不存在或当前账户不可用。", false)
    429 -> CloudProviderException("provider_rate_limited", "个人 ${provider.displayName} 账户请求过于频繁，请稍后重试。")
    in 500..599 -> if (provider == CloudAiProvider.CODEX) {
        providerConnectivityError(provider)
    } else {
        CloudProviderException("provider_unavailable", "${provider.displayName} 服务暂时不可用（$status），请稍后重试。")
    }
    else -> CloudProviderException("provider_error", "个人 ${provider.displayName} 请求失败（$status）。请检查 Key 和账户状态后重试。")
}

internal fun providerConnectivityError(provider: CloudAiProvider): CloudProviderException =
    if (provider == CloudAiProvider.CODEX) {
        CloudProviderException(
            "provider_unavailable",
            "无法连接 Codex。请检查：手机是否开启 Tailscale；电脑是否开机；CPA 与 Clash 是否正在运行。",
        )
    } else {
        CloudProviderException("provider_unavailable", "无法连接 ${provider.displayName}。请检查网络后重试。")
    }

private fun providerStreamInterruptedError(
    provider: CloudAiProvider,
    verification: Boolean = false,
): CloudProviderException {
    val phase = if (verification) "真实对话验证" else "数据流"
    val recovery = if (provider == CloudAiProvider.CODEX) {
        "请检查：手机是否开启 Tailscale；电脑是否开机；CPA 与 Clash 是否正在运行。"
    } else {
        "请检查网络后重试。"
    }
    return CloudProviderException(
        "provider_stream_interrupted",
        "${provider.displayName} $phase 在完成前中断。$recovery",
    )
}

internal fun defaultCloudHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(120, TimeUnit.SECONDS)
    .readTimeout(130, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS)
    .callTimeout(0, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
