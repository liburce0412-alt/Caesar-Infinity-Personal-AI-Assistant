package com.campusai.core.ai

import com.campusai.core.model.AiMode
import com.campusai.core.model.AiProvider
import com.campusai.core.network.CaesarCloudFreeTools

/** The same route policy drives tool projection, request flags and product capability copy. */
data class AiCapabilities(val webSearch: Boolean, val allowedToolNames: Set<String>? = null, val localMemoryRead: Boolean = false)

/** Normalize a saved/previous mode without changing personal cloud or local choices. */
fun aiModeForProvider(provider: AiProvider, requestedMode: AiMode): AiMode =
    if (provider == AiProvider.CAESAR_CLOUD_FREE) AiMode.FAST else requestedMode

fun aiCapabilities(provider: AiProvider, hasImages: Boolean = false): AiCapabilities = when (provider) {
    AiProvider.CAESAR_CLOUD_FREE -> AiCapabilities(
        webSearch = !hasImages,
        allowedToolNames = if (hasImages) emptySet() else CaesarCloudFreeTools.allowedNames,
    )
    AiProvider.DEEPSEEK, AiProvider.GOOGLE_GEMINI, AiProvider.CODEX -> AiCapabilities(webSearch = true)
    AiProvider.LOCAL, AiProvider.AUTO -> AiCapabilities(webSearch = false, localMemoryRead = true)
}

fun aiCapabilityDescription(provider: AiProvider): String = when (provider) {
    AiProvider.CAESAR_CLOUD_FREE -> "文字可请求搜索、时间记录、课程查询和记忆建议；读取记忆仅在本地可用。图片仅识别当前一张。应用操作按权限和确认规则执行；健康只附带本轮同意的今日摘要。"
    AiProvider.LOCAL, AiProvider.AUTO -> "我可以处理文字和图片、接收语音转写，并通过已注册的 App 工具管理时间记录、树洞、心愿墙、消息和本机健康数据。操作受权限与确认规则约束。"
    else -> "我可以查询网页、处理文字，并通过已注册工具管理 App 记录、提出记忆建议；读取记忆仅在本地可用。应用操作按权限和确认规则执行；健康只附带本轮同意的今日摘要。"
}
