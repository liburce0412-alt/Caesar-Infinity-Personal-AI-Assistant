package com.campusai.app

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import com.campusai.core.designsystem.CampusTheme
import com.campusai.core.model.ThemeMode
import com.campusai.core.model.UiState
import com.campusai.features.community.CommunityPost
import com.campusai.features.community.MarketplaceListing
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommunityInteractionUiTest {
    @get:Rule val compose = createComposeRule()
    private val wish = MarketplaceListing("wish", "self", "我", "周末看日出", "", null, "", "", "active", "approved", "")
    private val post = CommunityPost("post", "self", "我", "", "今天很开心", "", "", false, 0, false, 0, "")

    @Test fun `report failure keeps input and enables retry inside the dialog`() {
        var busy by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var submitted = 0
        var dismissed = false
        compose.setContent { CampusTheme(ThemeMode.LIGHT) {
            ReportDialog("一条内容", busy, { dismissed = true }, { reason, details ->
                assertEquals("不实信息", reason)
                assertEquals("需要核实", details)
                submitted++
                busy = true
            }, error)
        } }
        compose.onNodeWithText("原因").performTextInput("不实信息")
        compose.onNodeWithText("补充说明").performTextInput("需要核实")
        compose.onNodeWithText("提交举报").performClick()
        compose.onNodeWithText("正在提交").assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsNotEnabled()
        compose.onNodeWithText("原因").assertIsNotEnabled()
        compose.runOnIdle { busy = false; error = "连接中断，请重试。" }
        compose.onNodeWithText("连接中断，请重试。").assertIsDisplayed()
        compose.onNodeWithText("原因").assertTextContains("不实信息")
        compose.onNodeWithText("提交举报").performClick()
        compose.runOnIdle { assertEquals(2, submitted); assertFalse(dismissed) }
    }

    @Test fun `wish comment success does not erase a newer draft`() {
        var complete: (() -> Unit)? = null
        var submitted = ""
        compose.setContent { CampusTheme(ThemeMode.LIGHT) {
            ListingDetails(wish, true, UiState.Empty, false, null, {}, {}, {}, {}, {}, {}, { text, done -> submitted = text; complete = done })
        } }
        compose.onNodeWithText("写下评论…").performTextInput("第一条留言")
        compose.onNodeWithContentDescription("发布心愿评论").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("下一条还在写")
        compose.runOnIdle { complete!!.invoke(); assertEquals("第一条留言", submitted) }
        compose.onNode(hasSetTextAction()).assertTextContains("下一条还在写")
    }

    @Test fun `post comment clears its own draft but preserves subsequent input`() {
        var complete: (() -> Unit)? = null
        compose.setContent { CampusTheme(ThemeMode.LIGHT) {
            PostDetails(post, UiState.Empty, false, null, {}, {}, {}, { _, done -> complete = done })
        } }
        compose.onNodeWithText("写下评论…").performTextInput("第一条留言")
        compose.onNodeWithContentDescription("发布评论").performClick()
        compose.runOnIdle { complete!!.invoke() }
        compose.onNodeWithText("写下评论…").performTextInput("第二条留言")
        compose.onNodeWithContentDescription("发布评论").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("尚未发出的新输入")
        compose.runOnIdle { complete!!.invoke() }
        compose.onNode(hasSetTextAction()).assertTextContains("尚未发出的新输入")
    }

    @Test fun `busy post composer exposes disabled controls and retains fields after failure`() {
        var busy by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        compose.setContent { CampusTheme(ThemeMode.LIGHT) {
            PostComposer(busy, {}, { _, _, _, _, _, _ -> busy = true }, error = error)
        } }
        compose.onNodeWithText("今天有什么想被记住？").performTextInput("这是未提交成功的草稿")
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithContentDescription("返回").assertIsNotEnabled()
        compose.onNodeWithText("正在保存").assertIsNotEnabled()
        compose.onNodeWithText("这是未提交成功的草稿").assertIsNotEnabled()
        compose.runOnIdle { busy = false; error = "保存失败，请重试。" }
        compose.onNodeWithText("保存失败，请重试。").assertIsDisplayed()
        compose.onNodeWithText("这是未提交成功的草稿").assertIsEnabled()
        compose.onNodeWithText("保存").assertIsEnabled()
    }

    @Test fun `wish image selection survives picker cancellation and state restoration`() = verifyPickerPreservation(true)
    @Test fun `post image selection survives picker cancellation and state restoration`() = verifyPickerPreservation(false)

    private fun verifyPickerPreservation(isWish: Boolean) {
        val registry = TestImageRegistry()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CampusTheme(ThemeMode.LIGHT) {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                if (isWish) ListingComposer(false, {}, { _, _, _, _, _, _, _, _ -> })
                else PostComposer(false, {}, { _, _, _, _, _, _ -> })
            }
        } }
        val description = if (isWish) "待发布的心愿图片" else "待发布的树洞图片"
        compose.onNodeWithText(if (isWish) "选一张代表它的图" else "从相册选一张图").performScrollTo().performClick()
        compose.runOnIdle { registry.deliver(Uri.parse("content://test/selected-image")) }
        compose.onNodeWithContentDescription(description, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("点击更换").performScrollTo().performClick()
        compose.runOnIdle { registry.deliver(null) }
        compose.onNodeWithContentDescription(description, useUnmergedTree = true).assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription(description, useUnmergedTree = true).assertExists()
    }

    private class TestImageRegistry : ActivityResultRegistry() {
        var activeRequest = 0
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            activeRequest = requestCode
        }
        fun deliver(uri: Uri?) { dispatchResult(activeRequest, uri) }
    }
}
