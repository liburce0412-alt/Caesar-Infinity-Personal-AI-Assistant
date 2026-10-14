package com.campusai.core.ai

import com.campusai.core.model.AiMode
import com.campusai.core.model.AiProvider
import com.campusai.core.model.LocalModelState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicReference

enum class AiRoute { PERSONAL_DEEPSEEK, PERSONAL_GOOGLE_GEMINI, PERSONAL_CODEX, LOCAL, CAESAR_CLOUD_FREE }

sealed interface AiRouteDecision {
    data class Use(val route: AiRoute) : AiRouteDecision
    data class Block(val code: String, val message: String, val canUseCloudOnce: Boolean = false) : AiRouteDecision
}

fun decideAiRoute(
    provider: AiProvider,
    mode: AiMode,
    online: Boolean,
    localReady: Boolean,
    personalKeyAvailable: Boolean = false,
    geminiKeyAvailable: Boolean = false,
    codexKeyAvailable: Boolean = false,
): AiRouteDecision = when (provider) {
    AiProvider.CAESAR_CLOUD_FREE -> if (online) {
        AiRouteDecision.Use(AiRoute.CAESAR_CLOUD_FREE)
    } else {
        AiRouteDecision.Block("cloud_offline", "Caesar cloud free 需要网络连接，请联网后重试。")
    }
    AiProvider.AUTO -> when {
        localReady -> AiRouteDecision.Use(AiRoute.LOCAL)
        online && (personalKeyAvailable || geminiKeyAvailable || codexKeyAvailable) -> AiRouteDecision.Block(
            "local_model_not_ready",
            "本地模型尚未就绪。你可以先下载模型，或只为本次明确选择一个已配置的云端 Provider。",
            canUseCloudOnce = true,
        )
        else -> AiRouteDecision.Block("offline_model_missing", "本地模型尚未下载。请先在“我的 → AI 运行方式”下载并校验。")
    }
    AiProvider.DEEPSEEK -> if (online) {
        cloudRoute(CloudAiProvider.DEEPSEEK, personalKeyAvailable)
    } else {
        AiRouteDecision.Block("deepseek_offline", "DeepSeek 云端需要网络连接。恢复网络后重试；不会自动切换到本地模型。")
    }
    AiProvider.GOOGLE_GEMINI -> if (online) {
        cloudRoute(CloudAiProvider.GOOGLE_GEMINI, geminiKeyAvailable)
    } else {
        AiRouteDecision.Block("gemini_offline", "Google Gemini 云端需要网络连接。恢复网络后重试；不会自动切换到其他模型。")
    }
    AiProvider.CODEX -> if (online) {
        cloudRoute(CloudAiProvider.CODEX, codexKeyAvailable)
    } else {
        AiRouteDecision.Block(
            "codex_offline",
            "Codex 连接不可用。请检查：手机是否开启 Tailscale；电脑是否开机；CPA 与 Clash 是否正在运行。",
        )
    }
    AiProvider.LOCAL -> when {
        localReady -> AiRouteDecision.Use(AiRoute.LOCAL)
        else -> AiRouteDecision.Block("local_model_not_ready", "本地模型尚未就绪。请先在“我的 → AI 运行方式”下载并完成校验。", canUseCloudOnce = online)
    }
}

private fun cloudRoute(provider: CloudAiProvider, keyAvailable: Boolean): AiRouteDecision = when {
    keyAvailable -> AiRouteDecision.Use(
        when (provider) {
            CloudAiProvider.DEEPSEEK -> AiRoute.PERSONAL_DEEPSEEK
            CloudAiProvider.GOOGLE_GEMINI -> AiRoute.PERSONAL_GOOGLE_GEMINI
            CloudAiProvider.CODEX -> AiRoute.PERSONAL_CODEX
        },
    )
    else -> AiRouteDecision.Block(
        when (provider) {
            CloudAiProvider.DEEPSEEK -> "personal_key_missing"
            CloudAiProvider.GOOGLE_GEMINI -> "gemini_key_missing"
            CloudAiProvider.CODEX -> "codex_key_missing"
        },
        "已选择“我的 ${provider.displayName} Key”，但设备上尚未保存 Key。请前往“我的 → AI 运行方式”保存后重试。",
    )
}

