package com.campusai.features.ai

import com.campusai.core.designsystem.CaesarOrbMode

internal data class AiOrbPresentation(val mode: CaesarOrbMode, val label: String)

internal fun aiOrbPresentation(
    state: AiUiState,
    listening: Boolean,
    transcribing: Boolean,
    speaking: Boolean,
    voiceMessage: String?,
    playbackMessage: String?,
): AiOrbPresentation = when {
    listening -> AiOrbPresentation(CaesarOrbMode.LISTENING, "正在聆听 · 结束后可编辑文字")
    transcribing -> AiOrbPresentation(CaesarOrbMode.TRANSCRIBING, "正在转写")
    speaking -> AiOrbPresentation(CaesarOrbMode.SPEAKING, "正在朗读")
    state.streaming -> {
        val responding = state.messages.lastOrNull()?.let { it.role == "assistant" && it.content.isNotBlank() } == true
        val label = when (state.stage) {
            "planning" -> "正在思考"
            "local_loading" -> "正在加载本地模型"
            "local_generating" -> "正在生成回复"
            "responding", "思考中", "" -> if (responding) "正在生成回复" else "正在等待回复"
            else -> state.stage
        }
        AiOrbPresentation(if (responding) CaesarOrbMode.RESPONDING else CaesarOrbMode.THINKING, label)
    }
    voiceMessage != null -> AiOrbPresentation(
        if (voiceMessage == "语音已结束") CaesarOrbMode.CANCELLED else CaesarOrbMode.ERROR, voiceMessage)
    playbackMessage != null -> AiOrbPresentation(when (playbackMessage) {
        "朗读失败 · 请重试" -> CaesarOrbMode.ERROR
        "已停止朗读", "朗读已停止" -> CaesarOrbMode.CANCELLED
        "正在准备朗读" -> CaesarOrbMode.THINKING
        else -> CaesarOrbMode.COMPLETE
    }, playbackMessage)
    state.errorCode == "cancelled" -> AiOrbPresentation(CaesarOrbMode.CANCELLED, "已停止生成")
    state.error != null -> AiOrbPresentation(CaesarOrbMode.ERROR, "本次未完成 · 可重试")
    state.messages.any { it.role == "assistant" && it.content.isNotBlank() } -> AiOrbPresentation(CaesarOrbMode.COMPLETE, "回复已完成")
    else -> AiOrbPresentation(CaesarOrbMode.IDLE, "准备就绪")
}
