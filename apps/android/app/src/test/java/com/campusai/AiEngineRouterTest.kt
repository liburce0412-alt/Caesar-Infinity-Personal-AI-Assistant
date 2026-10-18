package com.campusai

import com.campusai.core.ai.AiEngine
import com.campusai.core.ai.AiEngineRouter
import com.campusai.core.ai.AiEvent
import com.campusai.core.ai.AiRequest
import com.campusai.core.ai.AiRoute
import com.campusai.core.ai.AiRouteDecision
import com.campusai.core.ai.decideAiRoute
import com.campusai.core.model.AiConversationMessage
import com.campusai.core.model.AiMode
import com.campusai.core.model.AiProvider
import com.campusai.features.ai.selectedCloudProviderStatus
import com.campusai.core.model.LocalModelState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEngineRouterTest {
    @Test fun `free cloud needs network but no personal provider key`() {
        assertEquals(AiRouteDecision.Use(AiRoute.CAESAR_CLOUD_FREE),
            decideAiRoute(AiProvider.CAESAR_CLOUD_FREE, AiMode.FAST, true, false))
        assertEquals("cloud_offline", (decideAiRoute(AiProvider.CAESAR_CLOUD_FREE,
            AiMode.FAST, false, true) as AiRouteDecision.Block).code)
        assertEquals("unsupported_mode", (decideAiRoute(AiProvider.CAESAR_CLOUD_FREE,
            AiMode.DEEP, true, true) as AiRouteDecision.Block).code)
    }

    @Test fun `free cloud image capability never bypasses local content requirements`() = runTest {
        for (localOnly in listOf(false, true)) {
            val cloud = FakeEngine()
            val local = FakeEngine()
            val personal = FakeEngine()
            val router = AiEngineRouter(personal, local, { AiProvider.CAESAR_CLOUD_FREE },
                { false }, { true }, { LocalModelState.Ready }, caesarCloudFree = cloud)
            val path = "/private/current.jpg"
            router.stream(AiRequest(AiMode.FAST,
                listOf(AiConversationMessage("user", "看图", attachmentPaths = listOf(path))),
                imagePaths = listOf(path), allowCloudFreeImageUpload = true, requiresLocal = localOnly)).toList()
            assertEquals(if (localOnly) 0 else 1, cloud.calls)
            assertEquals(if (localOnly) 1 else 0, local.calls)
            assertEquals(0, personal.calls)
        }
    }

    @Test fun `free cloud image capability cannot unlock another provider`() = runTest {
        val cloud = FakeEngine()
        val local = FakeEngine()
        val personal = FakeEngine()
        val router = AiEngineRouter(personal, local, { AiProvider.DEEPSEEK },
            { true }, { true }, { LocalModelState.Ready }, caesarCloudFree = cloud)
        val path = "/private/current.jpg"
        router.stream(AiRequest(AiMode.FAST,
            listOf(AiConversationMessage("user", "看图", attachmentPaths = listOf(path))),
            imagePaths = listOf(path), allowCloudFreeImageUpload = true)).toList()
        assertEquals(1, local.calls)
        assertEquals(0, cloud.calls)
        assertEquals(0, personal.calls)
    }

    @Test
    fun `empty state labels the selected cloud provider without claiming a connection`() {
        assertEquals("DeepSeek", selectedCloudProviderStatus(AiProvider.DEEPSEEK))
        assertEquals("Gemini", selectedCloudProviderStatus(AiProvider.GOOGLE_GEMINI))
        assertEquals("Codex", selectedCloudProviderStatus(AiProvider.CODEX))
    }

    @Test fun `auto follows online and offline rules`() {
        assertEquals(AiRouteDecision.Use(AiRoute.LOCAL), decideAiRoute(AiProvider.AUTO, AiMode.FAST, true, true, true))
        val cloudConsent = decideAiRoute(AiProvider.AUTO, AiMode.FAST, true, false, true) as AiRouteDecision.Block
        assertEquals("local_model_not_ready", cloudConsent.code)
        assertTrue(cloudConsent.canUseCloudOnce)
        val geminiConsent = decideAiRoute(
            provider = AiProvider.AUTO,
            mode = AiMode.FAST,
            online = true,
            localReady = false,
            personalKeyAvailable = false,
            geminiKeyAvailable = true,
        ) as AiRouteDecision.Block
        assertTrue(geminiConsent.canUseCloudOnce)
        assertEquals(AiRouteDecision.Use(AiRoute.LOCAL), decideAiRoute(AiProvider.AUTO, AiMode.FAST, false, true))
        assertEquals("offline_model_missing", (decideAiRoute(AiProvider.AUTO, AiMode.FAST, false, false) as AiRouteDecision.Block).code)
        assertEquals(AiRouteDecision.Use(AiRoute.LOCAL), decideAiRoute(AiProvider.AUTO, AiMode.DEEP, false, true))
    }

    @Test fun `explicit providers never silently fall back`() {
        assertEquals("deepseek_offline", (decideAiRoute(AiProvider.DEEPSEEK, AiMode.FAST, false, true) as AiRouteDecision.Block).code)
        assertEquals("gemini_offline", (decideAiRoute(AiProvider.GOOGLE_GEMINI, AiMode.FAST, false, true) as AiRouteDecision.Block).code)
        assertEquals(AiRouteDecision.Use(AiRoute.LOCAL), decideAiRoute(AiProvider.LOCAL, AiMode.DEEP, true, true))
        val missing = decideAiRoute(AiProvider.LOCAL, AiMode.FAST, true, false) as AiRouteDecision.Block
        assertTrue(missing.canUseCloudOnce)
    }

    @Test fun `Gemini requires its own key and never borrows DeepSeek credentials`() {
        val missing = decideAiRoute(
            provider = AiProvider.GOOGLE_GEMINI,
            mode = AiMode.FAST,
            online = true,
            localReady = true,
            personalKeyAvailable = true,
            geminiKeyAvailable = false,
        ) as AiRouteDecision.Block
        assertEquals("gemini_key_missing", missing.code)
        assertEquals(
            AiRouteDecision.Use(AiRoute.PERSONAL_GOOGLE_GEMINI),
            decideAiRoute(
                provider = AiProvider.GOOGLE_GEMINI,
                mode = AiMode.DEEP,
                online = true,
                localReady = false,
                geminiKeyAvailable = true,
            ),
        )
    }

    @Test fun `explicit cloud route requires the users own key`() {
        val missing = decideAiRoute(AiProvider.DEEPSEEK, AiMode.FAST, true, true, false) as AiRouteDecision.Block
        assertEquals("personal_key_missing", missing.code)
        assertEquals(
            AiRouteDecision.Use(AiRoute.PERSONAL_DEEPSEEK),
            decideAiRoute(AiProvider.DEEPSEEK, AiMode.DEEP, true, false, true),
        )
    }

    @Test fun `Codex requires its own key and routes only when available`() {
        val missing = decideAiRoute(
            provider = AiProvider.CODEX,
            mode = AiMode.FAST,
            online = true,
            localReady = true,
            personalKeyAvailable = true,
            geminiKeyAvailable = true,
            codexKeyAvailable = false,
        ) as AiRouteDecision.Block

        assertEquals("codex_key_missing", missing.code)
        assertEquals(
            AiRouteDecision.Use(AiRoute.PERSONAL_CODEX),
            decideAiRoute(
                provider = AiProvider.CODEX,
                mode = AiMode.DEEP,
                online = true,
                localReady = false,
                personalKeyAvailable = false,
                geminiKeyAvailable = false,
                codexKeyAvailable = true,
            ),
        )
    }

    @Test fun `offline Codex explains every local connectivity prerequisite`() {
        val blocked = decideAiRoute(
            provider = AiProvider.CODEX,
            mode = AiMode.FAST,
            online = false,
            localReady = true,
            codexKeyAvailable = true,
        ) as AiRouteDecision.Block

        assertEquals("codex_offline", blocked.code)
        listOf("Tailscale", "电脑", "CPA", "Clash").forEach { prerequisite ->
            assertTrue(blocked.message.contains(prerequisite))
        }
    }

    @Test fun `local engine failure does not call cloud`() = runTest {
        val personalDeepSeek = FakeEngine()
        val local = FakeEngine(fail = true)
        val router = AiEngineRouter(
            personalDeepSeek = personalDeepSeek,
            local = local,
            provider = { AiProvider.LOCAL },
            personalKeyAvailable = { true },
            isOnline = { true },
            localState = { LocalModelState.Ready },
        )
        runCatching { router.stream(AiRequest(AiMode.FAST, listOf(AiConversationMessage("user", "test")))).toList() }
        assertEquals(0, personalDeepSeek.calls)
        assertEquals(1, local.calls)
    }

    @Test fun `missing personal key never calls any engine`() = runTest {
        val personalDeepSeek = FakeEngine()
        val local = FakeEngine()
        val router = AiEngineRouter(
            personalDeepSeek = personalDeepSeek,
            local = local,
            provider = { AiProvider.DEEPSEEK },
            personalKeyAvailable = { false },
            isOnline = { true },
            localState = { LocalModelState.Ready },
        )
        runCatching { router.stream(AiRequest(AiMode.FAST, listOf(AiConversationMessage("user", "test")))).toList() }
        assertEquals(0, personalDeepSeek.calls)
        assertEquals(0, local.calls)
    }

    @Test fun `explicit Gemini route calls only the Gemini engine`() = runTest {
        val deepSeek = FakeEngine()
        val gemini = FakeEngine()
        val local = FakeEngine()
        val router = AiEngineRouter(
            personalDeepSeek = deepSeek,
            local = local,
            provider = { AiProvider.GOOGLE_GEMINI },
            personalKeyAvailable = { true },
            isOnline = { true },
            localState = { LocalModelState.Ready },
            personalGoogleGemini = gemini,
            geminiKeyAvailable = { true },
        )

        router.stream(AiRequest(AiMode.FAST, listOf(AiConversationMessage("user", "test")))).toList()

        assertEquals(0, deepSeek.calls)
        assertEquals(1, gemini.calls)
        assertEquals(0, local.calls)
    }

    @Test fun `selected Codex routes an explicitly enabled image turn to Codex only`() = runTest {
        val deepSeek = FakeEngine()
        val codex = FakeEngine()
        val local = FakeEngine()
        val router = AiEngineRouter(
            personalDeepSeek = deepSeek,
            local = local,
            provider = { AiProvider.CODEX },
            personalKeyAvailable = { true },
            isOnline = { true },
            localState = { LocalModelState.NotDownloaded },
            personalCodex = codex,
            codexKeyAvailable = { true },
        )
        val path = "/data/user/0/com.campusai/no_backup/current.jpg"

        router.stream(
            AiRequest(
                mode = AiMode.FAST,
                messages = listOf(AiConversationMessage("user", "看图", attachmentPaths = listOf(path))),
                imagePaths = listOf(path),
                allowCodexImageUpload = true,
            ),
        ).toList()

        assertEquals(0, deepSeek.calls)
        assertEquals(1, codex.calls)
        assertEquals(0, local.calls)
    }

    @Test fun `image upload flag cannot unlock another cloud provider`() = runTest {
        val deepSeek = FakeEngine()
        val local = FakeEngine()
        val router = AiEngineRouter(
            personalDeepSeek = deepSeek,
            local = local,
            provider = { AiProvider.DEEPSEEK },
            personalKeyAvailable = { true },
            isOnline = { true },
            localState = { LocalModelState.Ready },
        )
        val path = "/data/user/0/com.campusai/no_backup/current.jpg"

        router.stream(
            AiRequest(
                mode = AiMode.FAST,
                messages = listOf(AiConversationMessage("user", "看图", attachmentPaths = listOf(path))),
                imagePaths = listOf(path),
                allowCodexImageUpload = true,
            ),
        ).toList()

        assertEquals(0, deepSeek.calls)
        assertEquals(1, local.calls)
    }

    @Test fun `locked unavailable quality model never falls back to ready fast model`() = runTest {
        val personalDeepSeek = FakeEngine()
        val local = FakeEngine()
        val requestedIds = mutableListOf<String>()
        val router = AiEngineRouter(
            personalDeepSeek = personalDeepSeek,
            local = local,
            provider = { AiProvider.LOCAL },
            personalKeyAvailable = { true },
            isOnline = { true },
            localState = { modelId ->
                requestedIds += modelId
                if (modelId == "qwen3.5-2b-mnn") LocalModelState.Ready else LocalModelState.Downloading(.5f, 1, 2)
            },
        )

        val failure = runCatching {
            router.stream(
                AiRequest(
                    AiMode.FAST,
                    listOf(AiConversationMessage("user", "test")),
                    requiresLocal = true,
                    localModelId = "qwen3.5-4b-mnn",
                ),
            ).toList()
        }.exceptionOrNull()

        assertTrue(failure?.message?.contains("锁定的本地模型") == true)
        assertEquals(listOf("qwen3.5-4b-mnn"), requestedIds)
        assertEquals(0, local.calls)
        assertEquals(0, personalDeepSeek.calls)
    }

    @Test fun `locked ready fast model routes locally`() = runTest {
        val local = FakeEngine()
        val router = AiEngineRouter(
            personalDeepSeek = FakeEngine(),
            local = local,
            provider = { AiProvider.LOCAL },
            personalKeyAvailable = { false },
            isOnline = { false },
            localState = { modelId -> if (modelId == "qwen3.5-2b-mnn") LocalModelState.Ready else LocalModelState.NotDownloaded },
        )

        router.stream(
            AiRequest(
                AiMode.FAST,
                listOf(AiConversationMessage("user", "test")),
                requiresLocal = true,
                localModelId = "qwen3.5-2b-mnn",
            ),
        ).toList()

        assertEquals(1, local.calls)
    }

    @Test fun `cloud once rejects image ownership metadata before selecting an engine`() = runTest {
        val deepSeek = FakeEngine()
        val router = AiEngineRouter(
            personalDeepSeek = deepSeek,
            local = FakeEngine(),
            provider = { AiProvider.AUTO },
            personalKeyAvailable = { true },
            isOnline = { true },
            localState = { LocalModelState.NotDownloaded },
        )
        val request = AiRequest(
            mode = AiMode.FAST,
            messages = listOf(
                AiConversationMessage(
                    role = "user",
                    content = "看图",
                    attachmentPaths = listOf("/data/user/0/com.campusai/no_backup/private.jpg"),
                ),
            ),
            imagePaths = listOf("/data/user/0/com.campusai/no_backup/private.jpg"),
        )

        val failure = runCatching { router.streamCloudOnce(request, AiProvider.DEEPSEEK).toList() }.exceptionOrNull()

        assertTrue(failure is com.campusai.core.ai.AiRoutingException)
        assertFalse(failure?.message.orEmpty().contains("private.jpg"))
        assertEquals(0, deepSeek.calls)
    }

    @Test fun `older flow completion does not clear a newer route using the same engine`() = runTest {
        val local = ControlledEngine()
        val router = AiEngineRouter(
            personalDeepSeek = FakeEngine(),
            local = local,
            provider = { AiProvider.LOCAL },
            personalKeyAvailable = { false },
            isOnline = { false },
            localState = { LocalModelState.Ready },
        )
        val request = AiRequest(AiMode.FAST, listOf(AiConversationMessage("user", "test")), localModelId = "qwen3.5-2b-mnn")

        val first = launch { router.stream(request).toList() }
        assertEquals(0, local.started.receive())
        val second = launch { router.stream(request).toList() }
        assertEquals(1, local.started.receive())

        local.complete(0)
        first.join()
        router.cancel()

        assertEquals(1, local.cancelCalls)
        local.complete(1)
        second.join()
    }

    private class FakeEngine(private val fail: Boolean = false) : AiEngine {
        var calls = 0
        override fun stream(request: AiRequest): Flow<AiEvent> = flow {
            calls++
            if (fail) error("local failed")
            emit(AiEvent.Done(1))
        }
        override fun cancel() = Unit
    }

    private class ControlledEngine : AiEngine {
        private val nextCall = AtomicInteger(0)
        private val completions = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
        val started = Channel<Int>(Channel.UNLIMITED)
        var cancelCalls = 0

        override fun stream(request: AiRequest): Flow<AiEvent> = flow {
            val call = nextCall.getAndIncrement()
            val completion = CompletableDeferred<Unit>()
            completions[call] = completion
            started.send(call)
            completion.await()
            emit(AiEvent.Done(1))
        }

        fun complete(call: Int) {
            completions.getValue(call).complete(Unit)
        }

        override fun cancel() {
            cancelCalls++
        }
    }
}
