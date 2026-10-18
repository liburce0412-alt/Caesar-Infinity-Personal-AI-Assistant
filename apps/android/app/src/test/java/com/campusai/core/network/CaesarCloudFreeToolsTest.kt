package com.campusai.core.network

import com.campusai.core.ai.AiEvent
import com.campusai.core.ai.AiRequest
import com.campusai.core.ai.AiRoutingException
import com.campusai.core.model.AiConversationMessage
import com.campusai.core.model.AiMode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CaesarCloudFreeToolsTest {
    private fun request() = AiRequest(AiMode.FAST, listOf(AiConversationMessage("user", "查一下公开资料")),
        ownerUserId = "owner", allowWebSearch = true, caesarToolsJson = """[{"name":"web.search","parameters":[]}]""")
    private fun call(name: String = "web.search", id: String = "call-1") = OpenAiProviderToolCall(
        id, name.replace('.', '_'), name, """{"query":"公开资料"}""", null,
    )
    private val toolEvent = """{"type":"tool_call","id":"call-1","name":"web.search","wireName":"web_search","arguments":"{\"query\":\"公开资料\"}"}"""
    private fun meta(protocol: Boolean = true, model: String = "glm-4-flash-250414") =
        """{"type":"meta","model":"$model","requestId":"tools-test"${if (protocol) ",\"toolProtocol\":1" else ""}}"""
    private val done = """{"type":"done","elapsedMs":20}"""

    @Test fun `only allowed projected tools are selected and web permission still applies`() {
        val input = request().copy(caesarToolsJson = """[
            {"name":"health.get_snapshot"},{"name":"message.send"},{"name":"web.open"},
            {"name":"web.search"},{"name":"course.list"},{"name":"web.search"}]
        """)
        assertEquals(listOf("web.search", "course.list"), CaesarCloudFreeTools.projectedNames(input))
        assertEquals(listOf("course.list"), CaesarCloudFreeTools.projectedNames(input.copy(allowWebSearch = false)))
        val payload = cloudFreePayload(input)
        assertEquals(1, payload.getInt("toolProtocol"))
        assertEquals("web.search", payload.getJSONArray("tools").getString(0))
        assertFalse(payload.toString().contains("parameters"))
        assertFalse(payload.toString().contains("health.get_snapshot"))
    }

    @Test fun `cloud never receives local memories or historical memory reads`() {
        val input = request().copy(
            structuredContextJson = """{"confirmedMemories":[{"content":"night oxygen 88%"}],"study":{"minutes":20}}""",
            caesarToolsJson = """[{"name":"memory.list"},{"name":"memory.propose"}]""",
            messages = listOf(
                AiConversationMessage("user", "private memory question", cloudHealthSensitive = true),
                AiConversationMessage("assistant", "private memory answer", cloudHealthSensitive = true),
                AiConversationMessage("assistant", ProviderToolCallEnvelope.encode(call("memory.list"))),
                AiConversationMessage("tool", "private old memory result"),
                AiConversationMessage("user", "普通新问题"),
            ),
        )
        val payload = cloudFreePayload(input)
        assertEquals(listOf("memory.propose"), CaesarCloudFreeTools.projectedNames(input))
        assertFalse(payload.toString().contains("oxygen"))
        assertFalse(payload.toString().contains("private"))
        assertFalse(payload.toString().contains("memory.list"))
        assertTrue(payload.toString().contains("minutes"))
        val memoryPair = listOf(AiConversationMessage("assistant", ProviderToolCallEnvelope.encode(call("memory.list"))),
            AiConversationMessage("tool", "private result"))
        try {
            cloudFreePayload(request().copy(messages = request().messages + memoryPair))
            fail("A current memory read must not be replayed to cloud")
        } catch (error: AiRoutingException) { assertEquals("invalid_tool_protocol", error.code) }
    }

    @Test fun `only complete current-turn pairs are replayed and health history stays excluded`() {
        val input = request().copy(messages = listOf(
            AiConversationMessage("user", "old health", cloudHealthSensitive = true),
            AiConversationMessage("assistant", "private health response", cloudHealthSensitive = true),
            AiConversationMessage("assistant", ProviderToolCallEnvelope.encode(call(id = "old-call"))),
            AiConversationMessage("tool", "old search result"),
            AiConversationMessage("user", "查一下公开资料"),
            AiConversationMessage("assistant", ProviderToolCallEnvelope.encode(call())),
            AiConversationMessage("tool", """{"ok":true,"data":{"results":[]}}"""),
        ))
        val payload = cloudFreePayload(input)
        val messages = payload.getJSONArray("messages")
        assertEquals(3, messages.length())
        assertEquals("call-1", messages.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("call-1", messages.getJSONObject(2).getString("tool_call_id"))
        assertFalse(payload.toString().contains("old-call"))
        assertFalse(payload.toString().contains("old search"))
        assertFalse(payload.toString().contains("health response"))
    }

    @Test fun `invalid or oversized replay cannot silently drop or truncate tool results`() {
        val pair = listOf(AiConversationMessage("assistant", ProviderToolCallEnvelope.encode(call())), AiConversationMessage("tool", "{}"))
        for (tail in listOf(pair.take(1), pair.map { if (it.role == "tool") it.copy(content = "x".repeat(8001)) else it },
            pair.map { if (it.role == "tool") it.copy(cloudHealthSensitive = true) else it },
            listOf(AiConversationMessage("assistant", ProviderToolCallEnvelope.encode(call("health.get_snapshot"))), pair.last()))) {
            try { cloudFreePayload(request().copy(messages = request().messages + tail)); fail("Expected boundary rejection") }
            catch (_: AiRoutingException) { }
        }
    }

    @Test fun `a complete negotiated tool call becomes the existing provider envelope`() = runTest {
        val events = engine(listOf(meta(), toolEvent, done)).stream(request()).toList()
        val call = events.last() as AiEvent.ToolCallRequested
        assertEquals("web.search", call.name)
        assertEquals("call-1", ProviderToolCallEnvelope.decode(call.rawContent)?.id)
        assertEquals("公开资料", JSONObject(call.argumentsJson).getString("query"))
        assertFalse(events.any { it is AiEvent.Done })
    }

    @Test fun `legacy relay capability mismatch fails before any executable tool event`() = runTest {
        val events = engine(listOf(meta(protocol = false), toolEvent, done)).stream(request()).toList()
        assertEquals("cloud_tools_unavailable", (events.single() as AiEvent.Error).code)
    }

    @Test fun `tool call is never exposed before final completion`() = runTest {
        for (ending in listOf(emptyList(), listOf("""{"type":"error","code":"cloud_interrupted","message":"中断"}"""))) {
            val events = engine(listOf(meta(), toolEvent) + ending).stream(request()).toList()
            assertTrue(events.last() is AiEvent.Error)
            assertFalse(events.any { it is AiEvent.ToolCallRequested })
        }
    }

    @Test fun `unprojected or duplicate calls cannot reach the runtime`() = runTest {
        val forbidden = toolEvent.replace("web.search", "time.delete_record").replace("web_search", "time_delete_record")
        for (wire in listOf(listOf(forbidden), listOf(toolEvent, toolEvent))) {
            val events = engine(listOf(meta()) + wire + done).stream(request()).toList()
            assertTrue(events.last() is AiEvent.Error)
            assertFalse(events.any { it is AiEvent.ToolCallRequested })
        }
    }

    @Test fun `retired model metadata cannot produce an executable tool call`() = runTest {
        val events = engine(listOf(meta(model = "glm-4.7-flash"), toolEvent, done)).stream(request()).toList()
        assertTrue(events.single() is AiEvent.Error)
        assertFalse(events.any { it is AiEvent.ToolCallRequested })
    }

    private fun engine(events: List<String>): CaesarCloudFreeEngine {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/event-stream")
                .body(events.joinToString("") { "data: $it\n\n" }.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        return CaesarCloudFreeEngine({ "https://backend.example" }, { "session" }, { true }, client)
    }
}
