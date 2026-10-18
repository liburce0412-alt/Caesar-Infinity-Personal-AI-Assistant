package com.campusai.features.ai

import com.campusai.core.designsystem.CaesarOrbMode

/** Shell and notification state excludes message text, model tokens and elapsed-time ticks. */
data class AiRuntimeState(
    val streaming: Boolean = false,
    val importingImage: Boolean = false,
    val pendingImageCount: Int = 0,
    val completedExternalImageImport: String? = null,
    val orbMode: CaesarOrbMode = CaesarOrbMode.IDLE,
)

internal fun AiUiState.runtimeState() = AiRuntimeState(
    streaming, importingImage, pendingImages.size, completedExternalImageImport,
    aiOrbPresentation(this, false, false, false, null, null).mode,
)