class AiEngineRouter(
    private val personalDeepSeek: AiEngine,
    private val local: AiEngine,
    private val provider: () -> AiProvider,
    private val personalKeyAvailable: () -> Boolean,
    private val isOnline: () -> Boolean,
    private val localState: (modelId: String) -> LocalModelState,
    private val personalGoogleGemini: AiEngine? = null,
    private val geminiKeyAvailable: () -> Boolean = { false },
    private val personalCodex: AiEngine? = null,
    private val codexKeyAvailable: () -> Boolean = { false },
    private val caesarCloudFree: AiEngine? = null,
) : AiEngine {
    private data class ActiveRoute(val engine: AiEngine)

    private val active = AtomicReference<ActiveRoute?>(null)

    override fun stream(request: AiRequest): Flow<AiEvent> = flow {
        val selectedLocalState = localState(request.localModelId)
        val selectedProvider = provider()
        val hasImages = request.imagePaths.isNotEmpty() || request.messages.any { message ->
            message.attachmentPaths.isNotEmpty() || message.attachmentRefs.isNotEmpty()
        }
        val codexVisionTurn = selectedProvider == AiProvider.CODEX && request.allowCodexImageUpload
        val cloudFreeVisionTurn = selectedProvider == AiProvider.CAESAR_CLOUD_FREE && request.allowCloudFreeImageUpload
        val decision = if (request.requiresLocal || (hasImages && !codexVisionTurn && !cloudFreeVisionTurn)) {
            if (selectedLocalState == LocalModelState.Ready) AiRouteDecision.Use(AiRoute.LOCAL)
            else AiRouteDecision.Block("local_required", "本会话锁定的本地模型尚未就绪。请完成该模型下载与校验，或新建会话后选择其他档位。")
        } else decideAiRoute(
            provider = selectedProvider,
            mode = request.mode,
            online = isOnline(),
            localReady = selectedLocalState == LocalModelState.Ready,
            personalKeyAvailable = personalKeyAvailable(),
            geminiKeyAvailable = geminiKeyAvailable(),
            codexKeyAvailable = codexKeyAvailable(),
        )
        val engine = engineFor(decision)
        val route = ActiveRoute(engine)
        active.set(route)
        try {
            engine.stream(request).collect { emit(it) }
        } finally {
            active.compareAndSet(route, null)
        }
    }

    fun streamCloudOnce(request: AiRequest, provider: AiProvider = AiProvider.DEEPSEEK): Flow<AiEvent> = flow {
        if (
            request.requiresLocal ||
            request.imagePaths.isNotEmpty() ||
            request.messages.any { it.attachmentPaths.isNotEmpty() || it.attachmentRefs.isNotEmpty() }
        ) {
            throw AiRoutingException("cloud_local_content_forbidden", "图片和其他仅限本机的内容不能发送到云端。")
        }
        val cloudProvider = CloudAiProvider.from(provider)
            ?: throw AiRoutingException("cloud_provider_invalid", "本次云端请求未指定受支持的 Provider。")
        if (!isOnline()) {
            val code = when (cloudProvider) {
                CloudAiProvider.DEEPSEEK -> "deepseek_offline"
                CloudAiProvider.GOOGLE_GEMINI -> "gemini_offline"
                CloudAiProvider.CODEX -> "codex_offline"
            }
            val message = if (cloudProvider == CloudAiProvider.CODEX) {
                "Codex 连接不可用。请检查：手机是否开启 Tailscale；电脑是否开机；CPA 与 Clash 是否正在运行。"
            } else {
                "${cloudProvider.displayName} 云端需要网络连接。恢复网络后重试。"
            }
            throw AiRoutingException(code, message)
        }
        val keyAvailable = when (cloudProvider) {
            CloudAiProvider.DEEPSEEK -> personalKeyAvailable()
            CloudAiProvider.GOOGLE_GEMINI -> geminiKeyAvailable()
            CloudAiProvider.CODEX -> codexKeyAvailable()
        }
        val engine = engineFor(cloudRoute(cloudProvider, keyAvailable))
        val route = ActiveRoute(engine)
        active.set(route)
        try {
            engine.stream(request).collect { emit(it) }
        } finally {
            active.compareAndSet(route, null)
        }
    }

    private fun engineFor(decision: AiRouteDecision): AiEngine = when (decision) {
        is AiRouteDecision.Use -> when (decision.route) {
            AiRoute.CAESAR_CLOUD_FREE -> caesarCloudFree
                ?: throw AiRoutingException("cloud_not_configured", "Caesar cloud free 尚未开放。")
            AiRoute.PERSONAL_DEEPSEEK -> personalDeepSeek
            AiRoute.PERSONAL_GOOGLE_GEMINI -> personalGoogleGemini
                ?: throw AiRoutingException("gemini_not_configured", "Google Gemini 引擎尚未接入当前运行时。")
            AiRoute.PERSONAL_CODEX -> personalCodex
                ?: throw AiRoutingException("codex_not_configured", "Codex 引擎尚未接入当前运行时。")
            AiRoute.LOCAL -> local
        }
        is AiRouteDecision.Block -> throw AiRoutingException(decision.code, decision.message, decision.canUseCloudOnce)
    }

    override fun cancel() {
        active.getAndSet(null)?.engine?.cancel()
    }
}
