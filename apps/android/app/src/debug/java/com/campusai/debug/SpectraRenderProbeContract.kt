package com.campusai.debug

import com.campusai.core.model.RenderQuality

/** Fixed, bounded debug fixture; no intent-controlled temperatures, delays or output paths. */
internal object SpectraRenderProbeContract {
    const val ACTION = "com.campusai.debug.RUN_SPECTRA_PROBE"
    const val OPT_IN = "explicitOptIn"
    const val DEADLINE_MS = 70_000L
    const val REPORT = "spectra-render-probe.json"

    fun accepts(action: String?, optedIn: Boolean) = action == ACTION && optedIn

    data class Phase(
        val name: String,
        val durationMs: Long,
        val quality: RenderQuality = RenderQuality.AUTO,
        val syntheticThermal: Int = 0,
        val syntheticSaver: Boolean = false,
        val stallMainThread: Boolean = false,
        val expectedFinalBudget: String,
    )

    val phases = listOf(
        Phase("baseline", 2_000, expectedFinalBudget = "FULL"),
        Phase("synthetic_moderate", 2_000, syntheticThermal = 2, expectedFinalBudget = "BALANCED"),
        Phase("synthetic_severe", 2_000, syntheticThermal = 3, expectedFinalBudget = "ECONOMY"),
        Phase("thermal_input_removed", 18_000, expectedFinalBudget = "FULL"),
        Phase("synthetic_saver", 2_000, syntheticSaver = true, expectedFinalBudget = "BALANCED"),
        Phase("saver_input_removed", 9_000, expectedFinalBudget = "FULL"),
        Phase("high_synthetic_severe", 2_000, RenderQuality.HIGH, 3, true, expectedFinalBudget = "FULL"),
        Phase("high_synthetic_critical", 2_000, RenderQuality.HIGH, 4, expectedFinalBudget = "ECONOMY"),
        Phase("high_input_removed", 2_000, RenderQuality.HIGH, expectedFinalBudget = "FULL"),
        Phase("auto_baseline", 1_000, expectedFinalBudget = "FULL"),
        Phase("actual_process_frame_pressure", 4_500, stallMainThread = true, expectedFinalBudget = "ECONOMY"),
        Phase("frame_pressure_removed", 18_000, expectedFinalBudget = "FULL"),
    )
}
