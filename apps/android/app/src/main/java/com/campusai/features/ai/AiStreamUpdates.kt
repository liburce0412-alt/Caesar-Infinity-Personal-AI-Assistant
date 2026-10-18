package com.campusai.features.ai

import com.campusai.core.ai.AiEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Batch only text; flush before every control event, including completion and errors. */
internal fun Flow<AiEvent>.batchTextUpdates(intervalMillis: Long = 32, maxPendingChars: Int = 4096): Flow<AiEvent> = channelFlow {
    require(intervalMillis > 0)
    require(maxPendingChars > 0)
    val lock = Mutex()
    val pending = StringBuilder()
    var timer: Job? = null
    suspend fun flush() {
        if (pending.isNotEmpty()) {
            send(AiEvent.Delta(pending.toString()))
            pending.setLength(0)
        }
    }
    try {
        collect { event ->
            lock.withLock {
                if (event is AiEvent.Delta) {
                    var offset = 0
                    while (offset < event.text.length) {
                        val end = minOf(event.text.length, offset + maxPendingChars - pending.length)
                        pending.append(event.text, offset, end)
                        offset = end
                        if (pending.length == maxPendingChars) {
                            timer?.cancel()
                            timer = null
                            flush()
                        }
                    }
                    if (timer == null && pending.isNotEmpty()) timer = launch {
                        delay(intervalMillis)
                        lock.withLock { flush(); timer = null }
                    }
                } else {
                    timer?.cancel()
                    timer = null
                    flush()
                    send(event)
                }
            }
        }
        lock.withLock { timer?.cancel(); timer = null; flush() }
    } finally {
        timer?.cancel()
    }
}
