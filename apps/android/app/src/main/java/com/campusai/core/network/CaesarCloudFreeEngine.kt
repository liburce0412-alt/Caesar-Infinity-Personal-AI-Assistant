package com.campusai.core.network

import com.campusai.BuildConfig
import com.campusai.core.ai.AiEngine
import com.campusai.core.ai.AiEvent
import com.campusai.core.ai.AiExecutionEngine
import com.campusai.core.ai.AiRequest
import com.campusai.core.ai.AiRoutingException
import com.campusai.core.ai.CloudHealthDisclosure
import com.campusai.core.ai.ResolvedExecution
import com.campusai.core.model.AiMode
import com.campusai.core.model.AiProvider
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import okio.ByteString.Companion.toByteString

class CaesarCloudFreeEngine internal constructor(
    private val baseUrl: () -> String,
    private val sessionToken: suspend (String) -> String,
    private val sessionCurrent: (String) -> Boolean,
    private val client: OkHttpClient,
    private val buildType: String = "release",
    private val testEndpoint: String = "",
) : AiEngine {
    constructor() : this(
        baseUrl = { SupabaseClient.supabaseUrl },
        sessionToken = SupabaseClient::aiSessionToken,
        sessionCurrent = { it == SupabaseClient.userJwt },
        client = http,
        buildType = BuildConfig.BUILD_TYPE,
        testEndpoint = BuildConfig.CAESAR_CLOUD_TEST_ENDPOINT,
    )

    private val active = AtomicReference<Call?>(null)

    override fun stream(request: AiRequest): Flow<AiEvent> = stream(request) { submit -> submit(); true }

    internal fun stream(
        request: AiRequest,
        withSubmissionLease: suspend (submit: () -> Unit) -> Boolean,
    ): Flow<AiEvent> = channelFlow {
        try {
            withContext(Dispatchers.IO) {
                val payload = cloudFreePayload(request)
                val token = sessionToken(request.ownerUserId)
                if (!sessionCurrent(token)) throw sessionChanged()
                val endpoint = caesarCloudChatEndpoint(baseUrl(), buildType, testEndpoint)
                val call = client.newCall(Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "text/event-stream")
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build())
                active.set(call)
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.awaitCloudResponse(withSubmissionLease).use { response ->
                        if (!response.isSuccessful) {
                            val error = runCatching { JSONObject(response.peekBody(8192).string()).optJSONObject("error") }.getOrNull()
                            if (payload.has("toolProtocol") && error?.optString("code") == "invalid_request") {
                                throw AiRoutingException("cloud_tools_unavailable", "免费云端尚未开通当前工具协议，请稍后重试。")
                            }
                            throw AiRoutingException(error?.optString("code")?.takeIf { it.isNotBlank() } ?: "cloud_unavailable",
                                error?.optString("message")?.takeIf { it.isNotBlank() }?.take(240) ?: "Caesar cloud free 暂不可用，请稍后重试。")
                        }
                        if (response.header("Content-Type")?.startsWith("text/event-stream") != true) {
                            throw AiRoutingException("cloud_not_configured", "Caesar cloud free 尚未开放，请稍后重试。")
                        }
                        val source = response.body?.source() ?: error("empty_stream")
                        val reader = SseEventReader(source)
                        var hasMeta = false
                        var completed = false
                        var chars = 0
                        val assistantText = StringBuilder()
                        val projectedNames = payload.optJSONArray("tools")?.let { names ->
                            (0 until names.length()).mapTo(mutableSetOf()) { names.getString(it) }
                        }.orEmpty()
                        var toolCall: OpenAiProviderToolCall? = null
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            if (!sessionCurrent(token)) throw sessionChanged()
                            val raw = reader.next()?.data ?: break
                            val event = JSONObject(raw)
                            when (event.getString("type")) {
                                "meta" -> {
                                    val actualModel = event.getString("model")
                                    val expectedModel = if (payload.optJSONArray("images")?.length() == 1) "glm-4v-flash" else MODEL
                                    check(!hasMeta && actualModel == expectedModel)
                                    if (payload.has("toolProtocol") && event.optInt("toolProtocol") != CaesarCloudFreeTools.PROTOCOL) {
                                        throw AiRoutingException("cloud_tools_unavailable", "免费云端尚未开通当前工具协议，请稍后重试。")
                                    }
                                    hasMeta = true
                                    send(AiEvent.Meta(ResolvedExecution(AiProvider.CAESAR_CLOUD_FREE, actualModel,
                                        AiExecutionEngine.CLOUD_OPENAI_COMPATIBLE, event.getString("requestId"))))
                                }
                                "status" -> {
                                    check(hasMeta)
                                    send(AiEvent.Status(event.getString("stage"), event.optLong("elapsedMs")))
                                }
                                "delta" -> {
                                    check(hasMeta && toolCall == null)
                                    val text = event.getString("text")
                                    chars += text.length
                                    check(chars <= 24_000)
                                    assistantText.append(text)
                                    send(AiEvent.Delta(text))
                                }
                                "tool_call" -> {
                                    check(hasMeta && payload.has("toolProtocol") && toolCall == null)
                                    toolCall = CaesarCloudFreeTools.decodeCall(event, projectedNames, assistantText.toString())
                                }
                                "error" -> throw AiRoutingException(event.getString("code"), event.getString("message").take(240))
                                "done" -> {
                                    check(hasMeta && (chars > 0 || toolCall != null))
                                    val usage = event.optJSONObject("usage")
                                    val callRequest = toolCall
                                    if (callRequest != null) send(AiEvent.ToolCallRequested(callRequest.originalName,
                                        callRequest.argumentsJson, ProviderToolCallEnvelope.encode(callRequest)))
                                    else send(AiEvent.Done(event.optLong("elapsedMs"), usage?.optLong("inputTokens"), usage?.optLong("outputTokens")))
                                    completed = true
                                    break
                                }
                                else -> error("invalid_cloud_event")
                            }
                        }
                        if (!completed) throw AiRoutingException("cloud_interrupted", "回复在完成前中断，请重试。")
                    }
                } finally {
                    watcher.cancel()
                    call.cancel()
                    active.compareAndSet(call, null)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AiRoutingException) {
            send(AiEvent.Error(failure.code, failure.message))
        } catch (_: Exception) {
            send(AiEvent.Error("cloud_interrupted", "Caesar cloud free 连接中断，请检查网络后重试。"))
        }
    }

    override fun cancel() { active.getAndSet(null)?.cancel() }

    private companion object {
        const val MODEL = "glm-4-flash-250414"
        val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(125, TimeUnit.SECONDS).callTimeout(135, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
    }
}

