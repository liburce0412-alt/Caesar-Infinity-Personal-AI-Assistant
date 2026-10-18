package com.campusai

import com.campusai.debug.SpectraRenderProbeContract
import org.junit.Assert.*
import org.junit.Test

class SpectraRenderProbeContractTest {
    @Test fun `entry requires both exact action and explicit opt in`() {
        assertTrue(SpectraRenderProbeContract.accepts(SpectraRenderProbeContract.ACTION, true))
        assertFalse(SpectraRenderProbeContract.accepts(SpectraRenderProbeContract.ACTION, false))
        assertFalse(SpectraRenderProbeContract.accepts(null, true))
        assertFalse(SpectraRenderProbeContract.accepts("other", true))
    }

    @Test fun `fixed experiment stays bounded and never mixes simulated thermal with frame pressure`() {
        val phases = SpectraRenderProbeContract.phases
        assertTrue(phases.sumOf { it.durationMs } + 3_000 + phases.size * 80 < SpectraRenderProbeContract.DEADLINE_MS)
        assertTrue(SpectraRenderProbeContract.DEADLINE_MS <= 70_000)
        val pressure = phases.single { it.stallMainThread }
        assertTrue(pressure.durationMs <= 4_500)
        assertEquals(0, pressure.syntheticThermal)
        assertFalse(pressure.syntheticSaver)
        assertEquals("frame_pressure_removed", phases.last().name)
        assertEquals("FULL", phases.last().expectedFinalBudget)
        assertEquals(18_000L, phases.last().durationMs)
    }
}
