package com.campusai.features.ai

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.campusai.core.model.AiConversationMessage
import com.campusai.core.model.LocalImageRef
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CaesarImageStorageTest {
    @Test
    fun `legacy cache image migrates to stable no-backup reference and reports deletion`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val legacy = File(context.cacheDir, "legacy-image.jpg")
        FileOutputStream(legacy).use { output ->
            Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).also { bitmap ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                bitmap.recycle()
            }
        }
        val processor = CaesarImageProcessor(context)

        val migrated = processor.migrateLegacy(
            conversationId = "conversation-unit",
            messages = listOf(AiConversationMessage("user", "图片", attachmentPaths = listOf(legacy.absolutePath))),
        ).single()

        assertEquals(1, migrated.attachmentRefs.size)
        assertTrue(migrated.attachmentRefs.single().relativePath.startsWith("ai-conversations/conversation-unit/attachments/"))
        assertFalse(migrated.attachmentRefs.single().relativePath.contains(".."))
        assertTrue(migrated.attachmentPaths.single().startsWith(context.noBackupFilesDir.canonicalPath))
        assertFalse(AiConversationCodec.encode(listOf(migrated)).contains(legacy.absolutePath))

        File(migrated.attachmentPaths.single()).delete()
        val missing = processor.hydrate(listOf(migrated)).single()
        assertTrue(missing.attachmentPaths.isEmpty())
        assertEquals(1, missing.missingAttachmentCount)
        processor.deleteConversation("conversation-unit")
    }

    @Test
    fun `canonical image resolution still rejects files outside conversation storage`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sha = "a".repeat(64)
        val outside = File(context.noBackupFilesDir, "$sha.jpg")
        outside.writeBytes(byteArrayOf(-1, -40, 1, -1, -39))
        try {
            val ref = LocalImageRef(sha, "ai-conversations/../$sha.jpg", "image/jpeg", 1, 1, outside.length(), sha)
            val message = AiConversationMessage("user", "图片", attachmentRefs = listOf(ref))
            val hydrated = CaesarImageProcessor(context).hydrate(listOf(message)).single()
            assertTrue(hydrated.attachmentPaths.isEmpty())
            assertEquals(1, hydrated.missingAttachmentCount)
        } finally { outside.delete() }
    }
}
