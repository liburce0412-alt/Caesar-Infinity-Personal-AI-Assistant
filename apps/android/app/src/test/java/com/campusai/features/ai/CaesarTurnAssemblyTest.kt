package com.campusai.features.ai

import com.campusai.core.ai.AiRoutingException
import com.campusai.core.ai.CloudDailyHealthSummary
import com.campusai.core.ai.CloudHealthDisclosure
import com.campusai.core.model.AiConversationMessage
import com.campusai.core.model.AiMode
import com.campusai.core.model.LocalImageRef
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CaesarTurnAssemblyTest {
    @Test
    fun `full persisted history gives stable regeneration scope and a distinct next user turn`() {
        val history = (1..125).flatMap { index -> listOf(
            AiConversationMessage("user", "第 $index 轮", cloudHealthSensitive = index % 2 == 0),
            AiConversationMessage("assistant", "完成"),
        ) }
        val restored = AiConversationCodec.decode(AiConversationCodec.encode(history))
        fun assemble(messages: List<AiConversationMessage>) = assembleCaesarTurnRequest(
            AiMode.FAST, messages, "添加时间记录", "添加时间记录", JSONObject(), emptyList(), "session", "owner", "model",
        )
        val request = assemble(restored)
        val completed = restored + request.messages.last() + AiConversationMessage("assistant", "已完成")
        val reopened = AiConversationCodec.decode(AiConversationCodec.encode(completed))
        val regeneration = checkNotNull(planAiRegeneration(reopened))

        assertEquals("turn-126", request.toolActionScope)
        assertEquals(request.toolActionScope, assemble(regeneration.history).toolActionScope)
        assertEquals("turn-127", assemble(reopened).toolActionScope)
        assertEquals(request.toolActionScope, request.copy(messages = request.messages.takeLast(4)).toolActionScope)
    }

    @Test
    fun `speech transcript and image remain in one local locked request`() {
        val request = assembleCaesarTurnRequest(
            mode = AiMode.FAST,
            existingMessages = listOf(AiConversationMessage("assistant", "我在。")),
            prompt = "这张图里的课程是什么",
            displayPrompt = "这张图里的课程是什么",
            structuredContext = JSONObject().put("inputPart", "voice_transcript").put("onDevice", true),
            attachments = listOf(CaesarImageAttachment("C:/cache/lesson.jpg", "image/jpeg", "高等数学 A-203")),
            sessionId = "session-voice-image",
            ownerUserId = "owner",
            localModelId = "qwen3.5-2b-mnn",
        )

        assertEquals("qwen3.5-2b-mnn", request.localModelId)
        assertEquals(listOf("C:/cache/lesson.jpg"), request.imagePaths)
        assertTrue(request.requiresLocal)
        assertEquals("这张图里的课程是什么", request.messages.last().content)
        val context = JSONObject(request.structuredContextJson)
        assertEquals("voice_transcript", context.getString("inputPart"))
        assertEquals("高等数学 A-203", context.getJSONArray("imageOcr").getJSONObject(0).getString("text"))
    }

    @Test
    fun `selected Codex marks current images uploadable without unlocking health prompts`() {
        val codexImage = assembleCaesarTurnRequest(
            mode = AiMode.FAST,
            existingMessages = emptyList(),
            prompt = "看看这张图",
            displayPrompt = "看看这张图",
            structuredContext = JSONObject(),
            attachments = listOf(CaesarImageAttachment("C:/private/current.jpg", "image/jpeg", "")),
            sessionId = "codex-vision",
            ownerUserId = "owner",
            localModelId = "qwen3.5-2b-mnn",
            allowCodexImageUpload = true,
        ).withCloudHealthDisclosure(CloudHealthDisclosure.Excluded)
        val healthImage = assembleCaesarTurnRequest(
            mode = AiMode.FAST,
            existingMessages = emptyList(),
            prompt = "分析这张图里的心率数据",
            displayPrompt = "分析这张图里的心率数据",
            structuredContext = JSONObject(),
            attachments = listOf(CaesarImageAttachment("C:/private/health.jpg", "image/jpeg", "")),
            sessionId = "codex-health-vision",
            ownerUserId = "owner",
            localModelId = "qwen3.5-2b-mnn",
            allowCodexImageUpload = true,
        ).withCloudHealthDisclosure(CloudHealthDisclosure.Excluded)

        assertTrue(codexImage.allowCodexImageUpload)
        assertFalse(codexImage.requiresLocal)
        assertTrue(healthImage.allowCodexImageUpload)
        assertTrue(healthImage.requiresLocal)
    }

    @Test
    fun `conversation codec preserves surfaces attachments and rejects malformed recovery`() {
        val sha = "a".repeat(64)
        val original = listOf(
            AiConversationMessage(
                "user",
                "查看图片",
                attachmentPaths = listOf("C:/runtime-only/a.jpg"),
                attachmentRefs = listOf(
                    LocalImageRef(
                        assetId = sha,
                        relativePath = "ai-conversations/session-1/attachments/$sha.jpg",
                        mimeType = "image/jpeg",
                        width = 640,
                        height = 480,
                        byteSize = 1234,
                        sha256 = sha,
                    ),
                ),
                missingAttachmentCount = 1,
            ),
            AiConversationMessage(
                "assistant",
                "已读取",
                presentationJson = "{\"schema\":\"caesar.surface.v1\"}",
                providerReasoningContent = "hidden-provider-state",
                cloudHealthSensitive = true,
            ),
        )

        val restored = AiConversationCodec.decode(AiConversationCodec.encode(original))
        assertTrue(restored.first().attachmentPaths.isEmpty())
        assertEquals(original.first().attachmentRefs, restored.first().attachmentRefs)
        assertEquals(1, restored.first().missingAttachmentCount)
        assertEquals(original.last().copy(providerReasoningContent = null), restored.last())
        assertTrue(restored.last().cloudHealthSensitive)
        assertTrue(AiConversationCodec.decode("not-json").isEmpty())
        assertFalse(AiConversationCodec.decode("[{\"role\":\"root\",\"content\":\"hidden\"}]").any())
    }

    @Test
    fun `stale foreground save keeps appended sensitive automation messages`() {
        val current = listOf(
            AiConversationMessage("assistant", "云端还没更新呢", cloudHealthSensitive = true),
            AiConversationMessage("user", "好的"),
            AiConversationMessage("assistant", "我再陪你等等"),
        )
        val persisted = listOf(
            current.first(),
            AiConversationMessage("assistant", "刚刚有新数据了", cloudHealthSensitive = true),
        )

        val merged = mergePersistedConversationMessages(current, persisted)

        assertEquals(4, merged.size)
        assertEquals("刚刚有新数据了", merged.last().content)
        assertTrue(merged.last().cloudHealthSensitive)
    }

    @Test
    fun `regeneration replaces the persisted old tail and keeps concurrent append`() {
        val history = AiConversationMessage("assistant", "之前的回答")
        val prompt = AiConversationMessage("user", "只回复 OK")
        val oldAnswer = AiConversationMessage("assistant", "不是 OK")
        val newAnswer = AiConversationMessage("assistant", "OK")
        val concurrent = AiConversationMessage(
            "assistant",
            "自动任务刚刚追加的消息",
            cloudHealthSensitive = true,
        )

        val merged = mergePersistedConversationMessages(
            current = listOf(history, prompt, newAnswer),
            persisted = listOf(history, prompt, oldAnswer, concurrent),
            replacedTail = listOf(prompt, oldAnswer),
        )

        assertEquals(listOf(history, prompt, newAnswer, concurrent), merged)
    }

    @Test
    fun `plain final assistant turn produces a precise regeneration plan`() {
        val history = AiConversationMessage("assistant", "你好")
        val prompt = AiConversationMessage("user", "解释 SSE")
        val tool = AiConversationMessage("tool", "内部工具结果")
        val answer = AiConversationMessage("assistant", "SSE 是流式事件协议。")
        val messages = listOf(history, prompt, tool, answer)

        val plan = planAiRegeneration(messages)

        assertEquals(3, plan?.assistantIndex)
        assertEquals(listOf(history), plan?.history)
        assertEquals(prompt, plan?.prompt)
        assertEquals(listOf(prompt, tool, answer), plan?.replacedTail)
    }

    @Test
    fun `regeneration rejects protected attachments presentations and nonfinal answers`() {
        val protectedPrompt = AiConversationMessage(
            role = "user",
            content = "看这张图片",
            attachmentPaths = listOf("C:/private.jpg"),
        )
        assertEquals(
            null,
            planAiRegeneration(listOf(protectedPrompt, AiConversationMessage("assistant", "看到了"))),
        )

        assertEquals(
            null,
            planAiRegeneration(
                listOf(
                    AiConversationMessage("user", "上一张图", missingAttachmentCount = 1),
                    AiConversationMessage("assistant", "原图片已不存在"),
                ),
            ),
        )

        assertEquals(
            null,
            planAiRegeneration(
                listOf(
                    AiConversationMessage("user", "分析健康数据", cloudHealthSensitive = true),
                    AiConversationMessage("assistant", "健康建议"),
                ),
            ),
        )

        assertEquals(
            null,
            planAiRegeneration(
                listOf(
                    AiConversationMessage("user", "生成卡片"),
                    AiConversationMessage("assistant", "完成", presentationJson = "{}"),
                ),
            ),
        )

        assertEquals(
            null,
            planAiRegeneration(
                listOf(
                    AiConversationMessage("user", "第一个问题"),
                    AiConversationMessage("assistant", "旧答案"),
                    AiConversationMessage("user", "后续消息"),
                ),
            ),
        )
    }

    @Test
    fun `explicit health summary unlocks only text health turns while images stay local`() {
        val disclosure = CloudHealthDisclosure.Included(
            CloudDailyHealthSummary(localDate = "2026-08-28", steps = 974),
        )
        val health = assembleCaesarTurnRequest(
            mode = AiMode.FAST,
            existingMessages = emptyList(),
            prompt = "我今天的步数怎么样",
            displayPrompt = "我今天的步数怎么样",
            structuredContext = JSONObject(),
            attachments = emptyList(),
            sessionId = "health-session",
            ownerUserId = "owner",
            localModelId = "qwen3.5-2b-mnn",
        )
        val authorizedHealth = health.withCloudHealthDisclosure(disclosure)
        val image = assembleCaesarTurnRequest(
            mode = AiMode.FAST,
            existingMessages = emptyList(),
            prompt = "看看图片",
            displayPrompt = "看看图片",
            structuredContext = JSONObject(),
            attachments = listOf(CaesarImageAttachment("C:/private.jpg", "image/jpeg", "私密 OCR")),
            sessionId = "image-session",
            ownerUserId = "owner",
            localModelId = "qwen3.5-2b-mnn",
        ).withCloudHealthDisclosure(disclosure)
        val cloudConsent = AiRoutingException("local_model_not_ready", "本地未就绪", canUseCloudOnce = true)

        assertTrue(health.requiresLocal)
        assertFalse(authorizedHealth.requiresLocal)
        assertTrue(canOfferCloudFallback(authorizedHealth, cloudConsent))
        assertTrue(image.requiresLocal)
        assertTrue(JSONObject(image.structuredContextJson).has("imageOcr"))
        assertFalse(canOfferCloudFallback(image, cloudConsent))
    }
}
