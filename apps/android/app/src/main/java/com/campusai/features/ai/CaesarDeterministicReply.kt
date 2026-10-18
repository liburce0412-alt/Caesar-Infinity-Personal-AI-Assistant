package com.campusai.features.ai

/** Stable product facts should not depend on sampling from a local model. */
internal object CaesarDeterministicReply {
    private val identityPatterns = listOf(
        Regex("^(?:你的)?名字是什么[?？]?$"),
        Regex("^你叫什么(?:名字)?[?？]?$"),
        Regex("^你是谁[?？]?$"),
        Regex("^(?:请)?(?:简单)?(?:介绍一下你自己|自我介绍)[。.!！?？]?$"),
    )
    private val capabilityPatterns = listOf(
        Regex("^(?:请)?(?:说说|介绍一下)?你能(?:做|干)什么[?？]?$"),
        Regex("^你会什么[?？]?$"),
        Regex("^你有什么功能[?？]?$"),
        Regex("^你能帮我(?:做)?什么[?？]?$"),
    )

    fun forPrompt(prompt: String, hasImages: Boolean, now: java.time.Instant = java.time.Instant.now(),
        provider: com.campusai.core.model.AiProvider = com.campusai.core.model.AiProvider.LOCAL): String? {
        if (hasImages) return null
        com.campusai.core.time.AiClock.reply(prompt, now)?.let { return it }
        val normalized = prompt.trim().replace(Regex("\\s+"), "")
        return when {
            identityPatterns.any { it.matches(normalized) } ->
                "我是 Caesar∞，运行在你设备上的私人 Agent。"
            capabilityPatterns.any { it.matches(normalized) } ->
                com.campusai.core.ai.aiCapabilityDescription(provider)
            else -> null
        }
    }
}
