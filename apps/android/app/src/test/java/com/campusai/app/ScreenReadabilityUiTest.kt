package com.campusai.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import java.util.concurrent.atomic.AtomicBoolean
import androidx.test.core.app.ApplicationProvider
import com.campusai.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.campusai.core.auth.AuthState
import com.campusai.core.designsystem.CampusTheme
import com.campusai.core.model.ThemeMode
import com.campusai.core.profile.CampusProfile
import com.campusai.features.ai.ComposerAttachments
import com.campusai.features.ai.CaesarImageAttachment
import com.campusai.features.community.CommunityPost
import com.campusai.features.community.ConversationSummary
import com.campusai.features.community.MarketplaceListing
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenReadabilityUiTest {
    @get:Rule val compose = createComposeRule()
    private val previewImage: String get() = "android.resource://${ApplicationProvider.getApplicationContext<Context>().packageName}/${R.drawable.campusai_infinity_icon}"

    private lateinit var previousLoader: ImageLoader
    private lateinit var imageLoader: ImageLoader
    private var previousRecord: String? = null
    private val imageLoaded = AtomicBoolean(false)

    @Before fun useLocalImageFixture() {
        previousRecord = System.getProperty("roborazzi.test.record")
        System.setProperty("roborazzi.test.record", "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        previousLoader = Coil.imageLoader(context)
        // Coil success alone does not prove the next frame painted an image. Use a known
        // opaque fixture so the screenshot can verify content pixels as well as geometry.
        val bitmap = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(52, 133, 128))
            val canvas = android.graphics.Canvas(this)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = android.graphics.Color.rgb(243, 195, 111)
            canvas.drawCircle(248f, 52f, 25f, paint)
        }
        imageLoader = ImageLoader.Builder(context).components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                    imageLoaded.set(true)
                    return SuccessResult(BitmapDrawable(context.resources, bitmap), chain.request, DataSource.MEMORY)
                }
            })
        }.build()
        Coil.setImageLoader(imageLoader)
    }

    @After fun restoreImageLoader() {
        Coil.setImageLoader(previousLoader)
        imageLoader.shutdown()
        if (previousRecord == null) System.clearProperty("roborazzi.test.record")
        else System.setProperty("roborazzi.test.record", previousRecord)
    }

    private fun show(theme: ThemeMode = ThemeMode.LIGHT, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            CampusTheme(theme) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState()).padding(16.dp)) { content() }
                }
            }
        }
    }

    private fun assertImageRendered(description: String) {
        val output = java.io.File.createTempFile("readability-image-", ".png")
        compose.onNodeWithContentDescription(description, useUnmergedTree = true).captureRoboImage(output.absolutePath)
        val bitmap = requireNotNull(BitmapFactory.decodeFile(output.absolutePath))
        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        assertTrue("Loaded image must actually paint the opaque teal fixture",
            android.graphics.Color.green(pixel) > 110 && android.graphics.Color.red(pixel) < 80)
    }

    @Test fun `photo post keeps text outside the photo at double font size`() {
        show(fontScale = 2f) {
            PostCard(CommunityPost("post", "self", "认真记录每一天的同学", "", "下课以后，去看了一场日落。", "校园生活",
                previewImage, false, 12, true, 3, "2026-10-02T17:30:00"), {}, {}, {}, {})
        }
        compose.waitUntil(5_000) { imageLoaded.get() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("正在载入图片", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("图片暂时无法显示").assertDoesNotExist()
        assertImageRendered("树洞图片")
        val image = compose.onNodeWithContentDescription("树洞图片", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithText("下课以后，去看了一场日落。", useUnmergedTree = true)
        assertTrue(body.fetchSemanticsNode().boundsInRoot.top >= image.bottom)
        body.performScrollTo().assertIsDisplayed()
        // A fixed square with overlaid text regressed at large text sizes. The content now has its own space.
        assertTrue(image.height > 0f)
        compose.onNodeWithContentDescription("收藏").performScrollTo().assertIsDisplayed()
        compose.onRoot().captureRoboImage("../../../artifacts/community-photo-large-text.png")
    }

    @Test fun `photo wish separates the image and description in dark mode`() {
        show(theme = ThemeMode.DARK) {
            ListingCardView(MarketplaceListing("wish", "self", "我", "周末，去看一场日出", "带一本书，把普通的一天过得特别一点。",
                null, "", previewImage, "active", "pending", "2026-10-02T17:30:00")) {}
        }
        compose.waitUntil(5_000) { imageLoaded.get() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("正在载入图片", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("图片暂时无法显示").assertDoesNotExist()
        assertImageRendered("心愿图片")
        val image = compose.onNodeWithContentDescription("心愿图片", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("周末，去看一场日出", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Title must sit below the image, not on a tinted overlay", title.top >= image.bottom)
        compose.onNodeWithText("带一本书，把普通的一天过得特别一点。", useUnmergedTree = true).assertIsDisplayed()
        compose.onRoot().captureRoboImage("../../../artifacts/wish-photo-dark.png")
    }

    @Test fun `profile grows around biography and level at double font size`() {
        show(fontScale = 2f) {
            ProfileHero(CampusProfile(displayName = "认真过好每一天", bio = "想把每一个普通的日子，过得特别一点。", role = "admin"), "同学", 12, 420) {}
        }
        compose.onNodeWithText("LEVEL 12 · 420 XP", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        val biography = compose.onNodeWithText("想把每一个普通的日子，过得特别一点。", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val level = compose.onNodeWithText("LEVEL 12 · 420 XP", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(level.top >= biography.bottom)
        compose.onRoot().captureRoboImage("../../../artifacts/profile-large-text.png")
    }

    @Test fun `message metadata stays below long names and unread badges remain visible`() {
        show(fontScale = 2f) {
            ConversationRow(ConversationSummary("conversation", "wish", "周末去看日出", "other", "名字特别长的校园朋友",
                "我们明天早上见。", "2026-10-02T17:30:00", 120)) {}
        }
        val name = compose.onNodeWithText("名字特别长的校园朋友", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val time = compose.onNodeWithText("2026-10-02 17:30", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(time.top >= name.bottom)
        compose.onNodeWithText("99+", useUnmergedTree = true).assertIsDisplayed()
        compose.onRoot().captureRoboImage("../../../artifacts/messages-large-text.png")
    }

    @Test fun `sign in header remains reachable and password visibility can be changed`() {
        var backedOut = false
        compose.setContent {
            CampusTheme(ThemeMode.LIGHT) {
                AuthScreen(AuthState(), { _, _ -> false }, { _, _, _ -> false }, {}, { backedOut = true })
            }
        }
        compose.onNodeWithContentDescription("返回").assertIsDisplayed()
        compose.onNodeWithText("密码").performScrollTo().performTextInput("password123")
        compose.onNodeWithContentDescription("显示密码").performClick()
        compose.onNodeWithContentDescription("隐藏密码").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { assertTrue(backedOut) }
        compose.onRoot().captureRoboImage("../../../artifacts/auth-refined.png")
    }
    @Test fun `four AI image attachments scroll within a narrow composer`() {
        var removed = -1
        show {
            ComposerAttachments(
                images = List(4) { CaesarImageAttachment("missing-$it.jpg", "image/jpeg", "") },
                importingImage = false, onRemoveImage = { removed = it }, modifier = Modifier.width(232.dp),
            )
        }
        compose.onAllNodesWithContentDescription("移除图片").onLast().performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(removed == 3) }
    }

}
