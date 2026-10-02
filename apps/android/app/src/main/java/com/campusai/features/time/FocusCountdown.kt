package com.campusai.features.time

/** Uses elapsed realtime so delayed frames and time spent off screen still count. */
internal data class FocusCountdown(val remainingMillis: Long, val deadlineMillis: Long?) {
    val running: Boolean get() = deadlineMillis != null && remainingMillis > 0
    val completed: Boolean get() = remainingMillis == 0L

    fun at(nowMillis: Long): FocusCountdown {
        val deadline = deadlineMillis ?: return this
        val remaining = (deadline - nowMillis).coerceAtLeast(0)
        return FocusCountdown(remaining, deadline.takeIf { remaining > 0 })
    }

    fun pause(nowMillis: Long) = at(nowMillis).copy(deadlineMillis = null)

    fun resume(nowMillis: Long) =
        if (completed) this else copy(deadlineMillis = nowMillis + remainingMillis)
}
