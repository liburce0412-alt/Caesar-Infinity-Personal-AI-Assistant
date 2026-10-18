package com.campusai.core.model

/** History metadata only. Load the full report before opening, deleting or restoring a conversation. */
data class AiReportSummary(
    val id: String,
    val provider: AiProvider,
    val mode: AiMode,
    val model: String,
    val executionEngine: String,
    val requestId: String,
    val title: String,
    val summary: String,
    val createdAt: Long,
    val updatedAt: Long,
    val ownerUserId: String = "local_user",
)
