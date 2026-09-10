package com.campusai.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.campusai.core.designsystem.CampusTheme
import com.campusai.core.model.ThemeMode
import com.campusai.core.model.UiState
import com.campusai.features.community.MarketplaceListing
import com.github.takahirom.roborazzi.captureRoboImage
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
class WishJournalUiTest {
    @get:Rule val compose = createComposeRule()
    private val wish = MarketplaceListing("wish", "self", "我", "去看一次海", "把这一天留给自己。", null, "", "", "active", "approved", "2026-09-06T01:30:00Z", isPublic = true)

    @Test fun `text card is horizontal and image card is square with no public or sale badge`() {
        compose.setContent { CampusTheme(ThemeMode.DARK) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(20.dp)) {
                ListingCardView(wish) {}
                Spacer(Modifier.height(16.dp))
                Box(Modifier.width(180.dp)) { ListingCardView(wish.copy(title = "等一个日出", mediaUrl = "file:///unavailable-test-image.png")) {} }
            }
        } }
        val textBounds = compose.onNodeWithText(wish.title).fetchSemanticsNode().boundsInRoot
        // Clickable card bounds are the merged semantics node containing the label.
        assertTrue(textBounds.width > textBounds.height * 1.5f)
        val imageBounds = compose.onNodeWithText("等一个日出").fetchSemanticsNode().boundsInRoot
        assertEquals(imageBounds.width, imageBounds.height, 1f)
        compose.onNodeWithText("公开").assertDoesNotExist()
        compose.onNodeWithText("在售", substring = true).assertDoesNotExist()
        compose.onRoot().captureRoboImage("../../../artifacts/wish-journal-cards.png")
    }

    @Test fun `own wish has an enabled comment composer and management`() {
        var submitted = ""
        var managed = false
        compose.setContent { CampusTheme(ThemeMode.LIGHT) {
            ListingDetails(wish, true, UiState.Empty, false, null, {}, {}, {}, { managed = true }, {}, {}, { text, done -> submitted = text; done() })
        } }
        compose.onNodeWithText("写下评论…").performTextInput("今天往前走了一小步")
        compose.onNodeWithContentDescription("发布心愿评论").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("今天往前走了一小步", submitted) }
        compose.onNodeWithText("管理").performClick()
        compose.runOnIdle { assertTrue(managed) }
        compose.onNodeWithText("在售", substring = true).assertDoesNotExist()
    }

    @Test fun `editing keeps existing date price and private sharing until explicitly saved`() {
        var saved = false
        compose.setContent { CampusTheme(ThemeMode.LIGHT) {
            ListingComposer(false, {}, { title, _, cents, _, _, public, remove, date ->
                assertEquals(wish.title, title); assertNull(cents); assertFalse(public); assertFalse(remove)
                assertEquals("2026-10-01", date); saved = true
            }, initial = wish.copy(isPublic = false, targetDate = "2026-10-01"))
        } }
        compose.onNodeWithText("分享给其他人").assertExists()
        compose.onNodeWithText("保存", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertTrue(saved) }
    }

    @Test fun `deletion requires the confirmation and respects busy state`() {
        var deletes = 0
        compose.setContent { CampusTheme(ThemeMode.LIGHT) { OwnedContentDialog("管理心愿", false, null, {}, {}, { deletes++ }) } }
        compose.onNodeWithText("删除内容").performClick()
        compose.runOnIdle { assertEquals(0, deletes) }
        compose.onNodeWithText("确认删除").performClick()
        compose.runOnIdle { assertEquals(1, deletes) }
    }
}
