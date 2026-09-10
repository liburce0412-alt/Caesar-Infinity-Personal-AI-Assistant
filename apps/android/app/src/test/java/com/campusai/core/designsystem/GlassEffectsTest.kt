package com.campusai.core.designsystem

import org.junit.Assert.*
import org.junit.Test

class GlassEffectsTest {
    @Test fun `aurora and meteors cannot run in light mode for any choice combination`() {
        repeat(16) { mask ->
            val effects = GlassEffects(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0, mask and 8 != 0)
            val light = effects.active(dark = false, motion = true)
            assertFalse(light.aurora)
            assertFalse(light.meteors)
            assertEquals(effects.deformation, light.deformation)
            assertEquals(effects.rimLight, light.rimLight)
            assertEquals(effects, effects.active(dark = true, motion = true))
            assertEquals(GlassEffects(false, false, false, false), effects.active(dark = true, motion = false))
        }
    }

    @Test fun `disabling meteors clears active particles without disabling aurora selection`() {
        val atmosphere = GlassAtmosphere(kotlin.random.Random(42))
        repeat(100) { atmosphere.update(it / 60f, 1f, 720f, 320f) }
        val phase = atmosphere.phase
        atmosphere.update(2f, 1f, 720f, 320f, meteorsEnabled = false)
        assertEquals(phase, atmosphere.phase)
        assertTrue((0 until 5).all { atmosphere.heads[it * 4 + 3] == 0f })
    }
}
