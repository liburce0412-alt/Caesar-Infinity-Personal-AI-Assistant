package com.campusai.core.designsystem

import com.campusai.core.model.RenderQuality
import kotlin.math.ceil

/** Sampling/cost budgets only: scene time and all optical material parameters remain unchanged. */
internal enum class SpectraRenderBudget(val maximumHz: Float, val sceneScale: Float) {
    FULL(120f, .5f),
    BALANCED(60f, .42f),
    ECONOMY(30f, .34f);

    fun intervalNanos(displayHz: Float): Long {
        val refreshHz = saneSpectraRefreshRate(displayHz)
        // Use whole display periods so a 90 Hz display gets a steady 45 Hz under a 60 Hz cap.
        val periods = ceil(refreshHz / maximumHz - .001f).toInt().coerceAtLeast(1)
        return (1_000_000_000.0 * periods / refreshHz).toLong()
    }
}

internal fun saneSpectraRefreshRate(value: Float): Float =
    if (value.isFinite() && value in 20f..240f) value else 60f

/**
 * Main-thread, allocation-free feedback controller. Render callbacks measure submission cadence
 * and CPU work, not GPU completion/presentation. Device FrameTimeline remains the acceptance test.
 */
internal class SpectraRenderPolicy {
    var budget = SpectraRenderBudget.FULL
        private set

    private var quality = RenderQuality.AUTO
    private var minimumBudget = SpectraRenderBudget.FULL
    private var windowStartedAt = 0L
    private var previousDisplayAt = 0L
    private var previousRenderAt = 0L
    private var previousRefreshHz = 0f
    private var displayFrames = 0
    private var missedDisplayFrames = 0
    private var renderFrames = 0
    private var missedRenderFrames = 0
    private var slowRenderFrames = 0
    private var pressureWindows = 0
    private var healthyWindows = 0

    fun configure(quality: RenderQuality, powerSave: Boolean, thermalStatus: Int) {
        if (this.quality != quality) {
            this.quality = quality
            budget = SpectraRenderBudget.FULL
            resetTiming()
        }
        // Android thermal status: MODERATE=2, SEVERE=3, CRITICAL=4. Older devices use 0.
        val floor = when {
            quality == RenderQuality.LOW -> SpectraRenderBudget.ECONOMY
            thermalStatus >= 4 -> SpectraRenderBudget.ECONOMY
            quality == RenderQuality.HIGH -> SpectraRenderBudget.FULL
            thermalStatus >= 3 -> SpectraRenderBudget.ECONOMY
            thermalStatus >= 2 || powerSave -> SpectraRenderBudget.BALANCED
            else -> SpectraRenderBudget.FULL
        }
        if (floor != minimumBudget) {
            minimumBudget = floor
            resetTiming()
        }
        if (quality != RenderQuality.AUTO || floor.ordinal > budget.ordinal) {
            if (budget != floor) {
                budget = floor
                resetTiming()
            }
        }
    }

    /** Call only for a newly completed GL callback, before this display tick is evaluated. */
    fun recordRender(completedAtNanos: Long, cpuDurationNanos: Long, displayHz: Float) {
        val interval = budget.intervalNanos(displayHz)
        if (previousRenderAt > 0L && completedAtNanos > previousRenderAt) {
            renderFrames += 1
            missedRenderFrames += missedPeriods(completedAtNanos - previousRenderAt, interval)
            if (cpuDurationNanos > interval * .8) slowRenderFrames += 1
        }
        previousRenderAt = completedAtNanos
    }

    fun recordDisplay(frameTimeNanos: Long, displayHz: Float) {
        val refreshHz = saneSpectraRefreshRate(displayHz)
        if (previousRefreshHz != refreshHz) {
            resetTiming()
            previousRefreshHz = refreshHz
        }
        if (windowStartedAt == 0L) windowStartedAt = frameTimeNanos
        if (previousDisplayAt > 0L && frameTimeNanos > previousDisplayAt) {
            displayFrames += 1
            missedDisplayFrames += missedPeriods(
                frameTimeNanos - previousDisplayAt,
                (1_000_000_000.0 / refreshHz).toLong(),
            )
        }
        previousDisplayAt = frameTimeNanos
        if (frameTimeNanos - windowStartedAt < 1_000_000_000L) return

        val displayPressure = missedDisplayFrames.toFloat() / (displayFrames + missedDisplayFrames).coerceAtLeast(1)
        val renderPressure = missedRenderFrames.toFloat() / (renderFrames + missedRenderFrames).coerceAtLeast(1)
        val cpuPressure = slowRenderFrames.toFloat() / renderFrames.coerceAtLeast(1)
        val pressured = displayPressure >= .20f || renderPressure >= .20f || cpuPressure >= .20f
        // Missing callbacks are not evidence of healthy performance; require actual render samples.
        val healthy = renderFrames >= 8 && displayPressure <= .05f && renderPressure <= .05f && cpuPressure <= .05f
        pressureWindows = if (pressured) pressureWindows + 1 else 0
        healthyWindows = if (healthy) healthyWindows + 1 else 0
        if (quality == RenderQuality.AUTO) {
            val next = when {
                pressureWindows >= 2 -> SpectraRenderBudget.entries[(budget.ordinal + 1).coerceAtMost(2)]
                healthyWindows >= 8 && budget.ordinal > minimumBudget.ordinal ->
                    SpectraRenderBudget.entries[budget.ordinal - 1]
                else -> budget
            }
            if (next != budget) {
                budget = next
                resetTiming()
            }
        }
        windowStartedAt = frameTimeNanos
        clearWindow()
    }

    /** Lifecycle/refresh changes discard gaps and stale evidence; the current budget is retained. */
    fun resetTiming() {
        windowStartedAt = 0L
        previousDisplayAt = 0L
        previousRenderAt = 0L
        pressureWindows = 0
        healthyWindows = 0
        clearWindow()
    }

    private fun clearWindow() {
        displayFrames = 0
        missedDisplayFrames = 0
        renderFrames = 0
        missedRenderFrames = 0
        slowRenderFrames = 0
    }

    private fun missedPeriods(elapsed: Long, expected: Long): Int =
        ((elapsed.toDouble() / expected + .15).toInt() - 1).coerceIn(0, 120)
}

/** Lives with the surface, so recomposition cannot restart its cadence. */
internal class SpectraFramePacer {
    private var lastRequestedAt = 0L

    fun reset() { lastRequestedAt = 0L }

    fun shouldRender(frameTimeNanos: Long, budget: SpectraRenderBudget, displayHz: Float): Boolean {
        // Allow tiny vsync rounding differences, never a whole early display period.
        val tolerance = (100_000_000.0 / saneSpectraRefreshRate(displayHz)).toLong()
        if (lastRequestedAt != 0L && frameTimeNanos - lastRequestedAt < budget.intervalNanos(displayHz) - tolerance) return false
        lastRequestedAt = frameTimeNanos
        return true
    }
}
