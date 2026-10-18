package com.campusai.features.ai

internal data class AiSpeechTicket(val epoch: Long, val ownerId: String)

/** Reject permission/activity callbacks after cancellation, a new request, owner change or disposal. */
internal class AiSpeechResultGuard {
    private var epoch = 0L
    private var closed = false

    fun begin(ownerId: String): AiSpeechTicket = AiSpeechTicket(++epoch, ownerId)
    fun cancel() { epoch += 1L }
    fun close() { closed = true; cancel() }
    fun accepts(ticket: AiSpeechTicket?, currentOwnerId: String): Boolean =
        !closed && ticket != null && ticket.epoch == epoch && ticket.ownerId == currentOwnerId
}
