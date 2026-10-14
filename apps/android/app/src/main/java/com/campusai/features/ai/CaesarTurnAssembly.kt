package com.campusai.features.ai

import com.campusai.core.ai.AiRequest
import com.campusai.core.ai.CloudHealthDisclosure
import com.campusai.core.model.AiConversationMessage
import com.campusai.core.model.AiMode
import com.campusai.core.model.LocalImageRef
import org.json.JSONArray
import org.json.JSONObject

/** Pure request assembly used by text, speech transcripts and image turns. */
internal fun assembleCaesarTurnRequest(
    mode: AiMode,
    existingMessages: List<AiConversationMessage>,
    prompt: String,
    displayPrompt: String,
    structuredContext: JSONObject,
    attachments: List<CaesarImageAttachment>,
    sessionId: String,
    ownerUserId: String,
    localModelId: String,
    cloudOnce: Boolean = false,
    allowCodexImageUpload: Boolean = false,
    allowCloudFreeImageUpload: Boolean = false,
): AiRequest {
    val context = JSONObject(structuredContext.toString()).put("locale", "zh-CN")
    val ocr = attachments.mapIndexedNotNull { index, image ->
        image.ocrText.takeIf(String::isNotBlank)?.let {
            JSONObject().put("imageIndex", index).put("text", it)
        }
    }
    if (ocr.isNotEmpty()) context.put("imageOcr", JSONArray(ocr))
    return AiRequest(
        mode = mode,
        messages = existingMessages + AiConversationMessage(
            role = "user",
            content = prompt.trim(),
            attachmentPaths = attachments.map(CaesarImageAttachment::localPath),
            attachmentRefs = attachments.mapNotNull(CaesarImageAttachment::imageRef),
        ),
        structuredContextJson = context.toString(),
        maxOutputTokens = 512,
        sessionId = sessionId,
        ownerUserId = ownerUserId,
        userPrompt = displayPrompt.trim(),
        requiresLocal = !cloudOnce && CaesarLocalityPolicy.requiresLocal(
            prompt = displayPrompt,
            hasImages = attachments.isNotEmpty() && !allowCodexImageUpload && !allowCloudFreeImageUpload,
        ),
        localModelId = localModelId,
        imagePaths = attachments.map(CaesarImageAttachment::localPath),
        allowCodexImageUpload = allowCodexImageUpload,
        allowCloudFreeImageUpload = allowCloudFreeImageUpload,
    )
}

/** Health summaries remain consent-gated; images may cross only on explicitly selected vision routes. */
internal fun AiRequest.withCloudHealthDisclosure(disclosure: CloudHealthDisclosure): AiRequest {
    val hasImages = imagePaths.isNotEmpty() || messages.any { message ->
        message.attachmentPaths.isNotEmpty() || message.attachmentRefs.isNotEmpty()
    }
    return copy(
        cloudHealthDisclosure = disclosure,
        requiresLocal = (hasImages && !allowCodexImageUpload && !allowCloudFreeImageUpload) ||
            (requiresLocal && disclosure !is CloudHealthDisclosure.Included),
    )
}

internal object AiConversationCodec {
    fun encode(messages: List<AiConversationMessage>): String = JSONArray(messages.map { message ->
        JSONObject()
            .put("role", message.role)
            .put("content", message.content)
            .put("presentation", message.presentationJson ?: JSONObject.NULL)
            .put("attachments", JSONArray(if (message.attachmentRefs.isEmpty()) message.attachmentPaths else emptyList<String>()))
            .put("imageRefs", JSONArray(message.attachmentRefs.map { it.toJson() }))
            .put("missingAttachmentCount", message.missingAttachmentCount)
            .put("cloudHealthSensitive", message.cloudHealthSensitive)
    }).toString()

