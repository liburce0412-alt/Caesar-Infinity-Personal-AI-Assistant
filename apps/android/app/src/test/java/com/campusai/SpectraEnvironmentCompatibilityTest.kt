package com.campusai

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import com.campusai.core.designsystem.SpectraColors
import com.campusai.core.designsystem.spectraColorScheme
import com.campusai.core.model.SpectraEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SpectraEnvironmentCompatibilityTest {
    @Test
    fun legacyEnvironmentNamesAndOrdinalsRemainStable() {
        assertEquals(
            listOf("ORIGINAL", "OCEAN", "ULTRAVIOLET", "EMBER"),
            SpectraEnvironment.entries.take(4).map { it.name },
        )
        assertEquals(4, SpectraEnvironment.AURORA.ordinal)
    }

    @Test
    fun semanticColorsKeepTextContrastInEveryEnvironmentAndTheme() {
        for (dark in listOf(false, true)) for (environment in SpectraEnvironment.entries) {
            val scheme = spectraColorScheme(dark, environment)
            val pairs = listOf(
                scheme.onSurface to scheme.surface,
                scheme.onSurfaceVariant to scheme.surface,
                scheme.onSurface to scheme.surfaceContainerLowest,
                scheme.onSurface to scheme.surfaceContainerLow,
                scheme.onSurface to scheme.surfaceContainer,
                scheme.onSurface to scheme.surfaceContainerHigh,
                scheme.onSurface to scheme.surfaceContainerHighest,
                scheme.onPrimary to scheme.primary,
                scheme.onPrimaryContainer to scheme.primaryContainer,
                scheme.onSecondaryContainer to scheme.secondaryContainer,
                scheme.onTertiaryContainer to scheme.tertiaryContainer,
            )
            pairs.forEach { (text, surface) ->
                assertTrue("$environment dark=$dark contrast=${contrast(text, surface)}", contrast(text, surface) >= 4.5f)
            }
        }
    }

    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)

    @Test
    fun auroraProvidesIndependentReadableLightAndDarkAccents() {
        val light = spectraColorScheme(dark = false, environment = SpectraEnvironment.AURORA)
        val dark = spectraColorScheme(dark = true, environment = SpectraEnvironment.AURORA)

        assertEquals(SpectraColors.Aurora, light.primary)
        assertEquals(SpectraColors.AuroraLight, dark.primary)
        assertNotEquals(light.background, dark.background)
        assertNotEquals(light.primary, light.background)
        assertNotEquals(dark.primary, dark.background)
    }
}
