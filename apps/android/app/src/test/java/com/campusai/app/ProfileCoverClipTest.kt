package com.campusai.app

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import com.campusai.core.designsystem.*
import com.campusai.core.model.ThemeMode
import com.campusai.core.profile.CampusProfile
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileCoverClipTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `loaded opaque cover keeps all four rounded corners`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val previousRecord = System.getProperty("roborazzi.test.record")
        System.setProperty("roborazzi.test.record", "true")
        val previous = Coil.imageLoader(context)
        val fetched = AtomicBoolean(false)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val loader = ImageLoader.Builder(context).components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                    fetched.set(true)
                    return SuccessResult(BitmapDrawable(context.resources, bitmap), chain.request, DataSource.MEMORY)
                }
            })
        }.build()
        Coil.setImageLoader(loader)
        try {
            compose.setContent {
                CampusTheme(ThemeMode.LIGHT) {
                    Box(Modifier.size(360.dp, 208.dp).background(Color.Green).testTag("cover")) {
                        ProfileHero(CampusProfile(displayName = "演示", coverPath = "promo/cover"), "演示", 1, 0, {})
                    }
                }
            }
            compose.waitUntil(5_000) { fetched.get() }
            compose.waitForIdle()
            val output = java.io.File.createTempFile("profile-cover-clip", ".png")
            compose.onNodeWithTag("cover").captureRoboImage(output.absolutePath)
            val pixels = android.graphics.BitmapFactory.decodeFile(output.absolutePath)
            assertTrue("Opaque image must actually be loaded", android.graphics.Color.red(pixels.getPixel(pixels.width / 2, pixels.height / 4)) > 200)
            for ((x, y) in listOf(1 to 1, pixels.width - 2 to 1, 1 to pixels.height - 2, pixels.width - 2 to pixels.height - 2)) {
                assertTrue("Cover leaks into corner ($x,$y)", android.graphics.Color.green(pixels.getPixel(x, y)) > 180)
            }
        } finally {
            Coil.setImageLoader(previous); loader.shutdown()
            if (previousRecord == null) System.clearProperty("roborazzi.test.record") else System.setProperty("roborazzi.test.record", previousRecord)
        }
    }
}