    fun decode(raw: String): List<AiConversationMessage> = runCatching {
        val rows = JSONArray(raw)
        buildList {
            repeat(rows.length()) { rowIndex ->
                rows.optJSONObject(rowIndex)?.let { row ->
                    val role = row.optString("role")
                    if (role !in ALLOWED_ROLES) return@let
                    add(
                        AiConversationMessage(
                            role = role,
                            content = row.optString("content"),
                            presentationJson = row.optString("presentation").takeUnless { it.isBlank() || it == "null" },
                            attachmentPaths = buildList {
                                val attachments = row.optJSONArray("attachments") ?: JSONArray()
                                repeat(attachments.length()) { attachmentIndex ->
                                    attachments.optString(attachmentIndex).takeIf(String::isNotBlank)?.let(::add)
                                }
                            },
                            attachmentRefs = buildList {
                                val refs = row.optJSONArray("imageRefs") ?: JSONArray()
                                repeat(refs.length()) { refIndex ->
                                    refs.optJSONObject(refIndex)?.toLocalImageRef()?.let(::add)
                                }
                            },
                            missingAttachmentCount = row.optInt("missingAttachmentCount", 0).coerceIn(0, 4),
                            cloudHealthSensitive = row.optBoolean("cloudHealthSensitive", false),
                        ),
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    private val ALLOWED_ROLES = setOf("system", "user", "assistant", "tool")

    private fun LocalImageRef.toJson(): JSONObject = JSONObject()
        .put("assetId", assetId)
        .put("relativePath", relativePath)
        .put("mimeType", mimeType)
        .put("width", width)
        .put("height", height)
        .put("byteSize", byteSize)
        .put("sha256", sha256)

    private fun JSONObject.toLocalImageRef(): LocalImageRef? = runCatching {
        val sha256 = getString("sha256")
        val assetId = getString("assetId")
        val relativePath = getString("relativePath")
        val mimeType = getString("mimeType")
        val width = getInt("width")
        val height = getInt("height")
        val byteSize = getLong("byteSize")
        require(SHA256.matches(sha256) && assetId == sha256)
        require(RELATIVE_IMAGE_PATH.matches(relativePath) && mimeType == "image/jpeg")
        require(width > 0 && height > 0 && byteSize > 0)
        LocalImageRef(assetId, relativePath, mimeType, width, height, byteSize, sha256)
    }.getOrNull()

    private val SHA256 = Regex("[0-9a-f]{64}")
    private val RELATIVE_IMAGE_PATH = Regex("ai-conversations/[A-Za-z0-9._-]{1,128}/attachments/[0-9a-f]{64}\\.jpg")
}

/** Keeps append-only automation messages when a foreground conversation saves a stale snapshot. */
internal fun mergePersistedConversationMessages(
    current: List<AiConversationMessage>,
    persisted: List<AiConversationMessage>,
    replacedTail: List<AiConversationMessage> = emptyList(),
): List<AiConversationMessage> {
    if (persisted.isEmpty()) return current
    val retainedPersisted = persisted.withoutLastMatchingSequence(replacedTail)
    val remaining = current.groupingBy { it.persistenceIdentity() }.eachCount().toMutableMap()
    val missing = retainedPersisted.filter { message ->
        val identity = message.persistenceIdentity()
        val count = remaining[identity] ?: 0
        if (count > 0) {
            remaining[identity] = count - 1
            false
        } else {
            true
        }
    }
    return current + missing
}

private fun List<AiConversationMessage>.withoutLastMatchingSequence(
    sequence: List<AiConversationMessage>,
): List<AiConversationMessage> {
    if (sequence.isEmpty() || size < sequence.size) return this
    val expected = sequence.map(AiConversationMessage::persistenceIdentity)
    for (start in size - sequence.size downTo 0) {
        val matches = expected.indices.all { offset ->
            this[start + offset].persistenceIdentity() == expected[offset]
        }
        if (matches) return take(start) + drop(start + sequence.size)
    }
    return this
}

internal data class AiRegenerationPlan(
    val assistantIndex: Int,
    val history: List<AiConversationMessage>,
    val prompt: AiConversationMessage,
    val replacedTail: List<AiConversationMessage>,
)

internal fun planAiRegeneration(messages: List<AiConversationMessage>): AiRegenerationPlan? {
    val assistantIndex = messages.indexOfLast { it.role == "assistant" && it.content.isNotBlank() }
    if (assistantIndex < 0 || assistantIndex != messages.lastIndex) return null
    val userIndex = (assistantIndex - 1 downTo 0).firstOrNull { messages[it].role == "user" } ?: return null
    val prompt = messages[userIndex]
    if (
        prompt.content.isBlank() ||
        prompt.cloudHealthSensitive ||
        prompt.attachmentPaths.isNotEmpty() ||
        prompt.attachmentRefs.isNotEmpty() ||
        prompt.missingAttachmentCount > 0 ||
        messages[assistantIndex].presentationJson != null
    ) return null
    return AiRegenerationPlan(
        assistantIndex = assistantIndex,
        history = messages.take(userIndex),
        prompt = prompt,
        replacedTail = messages.drop(userIndex),
    )
}

private fun AiConversationMessage.persistenceIdentity(): String = buildString {
    append(role).append('\u0000').append(content).append('\u0000')
    append(presentationJson.orEmpty()).append('\u0000').append(cloudHealthSensitive)
    attachmentRefs.forEach { append('\u0000').append(it.relativePath).append('|').append(it.sha256) }
    if (attachmentRefs.isEmpty()) attachmentPaths.forEach { append('\u0000').append(it) }
}
