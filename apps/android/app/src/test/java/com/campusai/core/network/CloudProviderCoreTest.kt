package com.campusai.core.network

import com.campusai.core.ai.AiRequest
import com.campusai.core.ai.CloudAiProvider
import com.campusai.core.ai.CloudDailyHealthSummary
import com.campusai.core.ai.CloudHealthDisclosure
import com.campusai.core.model.AiConversationMessage
import com.campusai.core.model.AiMode
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CloudProviderCoreTest {
    @Test fun `all personal cloud providers omit free-text memories and memory read envelopes`() {
        val memoryCall = OpenAiProviderToolCall("memory-call", "memory_list", "memory.list", "{}", null)
        for (provider in CloudAiProvider.entries) {
            val prepared = OpenAiCompatibleRequestFactory.prepare(provider, request(
                context = """{"confirmedMemories":[{"content":"night oxygen 88%"}],"study":{"minutes":20}}""",
                tools = """[{"name":"memory.list","description":"local memory","parameters":[]},{"name":"memory.propose","description":"proposal","parameters":[]}]""",
                messages = listOf(
                    AiConversationMessage("user", "private memory question", cloudHealthSensitive = true),
                    AiConversationMessage("assistant", "private memory answer", cloudHealthSensitive = true),
                    AiConversationMessage("assistant", ProviderToolCallEnvelope.encode(memoryCall)),
                    AiConversationMessage("tool", "private old memory result"),
                    AiConversationMessage("user", "普通新问题"),
                ),
            ), "test-model")
            val encoded = prepared.payload.toString()
            assertFalse(encoded.contains("oxygen"))
            assertFalse(encoded.contains("private memory"))
            assertFalse(encoded.contains("private old"))
            assertFalse(encoded.contains("memory_list"))
            assertTrue(encoded.contains("memory_propose"))
            assertTrue(encoded.contains("minutes"))
        }
    }

    @Test
    fun `Codex uses its requested defaults while accepting a dynamic HTTPS model catalog`() {
        val provider = CloudAiProvider.CODEX

        assertEquals("https://node.tail9a6cbb.ts.net/v1", provider.defaultBaseUrl)
        assertEquals("https://node.tail9a6cbb.ts.net/v1/models", provider.modelsUrl)
        assertEquals("https://node.tail9a6cbb.ts.net/v1/chat/completions", provider.chatCompletionsUrl)
        assertEquals("gpt-5.6-sol", provider.defaultModel(AiMode.FAST))
        assertEquals("gpt-5.6-sol", provider.defaultModel(AiMode.DEEP))
        assertEquals(
            "https://private-node.example/v2/models",
            provider.modelsUrl("  https://private-node.example/v2/  "),
        )
        assertEquals(
            "https://private-node.example/v2/chat/completions",
            provider.chatCompletionsUrl("https://private-node.example/v2/"),
        )

        assertTrue(provider.acceptsModelId("gpt-5.6-sol"))
        assertTrue(provider.acceptsModelId("o3"))
        assertTrue(provider.acceptsModelId("future-reasoner_2027.04"))
        assertFalse(provider.acceptsModelId("not/a/model"))
        val parsed = OpenAiCompatibleModelParser.parse(
            provider,
            """{"data":[{"id":"gpt-5.6-sol"},{"id":"o3"},{"id":"future-reasoner_2027.04"},{"id":"not/a/model"}]}""",
        )
        assertEquals(listOf("gpt-5.6-sol", "o3", "future-reasoner_2027.04"), parsed.map { it.id })

        assertTrue(runCatching { provider.resolveBaseUrl("http://private-node.example/v1") }.isFailure)
        assertTrue(runCatching { provider.resolveBaseUrl("https://user:pass@private-node.example/v1") }.isFailure)
        assertTrue(runCatching { provider.resolveBaseUrl("https://private-node.example/v1?token=secret") }.isFailure)
        assertTrue(runCatching { provider.resolveBaseUrl("https://private-node.example/v1#fragment") }.isFailure)
    }

    @Test
    fun `providers use only official fixed endpoints and validate their own model namespace`() {
        assertEquals("https://api.deepseek.com/chat/completions", CloudAiProvider.DEEPSEEK.chatCompletionsUrl)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            CloudAiProvider.GOOGLE_GEMINI.chatCompletionsUrl,
        )
        assertTrue(CloudAiProvider.DEEPSEEK.acceptsModelId("deepseek-v4-flash"))
        assertFalse(CloudAiProvider.DEEPSEEK.acceptsModelId("gemini-3.7-flash"))
        assertTrue(CloudAiProvider.GOOGLE_GEMINI.acceptsModelId("models/gemini-3.7-flash"))
        assertFalse(CloudAiProvider.GOOGLE_GEMINI.acceptsModelId("gemini-embedding-001"))

        val geminiPayload = OpenAiCompatibleRequestFactory.prepare(
            CloudAiProvider.GOOGLE_GEMINI,
            request(),
            "gemini-3.7-flash",
        ).payload
        val deepSeekPayload = OpenAiCompatibleRequestFactory.prepare(
            CloudAiProvider.DEEPSEEK,
            request(),
            "deepseek-v4-flash",
        ).payload
        assertFalse(geminiPayload.has("temperature"))
        assertTrue(deepSeekPayload.has("temperature"))
    }

    @Test
    fun `cloud boundary strips health identifiers and health tools by default`() {
        val prepared = OpenAiCompatibleRequestFactory.prepare(
            CloudAiProvider.DEEPSEEK,
            request(
                context = """{"task":"chat","study":{"minutes":20},"health":{"steps":9000},"dailyHealthSummary":{"sleep":999},"metrics":{"heartRate":70,"steps":888},"deviceId":"private-device"}""",
                tools = """[
                    {"name":"health.get_snapshot","description":"health","parameters":[]},
                    {"name":"time.create_record","description":"record","parameters":[{"name":"minutes","type":"integer","required":true,"description":"duration"}]}
                ]""",
            ),
            "deepseek-v4-flash",
        )

        val encoded = prepared.payload.toString()
        assertTrue(encoded.contains("\\\"minutes\\\":20"))
        assertFalse(encoded.contains("9000"))
        assertFalse(encoded.contains("999"))
        assertFalse(encoded.contains("888"))
        assertFalse(encoded.contains("70"))
        assertFalse(encoded.contains("private-device"))
        assertFalse(encoded.contains("health_get_snapshot"))
        assertTrue(encoded.contains("time_create_record"))
        assertFalse(prepared.payload.has("tool_choice"))
        assertEquals(mapOf("time_create_record" to "time.create_record"), prepared.originalToolNamesByWireName)
    }

    @Test
    fun `cloud request never serializes local image paths`() {
        val privatePath = "/data/user/0/com.campusai/no_backup/ai-conversations/unit/attachments/secret.jpg"
        val prepared = OpenAiCompatibleRequestFactory.prepare(
            CloudAiProvider.GOOGLE_GEMINI,
            request(context = """{"imageOcr":[{"text":"private-ocr-text"}]}""")
                .copy(imagePaths = listOf(privatePath), requiresLocal = true),
            "gemini-3.7-flash",
        ).payload.toString()

        assertFalse(prepared.contains(privatePath))
        assertFalse(prepared.contains("<img>"))
        assertFalse(prepared.contains("private-ocr-text"))
    }

    @Test
    fun `Codex sends only current normalized JPEGs as OpenAI image content parts`() {
        val historicalPath = "/data/user/0/com.campusai/no_backup/ai-conversations/old/attachments/history.jpg"
        val currentImage = File.createTempFile("campusai-codex-vision", ".jpg")
        currentImage.writeBytes(
            byteArrayOf(
                0xff.toByte(),
                0xd8.toByte(),
                0x01,
                0x02,
                0xff.toByte(),
                0xd9.toByte(),
            ),
        )
        try {
            val visionRequest = request(
                messages = listOf(
                    AiConversationMessage("user", "上一张图", attachmentPaths = listOf(historicalPath)),
                    AiConversationMessage("assistant", "已经看过。"),
                    AiConversationMessage("user", "只分析这张新图", attachmentPaths = listOf(currentImage.absolutePath)),
                ),
            ).copy(
                imagePaths = listOf(currentImage.absolutePath),
                allowCodexImageUpload = true,
            )

            val codexPayload = OpenAiCompatibleRequestFactory.prepare(
                CloudAiProvider.CODEX,
                visionRequest,
                "gpt-5.6-sol",
            ).payload
            val codexJson = codexPayload.toString()
            val encodedUser = codexPayload.getJSONArray("messages")
                .getJSONObject(codexPayload.getJSONArray("messages").length() - 1)
                .getJSONArray("content")

            assertEquals("text", encodedUser.getJSONObject(0).getString("type"))
            assertEquals("只分析这张新图", encodedUser.getJSONObject(0).getString("text"))
            assertEquals("image_url", encodedUser.getJSONObject(1).getString("type"))
            assertTrue(
                encodedUser.getJSONObject(1).getJSONObject("image_url").getString("url")
                    .startsWith("data:image/jpeg;base64,"),
            )
            assertFalse(codexJson.contains(currentImage.absolutePath))
            assertFalse(codexJson.contains(historicalPath))

            val geminiPayload = OpenAiCompatibleRequestFactory.prepare(
                CloudAiProvider.GOOGLE_GEMINI,
                visionRequest,
                "gemini-3.7-flash",
            ).payload.toString()
            assertFalse(geminiPayload.contains("data:image"))
            assertFalse(geminiPayload.contains(currentImage.absolutePath))
        } finally {
            currentImage.delete()
        }
    }

    @Test
    fun `Codex rejects more than four current turn images before reading paths`() {
        val failure = runCatching {
            OpenAiCompatibleRequestFactory.prepare(
                CloudAiProvider.CODEX,
                request().copy(
                    imagePaths = List(5) { index -> "/private/missing-$index.jpg" },
                    allowCodexImageUpload = true,
                ),
                "gpt-5.6-sol",
            )
        }.exceptionOrNull()

        assertTrue(failure is CloudProviderException)
        assertEquals("codex_image_limit", (failure as CloudProviderException).code)
        assertFalse(failure.message.orEmpty().contains("missing-"))
    }

    @Test
    fun `Codex rejects an oversized normalized image without serializing its path`() {
        val oversized = File.createTempFile("campusai-codex-oversized", ".jpg")
        RandomAccessFile(oversized, "rw").use { file -> file.setLength(4L * 1024 * 1024 + 1) }
        try {
            val failure = runCatching {
                OpenAiCompatibleRequestFactory.prepare(
                    CloudAiProvider.CODEX,
                    request(
                        messages = listOf(
                            AiConversationMessage(
                                "user",
                                "分析图片",
                                attachmentPaths = listOf(oversized.absolutePath),
                            ),
                        ),
                    ).copy(
                        imagePaths = listOf(oversized.absolutePath),
                        allowCodexImageUpload = true,
                    ),
                    "gpt-5.6-sol",
                )
            }.exceptionOrNull()

            assertTrue(failure is CloudProviderException)
            assertEquals("codex_image_too_large", (failure as CloudProviderException).code)
            assertFalse(failure.message.orEmpty().contains(oversized.absolutePath))
        } finally {
            oversized.delete()
        }
    }

    @Test
    fun `health disclosure sends only typed daily aggregate when explicitly included`() {
        val excluded = OpenAiCompatibleRequestFactory.prepare(
            CloudAiProvider.GOOGLE_GEMINI,
            request(),
            "gemini-3.7-flash",
        ).payload.toString()
        assertFalse(excluded.contains("health_summary"))

        val included = OpenAiCompatibleRequestFactory.prepare(
            CloudAiProvider.GOOGLE_GEMINI,
            request().copy(
                cloudHealthDisclosure = CloudHealthDisclosure.Included(
                    CloudDailyHealthSummary(
                        localDate = "2026-08-28",
                        steps = 974,
                        sleepMinutes = 430,
                        averageHeartRateBpm = 68.0,
                        averageOxygenSaturationPercent = 101.0,
                    ),
                ),
            ),
            "gemini-3.7-flash",
        ).payload.toString()

        assertTrue(included.contains("health_summary"))
        assertTrue(included.contains("974"))
        assertTrue(included.contains("430"))
        assertTrue(included.contains("68"))
        assertFalse(included.contains("101"))
        assertFalse(included.contains("originPackages"))
    }

    @Test
    fun `stream parser assembles fragmented tool call and preserves Gemini thought signature`() {
        val first = checkNotNull(
            OpenAiCompatibleStreamParser.parse(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","extra_content":{"google":{"thought_signature":"signature-unit-value"}},"function":{"name":"time_create_record","arguments":"{\"min"}}]}}]}""",
            ),
        )
        val second = checkNotNull(
            OpenAiCompatibleStreamParser.parse(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"utes\":25}"}}]}}],"usage":{"prompt_tokens":12,"completion_tokens":8}}""",
            ),
        )
        val accumulator = OpenAiToolCallAccumulator(
            CloudAiProvider.GOOGLE_GEMINI,
            mapOf("time_create_record" to "time.create_record"),
        )
        (first.toolCallFragments + second.toolCallFragments).forEach(accumulator::append)
        val call = checkNotNull(accumulator.complete())

        assertEquals("time.create_record", call.originalName)
        assertEquals("{\"minutes\":25}", call.argumentsJson)
        assertEquals("signature-unit-value", call.thoughtSignature)
        assertEquals(12L, second.inputTokens)
        assertEquals(8L, second.outputTokens)

        val followUp = OpenAiCompatibleRequestFactory.prepare(
            CloudAiProvider.GOOGLE_GEMINI,
            request(
                tools = """[{"name":"time.create_record","description":"record","parameters":[]}]""",
                messages = listOf(
                    AiConversationMessage("user", "记录 25 分钟"),
                    AiConversationMessage("assistant", ProviderToolCallEnvelope.encode(call)),
                    AiConversationMessage("tool", "{\"ok\":true}"),
                ),
            ),
            "gemini-3.7-flash",
        ).payload
        val replayedCall = followUp.getJSONArray("messages").getJSONObject(2)
            .getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("signature-unit-value", replayedCall.getJSONObject("extra_content").getJSONObject("google").getString("thought_signature"))
        assertEquals("call-1", followUp.getJSONArray("messages").getJSONObject(3).getString("tool_call_id"))
        assertEquals("auto", followUp.getString("tool_choice"))
    }

    @Test
    fun `DeepSeek thinking tool continuation replays reasoning and non null assistant content`() {
        val tools = """[
            {"name":"time.create_record","description":"record","parameters":[{"name":"minutes","type":"integer","required":true,"description":"duration"}]}
        ]"""
        val prepared = OpenAiCompatibleRequestFactory.prepare(
            CloudAiProvider.DEEPSEEK,
            request(mode = AiMode.DEEP, tools = tools),
            "deepseek-v4-pro",
        )
        assertTrue(prepared.payload.has("tools"))
        assertFalse(prepared.payload.has("tool_choice"))

        val chunk = checkNotNull(
            OpenAiCompatibleStreamParser.parse(
                """{"choices":[{"delta":{"reasoning_content":"需要先记录。","content":"我来记录。","tool_calls":[{"index":0,"id":"call-deep-1","type":"function","function":{"name":"time_create_record","arguments":"{\"minutes\":25}"}}]}}]}""",
            ),
        )
        assertEquals("需要先记录。", chunk.reasoningContentDelta)
        val accumulator = OpenAiToolCallAccumulator(
            CloudAiProvider.DEEPSEEK,
            mapOf("time_create_record" to "time.create_record"),
        )
        chunk.toolCallFragments.forEach(accumulator::append)
        val call = checkNotNull(
            accumulator.complete(
                assistantContent = checkNotNull(chunk.delta),
                reasoningContent = checkNotNull(chunk.reasoningContentDelta),
            ),
        )

        val followUp = OpenAiCompatibleRequestFactory.prepare(
            CloudAiProvider.DEEPSEEK,
            request(
                mode = AiMode.DEEP,
                tools = tools,
                messages = listOf(
                    AiConversationMessage("user", "记录 25 分钟"),
                    AiConversationMessage("assistant", ProviderToolCallEnvelope.encode(call)),
                    AiConversationMessage("tool", "{\"ok\":true}"),
                ),
            ),
            "deepseek-v4-pro",
        ).payload
        val replayedAssistant = followUp.getJSONArray("messages").getJSONObject(2)
        assertEquals("我来记录。", replayedAssistant.getString("content"))
        assertFalse(replayedAssistant.isNull("content"))
        assertEquals("需要先记录。", replayedAssistant.getString("reasoning_content"))
        assertEquals("call-deep-1", followUp.getJSONArray("messages").getJSONObject(3).getString("tool_call_id"))
    }

    @Test
    fun `cloud history omits prior health disclosure turns and preserves DeepSeek continuation state`() {
        val prepared = OpenAiCompatibleRequestFactory.prepare(
            CloudAiProvider.DEEPSEEK,
            request(
                mode = AiMode.DEEP,
                tools = """[{"name":"time.create_record","description":"record","parameters":[]}]""",
                messages = listOf(
                    AiConversationMessage("user", "普通问题"),
                    AiConversationMessage(
                        "assistant",
                        "普通回答",
                        providerReasoningContent = "普通回答的推理状态",
                    ),
                    AiConversationMessage("user", "敏感健康问题 974", cloudHealthSensitive = true),
                    AiConversationMessage("assistant", "敏感健康回答 430", cloudHealthSensitive = true),
                    AiConversationMessage("user", "下一轮"),
                ),
            ),
            "deepseek-v4-pro",
        ).payload.toString()

        assertTrue(prepared.contains("普通问题"))
        assertTrue(prepared.contains("普通回答的推理状态"))
        assertTrue(prepared.contains("下一轮"))
        assertFalse(prepared.contains("敏感健康问题"))
        assertFalse(prepared.contains("敏感健康回答"))
        assertFalse(prepared.contains("974"))
        assertFalse(prepared.contains("430"))
    }

    @Test
    fun `model parser filters non chat models and malformed responses`() {
        val parsed = OpenAiCompatibleModelParser.parse(
            CloudAiProvider.GOOGLE_GEMINI,
            """{"data":[{"id":"models/gemini-3.7-flash"},{"id":"gemini-embedding-001"},{"id":"other-model"}]}""",
        )
        assertEquals(listOf("gemini-3.7-flash"), parsed.map { it.id })
        assertTrue(OpenAiCompatibleModelParser.parse(CloudAiProvider.DEEPSEEK, "not-json").isEmpty())
        assertNull(OpenAiCompatibleStreamParser.parse("[DONE]"))
        assertTrue(OpenAiCompatibleStreamParser.containsProviderError("""{"error":{"message":"secret detail"}}"""))
    }

    @Test
    fun `unprojected and parallel tool calls fail closed`() {
        val unknown = OpenAiToolCallAccumulator(CloudAiProvider.DEEPSEEK, emptyMap())
        unknown.append(OpenAiToolCallFragment(0, "call-1", "unknown_tool", "{}", null))
        try {
            unknown.complete()
            fail("unprojected tool must fail")
        } catch (error: CloudProviderException) {
            assertEquals("tool_not_projected", error.code)
        }

        val parallel = OpenAiToolCallAccumulator(
            CloudAiProvider.DEEPSEEK,
            mapOf("time_one" to "time.one", "time_two" to "time.two"),
        )
        parallel.append(OpenAiToolCallFragment(0, "call-1", "time_one", "{}", null))
        parallel.append(OpenAiToolCallFragment(1, "call-2", "time_two", "{}", null))
        try {
            parallel.complete()
            fail("parallel tools must fail")
        } catch (error: CloudProviderException) {
            assertEquals("parallel_tool_calls_unsupported", error.code)
        }
    }

    private fun request(
        mode: AiMode = AiMode.FAST,
        context: String = "{}",
        tools: String = "[]",
        messages: List<AiConversationMessage> = listOf(AiConversationMessage("user", "你好")),
    ) = AiRequest(
        mode = mode,
        messages = messages,
        structuredContextJson = context,
        caesarToolsJson = tools,
    )
}
