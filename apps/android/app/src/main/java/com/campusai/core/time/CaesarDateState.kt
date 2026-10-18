package com.campusai.core.time

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/** Recheck after resume or clock changes, and publish only when the Beijing day changes. */
@Composable
fun rememberCaesarDate(): State<LocalDate> = rememberCaesarDate(Clock.systemUTC())

/** Explicit instance seam for isolated date/lifecycle verification; never replaces the system clock. */
@Composable
internal fun rememberCaesarDate(clock: Clock): State<LocalDate> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(CaesarTime.today(clock.instant()), lifecycle, clock) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = clock.instant()
                value = CaesarTime.today(now)
                val nextDay = value.plusDays(1).atStartOfDay(CaesarTime.zone).toInstant()
                delay(Duration.between(now, nextDay).toMillis().coerceIn(1L, 60_000L))
            }
        }
    }
}
