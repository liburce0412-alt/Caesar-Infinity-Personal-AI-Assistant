package com.campusai.core.network

import com.campusai.core.ai.AiEvent
import com.campusai.core.ai.AiRequest
import com.campusai.core.ai.CloudAiProvider
import com.campusai.core.ai.CloudDailyHealthSummary
import com.campusai.core.ai.CloudHealthDisclosure
import com.campusai.core.ai.ResolvedExecution
import com.campusai.core.automation.AutoMessageBatch
import com.campusai.core.automation.HealthAutoMessageClient
import com.campusai.core.automation.HealthTaskException
import com.campusai.core.model.AiConversationMessage
import com.campusai.core.model.AiMode
import com.campusai.core.security.PersonalAiProviderStore
import com.campusai.core.model.AiProvider
import com.campusai.core.ai.AiRoutingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect

class PersonalCloudHealthAutoMessageClient(
    private val store: PersonalAiProviderStore,
    private val localRuntime: (suspend () -> com.campusai.core.localai.LocalAiRuntime)? = null,
) : HealthAutoMessageClient {
    override suspend fun validateLocal(modelId: String): Result<Unit> = try {
        val runtime = localRuntime?.invoke() ?: throw HealthTaskException("local_model_unavailable", "本地模型暂不可用。")
        if (runtime.manager.stateFor(modelId) != com.campusai.core.model.LocalModelState.Ready) {
            throw HealthTaskException("local_model_unavailable", "请先在 AI 运行方式中下载并校验所选本地模型。")
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { Result.failure(failure) }

    override suspend fun generateLocal(
        modelId: String,
        summary: CloudDailyHealthSummary,
        withSubmissionLease: suspend (submit: () -> Unit) -> Boolean,
    ): Result<AutoMessageBatch> = try {
        validateLocal(modelId).getOrThrow()
        val runtime = checkNotNull(localRuntime).invoke()
        kotlinx.coroutines.coroutineScope {
            val task = async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                kotlinx.coroutines.withTimeout(120_000L) {
                    val output = StringBuilder()
                    var completed = false
                    var execution: ResolvedExecution? = null
                    val localRequest = request(summary).copy(
                        requiresLocal = true, localModelId = modelId,
                        maxOutputTokens = 160,
                        structuredContextJson = org.json.JSONObject().put("task", "health_auto_message")
                            .put("health_summary", summary.toAllowedJson()).toString(),
                        cloudHealthDisclosure = CloudHealthDisclosure.Excluded,
                    )
                    runtime.engine.stream(localRequest).collect { event ->
                        when (event) {
                            is AiEvent.Meta -> {
                                if (event.provider != AiProvider.LOCAL) throw HealthTaskException("task_model_mismatch", "本地模型校验失败。")
                                execution = event.execution
                            }
                            is AiEvent.Delta -> {
                                if (output.length + event.text.length > MAX_OUTPUT_CHARS) throw HealthTaskException("task_output_too_large", "自动消息过长。")
                                output.append(event.text)
                            }
                            is AiEvent.Done -> completed = true
                            is AiEvent.Error -> throw HealthTaskException(event.code, event.message)
                            is AiEvent.ToolCallRequested -> throw HealthTaskException("task_tool_call_rejected", "自动消息不允许调用工具。")
                            else -> Unit
                        }
                    }
                    if (!completed) throw HealthTaskException("task_stream_incomplete", "自动消息未完成。")
                    AutoMessageBatch.parse(output.toString()).getOrElse {
                        throw HealthTaskException("task_output_invalid", "本地模型返回的短消息格式无效。")
                    }.copy(execution = execution ?: throw HealthTaskException("task_execution_missing", "缺少执行标识。"))
                }
            }
            if (!withSubmissionLease { task.start() }) {
                task.cancel()
                throw HealthTaskException("request_superseded", "设置已改变，本次任务已取消。")
            }
            Result.success(task.await())
        }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        Result.failure(HealthTaskException("local_timeout", "本地模型处理超时。"))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { Result.failure(failure) }

    override suspend fun validateCaesarCloudFree(ownerUserId: String): Result<Unit> = try {
        SupabaseClient.aiSessionToken(ownerUserId)
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.failure(HealthTaskException("authentication_required", "请先登录 Caesar 后启用免费云端。"))
    }

    override suspend fun generateCaesarCloudFree(
        ownerUserId: String,
        summary: CloudDailyHealthSummary,
        withSubmissionLease: suspend (submit: () -> Unit) -> Boolean,
    ): Result<AutoMessageBatch> {
        val engine = CaesarCloudFreeEngine()
        return try {
            val output = StringBuilder()
            var completed = false
            var execution: ResolvedExecution? = null
            engine.stream(request(summary).copy(ownerUserId = ownerUserId), withSubmissionLease).collect { event ->
                when (event) {
                    is AiEvent.Meta -> {
                        if (event.provider != AiProvider.CAESAR_CLOUD_FREE ||
                            event.model != com.campusai.core.automation.ScheduledTaskConfig.CLOUD_FREE_MODEL) {
                            throw HealthTaskException("task_model_mismatch", "免费云端模型校验失败。")
                        }
                        execution = event.execution
                    }
                    is AiEvent.Delta -> {
                        if (output.length + event.text.length > MAX_OUTPUT_CHARS) {
                            throw HealthTaskException("task_output_too_large", "自动消息过长。")
                        }
                        output.append(event.text)
                    }
                    is AiEvent.Done -> completed = true
                    is AiEvent.Error -> throw HealthTaskException(event.code, event.message)
                    is AiEvent.ToolCallRequested -> throw HealthTaskException("task_tool_call_rejected", "自动消息不允许调用工具。")
                    else -> Unit
                }
            }
            if (!completed) throw HealthTaskException("task_stream_incomplete", "自动消息未完成。")
            val parsed = AutoMessageBatch.parse(output.toString()).getOrElse {
                throw HealthTaskException("task_output_invalid", "自动消息格式无效。")
            }
            Result.success(parsed.copy(execution = execution ?: throw HealthTaskException("task_execution_missing", "缺少执行标识。")))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(if (failure is AiRoutingException) HealthTaskException(failure.code, failure.message) else failure)
        } finally { engine.cancel() }
    }

    override suspend fun validate(provider: CloudAiProvider, modelId: String): Result<Unit> = runCatching {
        val lockedModel = requireLockedModel(provider, modelId)
        val client = lockedClient(provider, lockedModel)
        try {
            validateLockedConnection(client, lockedModel)
        } catch (failure: CloudProviderException) {
            throw HealthTaskException(failure.code, failure.message)
        } finally {
            client.cancel()
        }
    }

    override suspend fun generate(
        provider: CloudAiProvider,
        modelId: String,
        summary: CloudDailyHealthSummary,
        withSubmissionLease: suspend (submit: () -> Unit) -> Boolean,
    ): Result<AutoMessageBatch> = runCatching {
        val lockedModel = requireLockedModel(provider, modelId)
        val client = lockedClient(provider, lockedModel)
        try {
            validateLockedConnection(client, lockedModel)
            val output = StringBuilder()
            var completed = false
            var execution: ResolvedExecution? = null
            client.stream(
                request = request(summary),
                withSubmissionLease = withSubmissionLease,
                onEvent = { event ->
                    when (event) {
                        is AiEvent.Meta -> {
                            if (event.provider != provider.appProvider || event.model != lockedModel) {
                                throw HealthTaskException("task_model_mismatch", "定时任务模型锁定校验失败。")
                            }
                            execution = event.execution
                        }
                        is AiEvent.Delta -> {
                            if (output.length + event.text.length > MAX_OUTPUT_CHARS) {
                                throw HealthTaskException("task_output_too_large", "AI 返回的自动消息过长。")
                            }
                            output.append(event.text)
                        }
                        is AiEvent.Done -> completed = true
                        is AiEvent.Error -> throw HealthTaskException(event.code, event.message)
                        is AiEvent.ToolCallRequested -> throw HealthTaskException(
                            "task_tool_call_rejected",
                            "定时健康任务不允许调用工具。",
                        )
                        else -> Unit
                    }
                },
            )
            if (!completed) throw HealthTaskException("task_stream_incomplete", "AI 自动消息在完成前中断。")
            val parsed = AutoMessageBatch.parse(output.toString()).getOrElse {
                throw HealthTaskException("task_output_invalid", "AI 返回的自动消息格式无效。")
            }
            parsed.copy(execution = execution ?: throw HealthTaskException(
                "task_execution_missing",
                "AI 自动消息缺少执行标识。",
            ))
        } catch (failure: CloudProviderException) {
            throw HealthTaskException(failure.code, failure.message)
        } finally {
            client.cancel()
        }
    }

    private fun requireLockedModel(provider: CloudAiProvider, modelId: String): String {
        val lockedModel = provider.normalizeModelId(modelId)
        if (!provider.acceptsModelId(lockedModel)) {
            throw HealthTaskException("task_model_invalid", "定时任务保存的模型无效。")
        }
        if (!store.hasCredential(provider)) {
            throw HealthTaskException("task_provider_key_missing", "定时任务的 Provider Key 已不可用。")
        }
        return lockedModel
    }

    private fun lockedClient(provider: CloudAiProvider, modelId: String) = PersonalCloudClient(
        provider = provider,
        credential = { store.readCredential(provider)?.value.orEmpty() },
        selectedModel = { modelId },
        baseUrl = { store.baseUrl(provider) },
        client = defaultCloudHttpClient(),
    )

    private suspend fun validateLockedConnection(client: PersonalCloudClient, lockedModel: String) {
        val connection = client.validateConnection(lockedModel)
        if (connection.selectedModelId != lockedModel || connection.models.none { it.id == lockedModel }) {
            throw HealthTaskException("task_model_unavailable", "定时任务保存的模型当前不可用。")
        }
    }

    internal fun request(summary: CloudDailyHealthSummary): AiRequest = AiRequest(
        mode = AiMode.FAST,
        messages = listOf(AiConversationMessage("user", CX330_LIGHT_PROMPT)),
        structuredContextJson = """{"task":"health_auto_message"}""",
        maxOutputTokens = 96,
        caesarToolsJson = "[]",
        requiresLocal = false,
        sessionId = "automation-health",
        userPrompt = "",
        cloudHealthDisclosure = CloudHealthDisclosure.Included(summary),
    )

    private companion object {
        const val MAX_OUTPUT_CHARS = 2_048
        const val CX330_LIGHT_PROMPT = """
            给熟悉的用户发一组亲近、自然的中文短消息，只依据本次提供的当日健康摘要（health_summary 或 consentedDailyHealthSummary）。
            几条消息要接着聊同一件事：轻轻回应一个具体细节，再接一句体贴的关心或容易做到的小建议；不必每次都提建议、追问或罗列数字。
            像熟人顺口惦记一句，可自然使用“我”和“你”，短句、少句号，不说教、不催促、不强行撒娇，不模仿任何真实人。
            随提供的细节变化表达，不套固定开场，不用“继续保持”“记得多喝水”等万能收尾，也不要重复换词说同一个意思。
            没有历史就不编造前情、习惯或变化；当日汇总不代表此刻状态，不猜测正在做什么、心情、原因或具体时刻。
            不做医疗诊断，不凭单项数值判断正常或异常，不猜测缺失数据或把缺失当零；消息正文不提 Provider、模型、任务或数据字段名。
            只输出一个 JSON 对象，不要 Markdown：{"messages":["短句1","短句2"]}
            messages 必须恰好 2 或 3 条，每条 4–28 个中文字符，总长不超过 84 字。
        """
    }
}
