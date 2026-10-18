package com.campusai.core.network

import com.campusai.core.ai.AiEvent
import com.campusai.core.ai.AiRequest
import com.campusai.core.ai.AiRoutingException
import com.campusai.core.model.AiConversationMessage
import com.campusai.core.model.AiMode
import com.campusai.core.model.AiProvider
import com.campusai.core.preferences.initialAiProvider
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CaesarCloudFreeEngineTest {
    private fun request() = AiRequest(AiMode.FAST, listOf(AiConversationMessage("user", "你好")), ownerUserId = "owner")

    @Test fun `new install defaults to cloud while existing choices remain unchanged`() {
        assertEquals(AiProvider.CAESAR_CLOUD_FREE, initialAiProvider(null, false))
        assertEquals(AiProvider.AUTO, initialAiProvider(null, true))
        AiProvider.entries.forEach { assertEquals(it, initialAiProvider(it.name, true)) }
    }

    @Test fun `text request strips health history and private structured data`() {
        val payload = cloudFreePayload(request().copy(
            structuredContextJson = """{"task":"chat","health":{"steps":9000},"imageOcr":"secret","deviceId":"private"}""",
            messages = listOf(AiConversationMessage("user", "private-health", cloudHealthSensitive = true),
                AiConversationMessage("assistant", "private-response", cloudHealthSensitive = true),
                AiConversationMessage("user", "你好")),
        )).toString()
        assertFalse(payload.contains("private"))
        assertFalse(payload.contains("secret"))
        assertFalse(payload.contains("9000"))
        assertTrue(payload.contains("你好"))
    }

    @Test fun `local-only request cannot be uploaded even when image capability is set`() {
        try {
            cloudFreePayload(request().copy(requiresLocal = true, allowCloudFreeImageUpload = true))
            fail("Expected local boundary")
        } catch (error: AiRoutingException) { assertEquals("local_required", error.code) }
    }

    @Test fun `only the explicitly attached current JPEG is encoded and paths stay local`() {
        val file = File.createTempFile("cloud-free", ".jpg")
        try {
            file.writeBytes(byteArrayOf(-1, -40, 1, 2, -1, -39))
            val input = request().copy(
                messages = listOf(AiConversationMessage("user", "old", attachmentPaths = listOf("/private/history.jpg")),
                    AiConversationMessage("user", "这是什么", attachmentPaths = listOf(file.absolutePath))),
                imagePaths = listOf(file.absolutePath), allowCloudFreeImageUpload = true,
            )
            val payload = cloudFreePayload(input)
            assertEquals(1, payload.getJSONArray("images").length())
            assertTrue(payload.getJSONArray("images").getString(0).startsWith("data:image/jpeg;base64,"))
            assertFalse(payload.toString().contains(file.absolutePath))
            assertFalse(payload.toString().contains("/private/history.jpg"))
            try { cloudFreePayload(input.copy(allowCloudFreeImageUpload = false)); fail("Expected upload gate") }
            catch (error: AiRoutingException) { assertEquals("local_required", error.code) }
        } finally { file.delete() }
    }

    @Test fun `recent history is bounded without truncating the current question`() {
        val payload = cloudFreePayload(request().copy(messages = (1..20).map {
            AiConversationMessage("user", "question-$it")
        }))
        val messages = payload.getJSONArray("messages")
        assertEquals(12, messages.length())
        assertEquals("question-20", messages.getJSONObject(11).getString("content"))
    }

    @Test fun `relay stream resolves the model and requires a done event`() = runTest {
        for (complete in listOf(true, false)) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                assertEquals("https://backend.example/caesar-cloud/v1/chat", chain.request().url.toString())
                assertEquals("Bearer session-token", chain.request().header("Authorization"))
                val data = listOf(
                    """{"type":"meta","model":"glm-4-flash-250414","requestId":"request-1"}""",
                    """{"type":"delta","text":"你好"}""",
                ) + if (complete) listOf("""{"type":"done","elapsedMs":10}""") else emptyList()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Content-Type", "text/event-stream")
                    .body(data.joinToString("") { "data: $it\n\n" }.toResponseBody("text/event-stream".toMediaType())).build()
            }.build()
            val engine = CaesarCloudFreeEngine({ "https://backend.example" }, { "session-token" }, { true }, client)
            val events = engine.stream(request()).toList()
            assertEquals(AiProvider.CAESAR_CLOUD_FREE, (events.first() as AiEvent.Meta).provider)
            assertEquals(complete, events.last() is AiEvent.Done)
            assertEquals(!complete, events.last() is AiEvent.Error)
        }
    }

    @Test fun `changed session prevents any outgoing request`() = runTest {
        var called = false
        val http = OkHttpClient.Builder().addInterceptor { called = true; error("must not call") }.build()
        val engine = CaesarCloudFreeEngine({ "https://backend.example" }, { "old-session" }, { false }, http)
        assertEquals("authentication_required", (engine.stream(request()).toList().single() as AiEvent.Error).code)
        assertFalse(called)
    }

    @Test fun `explicit debug relay keeps owner authentication and the same fast payload`() = runTest {
        var authenticatedOwner: String? = null
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertEquals(CAESAR_CLOUD_LOOPBACK_ENDPOINT, chain.request().url.toString())
            assertEquals("Bearer session-token", chain.request().header("Authorization"))
            val buffer = okio.Buffer()
            chain.request().body!!.writeTo(buffer)
            val payload = JSONObject(buffer.readUtf8())
            assertEquals("fast", payload.getString("mode"))
            assertFalse(payload.has("allowTextFallback"))
            val events = listOf(
                """{"type":"meta","model":"glm-4-flash-250414","requestId":"debug-relay-test"}""",
                """{"type":"delta","text":"你好"}""",
                """{"type":"done","elapsedMs":10}""",
            )
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/event-stream")
                .body(events.joinToString("") { "data: $it\n\n" }.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val engine = CaesarCloudFreeEngine({ "https://backend.example" },
            { owner -> authenticatedOwner = owner; "session-token" }, { it == "session-token" }, client,
            buildType = "debug", testEndpoint = CAESAR_CLOUD_LOOPBACK_ENDPOINT)
        val events = engine.stream(request()).toList()
        assertEquals("owner", authenticatedOwner)
        assertEquals(1, calls)
        assertEquals("glm-4-flash-250414", (events.first() as AiEvent.Meta).execution.model)
        assertTrue(events.last() is AiEvent.Done)
    }

    @Test fun `HTTP and invalid variant overrides fail before transport`() = runTest {
        for ((buildType, base, endpoint) in listOf(
            Triple("debug", "http://127.0.0.1:18789", ""),
            Triple("release", "http://127.0.0.1:18789", ""),
            Triple("release", "https://backend.example", CAESAR_CLOUD_LOOPBACK_ENDPOINT),
            Triple("benchmark", "https://backend.example", CAESAR_CLOUD_LOOPBACK_ENDPOINT),
            Triple("debug", "https://backend.example", "http://127.0.0.1:18790/caesar-cloud/v1/chat"),
        )) {
            var called = false
            val http = OkHttpClient.Builder().addInterceptor { called = true; error("must not call") }.build()
            val engine = CaesarCloudFreeEngine({ base }, { "session-token" }, { true }, http,
                buildType = buildType, testEndpoint = endpoint)
            assertEquals("cloud_not_configured", (engine.stream(request()).toList().single() as AiEvent.Error).code)
            assertFalse(called)
        }
    }

    @Test fun `debug relay cannot bypass the changed-session guard`() = runTest {
        var called = false
        val http = OkHttpClient.Builder().addInterceptor { called = true; error("must not call") }.build()
        val engine = CaesarCloudFreeEngine({ "https://backend.example" }, { "old-session" }, { false }, http,
            buildType = "debug", testEndpoint = CAESAR_CLOUD_LOOPBACK_ENDPOINT)
        assertEquals("authentication_required", (engine.stream(request()).toList().single() as AiEvent.Error).code)
        assertFalse(called)
    }

    @Test fun `text and image payloads no longer send a fallback capability`() {
        assertEquals("fast", cloudFreePayload(request()).getString("mode"))
        assertFalse(cloudFreePayload(request()).has("allowTextFallback"))
        val file = File.createTempFile("cloud-free", ".jpg")
        try {
            file.writeBytes(byteArrayOf(-1, -40, 1, 2, -1, -39))
            val imageRequest = request().copy(
                messages = listOf(AiConversationMessage("user", "图形", attachmentPaths = listOf(file.absolutePath))),
                imagePaths = listOf(file.absolutePath), allowCloudFreeImageUpload = true,
            )
            assertFalse(cloudFreePayload(imageRequest).has("allowTextFallback"))
            assertEquals("fast", cloudFreePayload(imageRequest).getString("mode"))
        } finally { file.delete() }
    }

    @Test fun `unsupported deep requests stop before authentication or transport`() = runTest {
        var authenticated = false
        var called = false
        val client = OkHttpClient.Builder().addInterceptor { called = true; error("must not call") }.build()
        val engine = CaesarCloudFreeEngine({ "https://backend.example" },
            { authenticated = true; "session-token" }, { true }, client)
        val events = engine.stream(request().copy(mode = AiMode.DEEP)).toList()
        assertEquals("unsupported_mode", (events.single() as AiEvent.Error).code)
        assertFalse(authenticated)
        assertFalse(called)
    }

    @Test fun `text accepts only the direct free model and rejects retired or vision metadata`() = runTest {
        for ((model, accepted) in listOf(
            "glm-4-flash-250414" to true,
            "glm-4.7-flash" to false,
            "glm-4v-flash" to false,
            "glm-4-flash-250414x" to false,
        )) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val data = listOf(
                    """{"type":"meta","model":"$model","requestId":"direct-model-test"}""",
                    """{"type":"delta","text":"你好"}""",
                    """{"type":"done","elapsedMs":10}""",
                )
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Content-Type", "text/event-stream")
                    .body(data.joinToString("") { "data: $it\n\n" }.toResponseBody("text/event-stream".toMediaType())).build()
            }.build()
            val engine = CaesarCloudFreeEngine({ "https://backend.example" }, { "session-token" }, { true }, client)
            val events = engine.stream(request()).toList()
            assertEquals(accepted, events.last() is AiEvent.Done)
            if (accepted) assertEquals(model, (events.first() as AiEvent.Meta).execution.model)
            else assertTrue(events.first() is AiEvent.Error)
        }
    }

    @Test fun `current image still accepts only the vision model`() = runTest {
        val file = File.createTempFile("cloud-free-model", ".jpg")
        try {
            file.writeBytes(byteArrayOf(-1, -40, 1, 2, -1, -39))
            val input = request().copy(
                messages = listOf(AiConversationMessage("user", "图形", attachmentPaths = listOf(file.absolutePath))),
                imagePaths = listOf(file.absolutePath), allowCloudFreeImageUpload = true,
            )
            for (model in listOf("glm-4v-flash", "glm-4-flash-250414", "glm-4.7-flash")) {
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val events = listOf(
                        """{"type":"meta","model":"$model","requestId":"vision-test"}""",
                        """{"type":"delta","text":"图形"}""",
                        """{"type":"done","elapsedMs":10}""",
                    )
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .header("Content-Type", "text/event-stream")
                        .body(events.joinToString("") { "data: $it\n\n" }.toResponseBody("text/event-stream".toMediaType())).build()
                }.build()
                val engine = CaesarCloudFreeEngine({ "https://backend.example" }, { "session-token" }, { true }, client)
                val events = engine.stream(input).toList()
                assertEquals(model == "glm-4v-flash", events.last() is AiEvent.Done)
                if (model == "glm-4v-flash") assertEquals(model, (events.first() as AiEvent.Meta).execution.model)
                else assertTrue(events.single() is AiEvent.Error)
            }
        } finally { file.delete() }
    }
}
