package com.campusai.features.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.campusai.core.model.AiConversationMessage
import com.campusai.core.model.LocalImageRef
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class CaesarImageAttachment(
    val localPath: String,
    val mimeType: String,
    val ocrText: String,
    val imageRef: LocalImageRef? = null,
)

class CaesarImageProcessor(context: Context) {
    private val appContext = context.applicationContext
    // Android may expose /data/user/0 through an alias of /data/data. Use one base
    // for both relative references and resolution, including after canonicalizing a target.
    private val storageBase = appContext.noBackupFilesDir.canonicalFile
    private val storageRoot = File(storageBase, "ai-conversations")

    suspend fun import(uri: Uri, conversationId: String): CaesarImageAttachment = withContext(Dispatchers.IO) {
        cleanupAbandonedTemps()
        val resolver = appContext.contentResolver
        val source = resolver.openInputStream(uri)?.use { input ->
            val bytes = input.readAtMost(MAX_SOURCE_BYTES + 1)
            require(bytes.size <= MAX_SOURCE_BYTES) { "图片不能超过 15 MB。" }
            bytes
        } ?: error("无法读取这张图片。")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片格式无法解析。" }
        var sample = 1
        while (bounds.outWidth / sample > MAX_EDGE * 2 || bounds.outHeight / sample > MAX_EDGE * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("图片解码失败。")
        val orientation = runCatching { ExifInterface(source.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val oriented = decoded.orient(orientation)
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(oriented.width, oriented.height))
        val normalized = if (scale < 1f) Bitmap.createScaledBitmap(oriented, (oriented.width * scale).toInt().coerceAtLeast(1), (oriented.height * scale).toInt().coerceAtLeast(1), true) else oriented
        val attachmentDirectory = attachmentDirectory(conversationId).apply { mkdirs() }
        val temporary = File(attachmentDirectory, ".tmp-${UUID.randomUUID()}.jpg")
        val normalizedWidth = normalized.width
        val normalizedHeight = normalized.height
        FileOutputStream(temporary).use { stream -> check(normalized.compress(Bitmap.CompressFormat.JPEG, 90, stream)); stream.fd.sync() }
        if (normalized !== oriented) normalized.recycle()
        if (oriented !== decoded) oriented.recycle()
        if (!decoded.isRecycled) decoded.recycle()
        val stored = storeTemporaryJpeg(temporary, boundsWidth = normalizedWidth, boundsHeight = normalizedHeight)
        CaesarImageAttachment(resolve(stored)?.absolutePath ?: error("图片持久化失败。"), stored.mimeType, "", stored)
    }.let { attachment ->
        val text = recognize(attachment.localPath)
        attachment.copy(ocrText = text.take(MAX_OCR_CHARS))
    }

    fun delete(attachment: CaesarImageAttachment, protectedRefs: Set<String> = emptySet()) {
        if (attachment.imageRef?.relativePath in protectedRefs) return
        val candidate = File(attachment.localPath)
        if (isInsideStorage(candidate)) candidate.delete()
    }

    fun hydrate(messages: List<AiConversationMessage>): List<AiConversationMessage> = messages.map { message ->
        if (message.attachmentRefs.isEmpty()) return@map message
        val available = message.attachmentRefs.mapNotNull(::resolve)
        message.copy(
            attachmentPaths = available.map(File::getAbsolutePath),
            missingAttachmentCount = maxOf(message.missingAttachmentCount, message.attachmentRefs.size - available.size),
        )
    }

    suspend fun migrateLegacy(
        conversationId: String,
        messages: List<AiConversationMessage>,
    ): List<AiConversationMessage> = withContext(Dispatchers.IO) {
        messages.map { message ->
            if (message.attachmentRefs.isNotEmpty()) {
                val available = message.attachmentRefs.mapNotNull(::resolve)
                return@map message.copy(
                    attachmentPaths = available.map(File::getAbsolutePath),
                    missingAttachmentCount = maxOf(message.missingAttachmentCount, message.attachmentRefs.size - available.size),
                )
            }
            val migrated = message.attachmentPaths.take(MAX_IMAGES_PER_MESSAGE).mapNotNull { legacyPath ->
                val source = File(legacyPath).takeIf(File::isFile) ?: return@mapNotNull null
                // An existing source must remain retryable if copying fails. Do not persist
                // a missing-image marker in place of its only recoverable reference.
                migrateLegacyJpeg(source, conversationId) ?: error("历史图片迁移失败，请重试；原图片仍保留。")
            }
            message.copy(
                attachmentPaths = migrated.mapNotNull(::resolve).map(File::getAbsolutePath),
                attachmentRefs = migrated,
                missingAttachmentCount = (
                    message.missingAttachmentCount + message.attachmentPaths.size - migrated.size
                ).coerceAtMost(MAX_IMAGES_PER_MESSAGE),
            )
        }
    }

    /** A continued local-history conversation owns its own copies, including unsent images. */
    suspend fun copyForConversation(
        conversationId: String,
        messages: List<AiConversationMessage>,
        pendingImages: List<CaesarImageAttachment>,
    ): Pair<List<AiConversationMessage>, List<CaesarImageAttachment>> = withContext(Dispatchers.IO) {
        fun copy(source: File): LocalImageRef = migrateLegacyJpeg(source, conversationId)
            ?: error("无法复制历史图片，请重试；原对话仍保留。")
        val copiedMessages = messages.map { message ->
            val sources = if (message.attachmentRefs.isNotEmpty()) {
                message.attachmentRefs.map(::resolve)
            } else message.attachmentPaths.map { File(it).takeIf(File::isFile) }
            val refs = sources.mapNotNull { it?.let(::copy) }
            // Already missing images remain explicitly missing; a failed copy aborts the fork.
            val missing = maxOf(message.missingAttachmentCount, sources.count { it == null })
            message.copy(
                attachmentRefs = refs,
                attachmentPaths = refs.map { resolve(it)?.absolutePath ?: error("图片复制未完成。") },
                missingAttachmentCount = missing,
            )
        }
        val copiedPending = pendingImages.map { attachment ->
            val source = attachment.imageRef?.let(::resolve)
                ?: File(attachment.localPath).takeIf(File::isFile)
                ?: error("待发送图片已不可用，请重新添加；原对话仍保留。")
            val ref = copy(source)
            attachment.copy(localPath = resolve(ref)?.absolutePath ?: error("图片复制未完成。"), imageRef = ref)
        }
        copiedMessages to copiedPending
    }

    fun deleteConversation(conversationId: String) {
        val directory = runCatching { attachmentDirectory(conversationId).parentFile }.getOrNull() ?: return
        if (directory != storageRoot && isInsideStorage(directory)) directory.deleteRecursively()
    }

    private suspend fun recognize(path: String): String = runCatching {
        suspendCancellableCoroutine { continuation ->
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            val image = InputImage.fromFilePath(appContext, Uri.fromFile(File(path)))
            recognizer.process(image)
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(it.text.trim()) }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
                .addOnCompleteListener { recognizer.close() }
        }
    }.getOrDefault("")

    private fun Bitmap.orient(orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postScale(-1f, 1f); matrix.postRotate(270f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postScale(-1f, 1f); matrix.postRotate(90f) }
            else -> return this
        }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(8 * 1024)
        while (output.size() < limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun storeTemporaryJpeg(
        temporary: File,
        boundsWidth: Int,
        boundsHeight: Int,
    ): LocalImageRef {
        val sha256 = temporary.sha256()
        val target = File(temporary.parentFile, "$sha256.jpg")
        if (target.exists()) {
            temporary.delete()
        } else {
            check(temporary.renameTo(target)) { "无法原子保存图片。" }
        }
        return LocalImageRef(
            assetId = sha256,
            relativePath = target.relativeTo(storageBase).invariantSeparatorsPath,
            mimeType = "image/jpeg",
            width = boundsWidth,
            height = boundsHeight,
            byteSize = target.length(),
            sha256 = sha256,
        )
    }

    private fun migrateLegacyJpeg(source: File, conversationId: String): LocalImageRef? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        val directory = attachmentDirectory(conversationId).apply { mkdirs() }
        val temporary = File(directory, ".tmp-${UUID.randomUUID()}.jpg")
        FileInputStream(source).use { input ->
            FileOutputStream(temporary).use { output -> input.copyTo(output); output.fd.sync() }
        }
        storeTemporaryJpeg(temporary, bounds.outWidth, bounds.outHeight)
    }.getOrNull()

    private fun attachmentDirectory(conversationId: String): File {
        require(CONVERSATION_ID.matches(conversationId)) { "无效的会话标识。" }
        val conversation = File(storageRoot, conversationId).canonicalFile
        val root = storageRoot.canonicalFile
        require(conversation.path.startsWith(root.path + File.separator)) { "无效的会话目录。" }
        return File(conversation, "attachments")
    }

    private fun resolve(ref: LocalImageRef): File? = runCatching {
        if (!SHA256.matches(ref.sha256) || ref.assetId != ref.sha256 || ref.mimeType != "image/jpeg") return@runCatching null
        val candidate = File(storageBase, ref.relativePath).canonicalFile
        if (!isInsideStorage(candidate) || candidate.name != "${ref.sha256}.jpg" || !candidate.isFile) null else candidate
    }.getOrNull()

    private fun isInsideStorage(candidate: File): Boolean = runCatching {
        val root = storageRoot.canonicalFile.path + File.separator
        candidate.canonicalFile.path.startsWith(root)
    }.getOrDefault(false)

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun cleanupAbandonedTemps() {
        val cutoff = System.currentTimeMillis() - TEMP_MAX_AGE_MS
        storageRoot.walkTopDown()
            .filter { it.isFile && it.name.startsWith(".tmp-") && it.lastModified() < cutoff }
            .forEach(File::delete)
    }

    private companion object {
        const val MAX_SOURCE_BYTES = 15 * 1024 * 1024
        const val MAX_EDGE = 1600
        const val MAX_OCR_CHARS = 8_000
        const val MAX_IMAGES_PER_MESSAGE = 4
        const val TEMP_MAX_AGE_MS = 24 * 60 * 60 * 1_000L
        val CONVERSATION_ID = Regex("[A-Za-z0-9._-]{1,128}")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