private fun sessionChanged() = AiRoutingException("authentication_required", "登录状态已变化，请重新打开对话。")

private suspend fun Call.awaitCloudResponse(
    withSubmissionLease: suspend (submit: () -> Unit) -> Boolean,
): Response {
    val responseRef = AtomicReference<Response?>(null)
    val deferred = CompletableDeferred<Response>()
    val callback = object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            deferred.completeExceptionally(e)
        }
        override fun onResponse(call: Call, response: Response) {
            responseRef.set(response)
            if (!deferred.complete(response)) {
                responseRef.compareAndSet(response, null)
                response.close()
            }
        }
    }
    try {
        if (!withSubmissionLease { enqueue(callback) }) {
            throw AiRoutingException("request_superseded", "请求已被新的设置取消。")
        }
        return deferred.await().also { responseRef.compareAndSet(it, null) }
    } catch (failure: Throwable) {
        deferred.cancel()
        responseRef.getAndSet(null)?.close()
        cancel()
        throw failure
    }
}

internal fun cloudFreePayload(request: AiRequest): JSONObject {
    val hasImages = request.imagePaths.isNotEmpty() || request.messages.any {
        it.attachmentPaths.isNotEmpty() || it.attachmentRefs.isNotEmpty()
    }
    if (request.requiresLocal || (hasImages && !request.allowCloudFreeImageUpload)) {
        throw AiRoutingException("local_required", "本机内容不能自动发送到 Caesar cloud free。")
    }
    if (request.mode != AiMode.FAST) {
        throw AiRoutingException("unsupported_mode", "免费云端仅支持快速回答，请切换回答方式。")
    }
    val images = cloudFreeImages(request)
    val context = if (images.isEmpty()) com.campusai.core.ai.AiContextBudget.fitOptionalDetails(
        JSONObject(CloudRequestBoundary.sanitizeStructuredContext(request.structuredContextJson)),
    ) else JSONObject()
    if (images.isEmpty() && request.cloudHealthDisclosure is CloudHealthDisclosure.Included) {
        request.cloudHealthDisclosure.summary.toAllowedJsonOrNull()?.let {
            context.put("consentedDailyHealthSummary", it)
        }
    }
    if (context.toString().length > 12_000) throw AiRoutingException("request_too_large", "对话依据过长，请减少所选内容。")
    // Short recent history improves time to first token. Never replay health turns or tool envelopes.
    val lastUser = request.messages.indexOfLast { it.role == "user" }
    val replay = CaesarCloudFreeTools.currentTurnReplay(request)
    val toolNames = CaesarCloudFreeTools.projectedNames(request)
    val history = request.messages.take(lastUser + 1)
    val candidates = (if (images.isEmpty()) history else history.takeLast(1)).filter {
        it.role in listOf("user", "assistant") && it.content.isNotBlank() && !it.cloudHealthSensitive &&
            ProviderToolCallEnvelope.decode(it.content) == null
    }.takeLast(12)
    var chars = 0
    val recent = candidates.asReversed().takeWhile { message ->
        (chars + message.content.length <= 12_000).also { if (it) chars += message.content.length }
    }.asReversed()
    if (recent.isEmpty() || recent.last().role != "user") throw AiRoutingException("request_too_large", "请缩短当前问题后重试。")
    return JSONObject().put("mode", "fast")
        .put("images", JSONArray(images))
        .put("maxOutputTokens", request.maxOutputTokens.coerceIn(1, 2048))
        .put("context", context)
        .put("messages", JSONArray(recent.map { JSONObject().put("role", it.role).put("content", it.content) } + replay))
        .apply {
            if (toolNames.isNotEmpty() || replay.isNotEmpty()) {
                put("toolProtocol", CaesarCloudFreeTools.PROTOCOL)
                put("tools", JSONArray(toolNames))
            }
        }
}

