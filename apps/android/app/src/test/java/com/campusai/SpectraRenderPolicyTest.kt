package com.campusai

import com.campusai.core.designsystem.SpectraFramePacer
import com.campusai.core.designsystem.SpectraRenderBudget
import com.campusai.core.designsystem.SpectraRenderPolicy
import com.campusai.core.model.RenderQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraRenderPolicyTest {
    @Test
    fun `auto immediately respects battery saver and escalating thermal pressure`() {
        val policy = SpectraRenderPolicy()
        policy.configure(RenderQuality.AUTO, powerSave = true, thermalStatus = 0)
        assertEquals(SpectraRenderBudget.BALANCED, policy.budget)
        policy.configure(RenderQuality.AUTO, powerSave = false, thermalStatus = 2)
        assertEquals(SpectraRenderBudget.BALANCED, policy.budget)
        policy.configure(RenderQuality.AUTO, powerSave = false, thermalStatus = 3)
        assertEquals(SpectraRenderBudget.ECONOMY, policy.budget)
    }

    @Test
    fun `one slow window does not downgrade but sustained display misses do`() {
        val policy = SpectraRenderPolicy()
        val frames = Frames(policy)
        frames.advance(seconds = 1.2, actualDisplayHz = 30)
        assertEquals(SpectraRenderBudget.FULL, policy.budget)
        frames.advance(seconds = 1.2, actualDisplayHz = 30)
        assertEquals(SpectraRenderBudget.BALANCED, policy.budget)
        frames.advance(seconds = 2.4, actualDisplayHz = 30)
        assertEquals(SpectraRenderBudget.ECONOMY, policy.budget)
    }

    @Test
    fun `GL submission congestion can downgrade while display ticks remain smooth`() {
        val policy = SpectraRenderPolicy()
        Frames(policy).advance(seconds = 2.4, renderEvery = 4)
        assertEquals(SpectraRenderBudget.BALANCED, policy.budget)
    }

    @Test
    fun `expensive GL CPU work is a separate pressure signal`() {
        val policy = SpectraRenderPolicy()
        Frames(policy).advance(seconds = 2.4, cpuNanos = 8_000_000L)
        assertEquals(SpectraRenderBudget.BALANCED, policy.budget)
    }

    @Test
    fun `recovery requires eight healthy windows per step and retains power floor`() {
        val policy = SpectraRenderPolicy()
        policy.configure(RenderQuality.AUTO, powerSave = true, thermalStatus = 3)
        policy.configure(RenderQuality.AUTO, powerSave = true, thermalStatus = 0)
        val frames = Frames(policy)
        frames.advance(seconds = 6.0)
        assertEquals(SpectraRenderBudget.ECONOMY, policy.budget)
        frames.advance(seconds = 3.0)
        assertEquals(SpectraRenderBudget.BALANCED, policy.budget)
        frames.advance(seconds = 9.0)
        assertEquals(SpectraRenderBudget.BALANCED, policy.budget)
        policy.configure(RenderQuality.AUTO, powerSave = false, thermalStatus = 0)
        frames.advance(seconds = 9.0)
        assertEquals(SpectraRenderBudget.FULL, policy.budget)
    }

    @Test
    fun `missing renderer feedback never counts as healthy recovery`() {
        val policy = SpectraRenderPolicy()
        policy.configure(RenderQuality.AUTO, powerSave = true, thermalStatus = 0)
        policy.configure(RenderQuality.AUTO, powerSave = false, thermalStatus = 0)
        Frames(policy).advance(seconds = 12.0, renderEvery = Int.MAX_VALUE)
        assertEquals(SpectraRenderBudget.BALANCED, policy.budget)
    }

    @Test
    fun `lifecycle resume discards a long inactive gap`() {
        val policy = SpectraRenderPolicy()
        val frames = Frames(policy)
        frames.advance(seconds = 1.2)
        policy.resetTiming()
        frames.now += 90_000_000_000L
        frames.advance(seconds = 2.4)
        assertEquals(SpectraRenderBudget.FULL, policy.budget)
    }

    @Test
    fun `high preserves user choice except critical thermal protection and low remains low`() {
        val policy = SpectraRenderPolicy()
        policy.configure(RenderQuality.HIGH, powerSave = true, thermalStatus = 3)
        Frames(policy).advance(seconds = 6.0, actualDisplayHz = 20)
        assertEquals(SpectraRenderBudget.FULL, policy.budget)
        policy.configure(RenderQuality.HIGH, powerSave = true, thermalStatus = 4)
        assertEquals(SpectraRenderBudget.ECONOMY, policy.budget)
        policy.configure(RenderQuality.HIGH, powerSave = true, thermalStatus = 3)
        assertEquals(SpectraRenderBudget.FULL, policy.budget)
        policy.configure(RenderQuality.LOW, powerSave = false, thermalStatus = 0)
        Frames(policy).advance(seconds = 12.0)
        assertEquals(SpectraRenderBudget.ECONOMY, policy.budget)
    }

    @Test
    fun `frame pacing uses stable whole display periods with bounded resolution`() {
        for (budget in SpectraRenderBudget.entries) {
            assertTrue(budget.sceneScale in .3f..0.5f)
            for (displayHz in listOf(60, 90, 120)) {
                val pacer = SpectraFramePacer()
                val interval = (1_000_000_000.0 / displayHz).toLong()
                val requests = (1..displayHz).count { pacer.shouldRender(it * interval, budget, displayHz.toFloat()) }
                val expected = when (budget) {
                    SpectraRenderBudget.FULL -> displayHz
                    SpectraRenderBudget.BALANCED -> if (displayHz == 90) 45 else 60
                    SpectraRenderBudget.ECONOMY -> 30
                }
                assertEquals("$budget at $displayHz Hz", expected, requests)
            }
        }
        assertEquals(SpectraRenderBudget.FULL.intervalNanos(60f), SpectraRenderBudget.FULL.intervalNanos(Float.NaN))
    }

    private class Frames(private val policy: SpectraRenderPolicy) {
        var now = 1_000_000_000L
        private val pacer = SpectraFramePacer()
        private var tick = 0

        fun advance(
            seconds: Double,
            actualDisplayHz: Int = 120,
            renderEvery: Int = 1,
            cpuNanos: Long = 1_000_000L,
        ) {
            repeat((seconds * actualDisplayHz).toInt()) {
                now += 1_000_000_000L / actualDisplayHz
                tick += 1
                if (tick % renderEvery == 0 && pacer.shouldRender(now, policy.budget, 120f)) {
                    policy.recordRender(now, cpuNanos, 120f)
                }
                policy.recordDisplay(now, 120f)
            }
        }
    }
}
