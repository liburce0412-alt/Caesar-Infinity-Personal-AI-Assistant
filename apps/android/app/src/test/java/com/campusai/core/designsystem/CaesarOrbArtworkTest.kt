package com.campusai.core.designsystem

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CaesarOrbArtworkTest {
    @Test fun `fallback and notification artwork retains black dome clear lower body and transparent corners`() {
        for (dark in listOf(false, true)) {
            val bitmap = CaesarOrbArtwork.bitmap(dark)
            assertSame(bitmap, CaesarOrbArtwork.bitmap(dark))
            assertTrue(Color.red(bitmap.getPixel(128, 55)) < 18)
            assertTrue(Color.red(bitmap.getPixel(128, 215)) > 190)
            assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
            val output = File("../../../artifacts/caesar-orb-20261013/fallback-${if (dark) "dark" else "light"}.png")
            output.parentFile?.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