private fun cloudFreeImages(request: AiRequest): List<String> {
    if (request.imagePaths.isEmpty()) return emptyList()
    if (!request.allowCloudFreeImageUpload || request.imagePaths.size != 1 ||
        request.imagePaths != request.messages.lastOrNull { it.role == "user" }?.attachmentPaths) {
        throw AiRoutingException("cloud_image_limit", "Caesar cloud free 每次只发送当前添加的一张图片，请减少图片后重试。")
    }
    val limit = 4 * 1024 * 1024
    val file = File(request.imagePaths.single()).canonicalFile
    if (!file.isFile || !file.extension.equals("jpg", true) || file.length() !in 4L..limit.toLong()) {
        throw AiRoutingException("cloud_image_invalid", "请重新选择图片，处理后的 JPEG 不能超过 4 MiB。")
    }
    val bytes = file.inputStream().buffered().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw AiRoutingException("cloud_image_limit", "图片过大，请裁剪后重试。")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    if (bytes.size < 4 || bytes[0] != (-1).toByte() || bytes[1] != (-40).toByte() ||
        bytes[bytes.size - 2] != (-1).toByte() || bytes.last() != (-39).toByte()) {
        throw AiRoutingException("cloud_image_invalid", "图片格式无效，请重新选择。")
    }
    return listOf("data:image/jpeg;base64,${bytes.toByteString().base64()}")
}
