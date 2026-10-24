package com.campusai.core.network

import com.campusai.core.ai.AiSystemPolicy
import com.campusai.core.ai.CloudAiProvider
import com.campusai.core.ai.CloudDailyHealthSummary
import com.campusai.core.ai.CloudHealthDisclosure
import com.campusai.core.automation.AutoMessageBatch
import com.campusai.core.localai.LocalPromptPolicy
import com.campusai.core.security.PersonalAiProviderStore
import com.campusai.core.security.ProviderSecretStorage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HealthAutoMessagePromptTest {
    private val client = PersonalCloudHealthAutoMessageClient(PersonalAiProviderStore(object : ProviderSecretStorage {
        override fun read(key: String): String = error("Prompt construction must not read credentials")
        override fun write(key: String, value: String): Boolean = error("Prompt construction must not write credentials")
    }))

    @Test fun `all transports keep the automatic message request and authorized summary`() {
        val summary = CloudDailyHealthSummary(localDate = "2026-10-23", steps = 1234)
        val request = client.request(summary)
        val prompt = request.messages.single().content
        assertEquals("[]", request.caesarToolsJson)
        assertEquals(96, request.maxOutputTokens)
        assertEquals(CloudHealthDisclosure.Included(summary), request.cloudHealthDisclosure)
        val free = cloudFreePayload(request)
        assertEquals(prompt, free.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals(1234, free.getJSONObject("context").getJSONObject("consentedDailyHealthSummary").getInt("steps"))
        for (provider in CloudAiProvider.entries) {
            val payload = OpenAiCompatibleRequestFactory.prepare(provider, request, provider.defaultModel(request.mode)).payload
            val messages = payload.getJSONArray("messages")
            assertTrue(messages.getJSONObject(0).getString("content").contains("messages JSON"))
            assertEquals(prompt, messages.getJSONObject(messages.length() - 1).getString("content"))
            assertTrue(messages.toString().contains("1234"))
            assertFalse(payload.has("tools"))
        }
        val local = LocalPromptPolicy.prepare(request)
        assertTrue(local.first().content.contains("messages JSON"))
        assertEquals(prompt, local.last().content)
    }

    @Test fun `automatic message policy does not change chat or analysis output rules`() {
        val automatic = AiSystemPolicy.instruction(client.request(CloudDailyHealthSummary(localDate = "2026-10-23")).structuredContextJson)
        assertFalse(automatic.contains("JSON 字段名"))
        for (task in listOf("chat", "today_summary", "daily_greeting")) {
            val policy = AiSystemPolicy.instruction(JSONObject().put("task", task).toString())
            assertFalse(policy.contains("健康自动短消息"))
            assertTrue(policy.contains("JSON 字段名"))
        }
    }

    @Test fun `connected caring phrases fit the existing two or three message parser`() {
        for (raw in listOf(
            """{"messages":["今天已经走了不少路啦","歇会儿也挺好的，别太赶啦"]}""",
            """{"messages":["看到这次睡眠记录有点短","今天要是觉得困就先歇一会儿","手头的事慢慢来就好"]}""",
        )) {
            val batch = AutoMessageBatch.parse(raw)
            assertTrue(batch.exceptionOrNull()?.message, batch.isSuccess)
        }
    }
}
