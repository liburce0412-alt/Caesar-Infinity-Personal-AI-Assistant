package com.campusai.core.designsystem

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class GlassAtmosphereTest {
    @Test fun `particles stay bounded and expire after interaction ends`() {
        val effect = GlassAtmosphere(Random(42))
        var emitted = false
        repeat(600) { frame ->
            effect.update(frame / 60f, 1f, 720f, 320f)
            assertTrue(effect.heads.all { it.isFinite() })
            val active = (0 until 5).count { effect.heads[it * 4 + 3] > 0f }
            emitted = emitted || active > 0
            assertTrue(active <= 5)
        }
        assertTrue(emitted)
        effect.update(14f, 0f, 720f, 320f)
        assertTrue((0 until 5).all { effect.heads[it * 4 + 3] == 0f })
    }

    @Test fun `each interaction keeps one hue and neighboring surfaces have independent phases`() {
        val first = GlassAtmosphere(Random(1))
        val second = GlassAtmosphere(Random(2))
        first.update(0f, 1f, 720f, 320f)
        second.update(0f, 1f, 720f, 320f)
        assertNotEquals(first.phase, second.phase)
        val hue = first.hue
        val phase = first.phase
        repeat(60) { first.update(it / 60f, 1f, 720f, 320f); assertEquals(hue, first.hue) }
        first.update(2f, 0f, 720f, 320f)
        first.update(3f, 1f, 720f, 320f)
        assertNotEquals(phase, first.phase)
        assertTrue(first.hue == 0f || first.hue == 1f)
    }
}
