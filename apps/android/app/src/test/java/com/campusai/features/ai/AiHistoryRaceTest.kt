package com.campusai.features.ai

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.impl.WorkManagerImpl
import com.campusai.core.ai.AiEngine
import com.campusai.core.ai.AiEvent
import com.campusai.core.ai.AiRequest
import com.campusai.core.database.AgentMemoryEntity
import com.campusai.core.database.AiReportEntity
import com.campusai.core.database.CampusDao
import com.campusai.core.database.DailyGreetingEntity
import com.campusai.core.localai.LocalModelManager
import com.campusai.core.model.AiConversationMessage
import com.campusai.core.model.AiMode
import com.campusai.core.model.AiProvider
import com.campusai.core.model.AiReportSummary
import com.campusai.core.preferences.UserPreferencesRepository
import com.campusai.core.security.PersonalAiProviderStore
import com.campusai.core.security.ProviderSecretStorage
import com.campusai.core.time.CaesarTime
import java.lang.reflect.Proxy
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiHistoryRaceTest {
    @Test fun `external local conversation is consumed only after owner initialization and loading`() = runTest {
        val saved = report("automation-health-daily", "local_user", "旧问题", "已保存回复")
        reports[saved.id] = saved
        val vm = viewModel()
        var consumed = false
        vm.openConversationById(saved.id, "local_user") { consumed = true }
        vm.setActiveOwner("alice")
        runCurrent()
        assertFalse(consumed)
        vm.openConversationById(saved.id, "local_user") { consumed = true }
        assertFalse(consumed)
        runCurrent()
        assertTrue(consumed)
        assertEquals("已保存回复", vm.state.value.messages.last().content)
    }

    private val store = ViewModelStore()
    private var manager: LocalModelManager? = null
    private val reports = linkedMapOf<String, AiReportEntity>()
    private var intercept: (String, Array<out Any?>) -> Any? = { _, _ -> Unhandled }

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        // The nullable API handles both an isolated class and a shared test process.
        if (WorkManagerImpl.getInstance() == null) {
            WorkManagerImpl.initialize(context, Configuration.Builder().build())
        }
    }
    @After fun close() {
        store.clear()
        manager?.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(): AiViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val localModels = LocalModelManager(context).also { manager = it }
        val dao = Proxy.newProxyInstance(CampusDao::class.java.classLoader, arrayOf(CampusDao::class.java)) { _, method, args ->
            val values = args.orEmpty()
            val intercepted = intercept(method.name, values)
            if (intercepted !== Unhandled) intercepted else when (method.name) {
                "getAgentMemoriesFlow" -> flowOf(emptyList<AgentMemoryEntity>())
                "getAiReport" -> reports[values[0]]?.takeIf { it.ownerUserId == values[1] }
                "insertAiReport" -> { (values[0] as AiReportEntity).also { reports[it.id] = it }; Unit }
                "deleteAiReport" -> { reports[values[0]]?.takeIf { it.ownerUserId == values[1] }?.let { reports.remove(it.id) }; Unit }
                "getDailyGreeting" -> null
                else -> error("Unexpected DAO method ${method.name}")
            }
        } as CampusDao
        val secrets = PersonalAiProviderStore(object : ProviderSecretStorage {
            override fun read(key: String) = ""
            override fun write(key: String, value: String) = true
        })
        val noNetworkEngine = object : AiEngine {
            override fun stream(request: AiRequest): Flow<AiEvent> = error("This test must not request inference")
            override fun cancel() = Unit
        }
        return AiViewModel(dao, context, UserPreferencesRepository(context), localModels, noNetworkEngine, secrets,
            personalDeepSeekEngine = noNetworkEngine, personalGeminiEngine = noNetworkEngine, personalCodexEngine = noNetworkEngine,
        ).also { store.put("ai", it) }
    }

    @Test fun `selected empty health preserves the mixed draft and history until explicitly deselected`() = runTest {
        val vm = viewModel()
        val snapshot = AiContextSnapshot(userId = "alice")
        vm.setActiveOwner("alice")
        vm.setProvider(AiProvider.LOCAL)
        vm.setContextSelection(AiContextSelection(healthSummary = true))
        var draft = "总结健康并列出课程"
        vm.send(draft, snapshot) { draft = "" }
        vm.sendTask(CampusAiTask.TIME_PARSE, snapshot, draft) { draft = "" }
        runCurrent()
        assertEquals("总结健康并列出课程", draft)
        assertEquals("health_summary_unavailable", vm.state.value.errorCode)
        assertTrue(vm.state.value.messages.isEmpty())
        assertFalse(vm.state.value.streaming)
        assertTrue(reports.isEmpty())

        vm.setContextSelection(AiContextSelection(healthSummary = false))
        assertNull(vm.state.value.error)
        assertNull(vm.state.value.errorCode)
        var accepted = false
        vm.send("你是谁", snapshot) { accepted = true }
        runCurrent()
        assertTrue(accepted)
        val existing = vm.state.value.messages
        assertEquals(2, existing.size)
        vm.setContextSelection(AiContextSelection(healthSummary = true))
        vm.regenerateLastResponse(snapshot)
        runCurrent()
        assertEquals(existing, vm.state.value.messages)
        assertEquals("health_summary_unavailable", vm.state.value.errorCode)
    }

    @Test fun `late open cannot overwrite a deterministic turn and save captures its owner`() = runTest {
        val old = report("old", "alice", "旧问题", "旧回答")
        lateinit var pending: Continuation<AiReportEntity?>
        intercept = { name, args ->
            if (name == "getAiReport" && args[0] == old.id) {
                @Suppress("UNCHECKED_CAST")
                pending = args.last() as Continuation<AiReportEntity?>
                COROUTINE_SUSPENDED
            } else Unhandled
        }
        val vm = viewModel()
        vm.setActiveOwner("alice")
        vm.openConversationById(old.id, old.ownerUserId)
        runCurrent()
        vm.send("你是谁", AiContextSnapshot(userId = "alice"))
        pending.resume(old)
        runCurrent()
        assertEquals("你是谁", vm.state.value.messages.first().content)
        assertEquals("alice", reports.values.single().ownerUserId)
        assertFalse(reports.values.single().messagesJson.contains("旧问题"))
    }

    @Test fun `regenerate switches owner before planning old conversation`() = runTest {
        val old = report("old", "alice", "旧问题", "旧回答").also { reports[it.id] = it }
        val vm = viewModel()
        vm.setActiveOwner("alice")
        vm.openConversationById(old.id, "alice")
        runCurrent()
        assertEquals(2, vm.state.value.messages.size)
        vm.regenerateLastResponse(AiContextSnapshot(userId = "bob"))
        runCurrent()
        assertTrue(vm.state.value.messages.isEmpty())
        assertEquals(listOf(old), reports.values.toList())
    }

    @Test fun `delete completes before undo is offered and undo restores the captured legacy owner`() = runTest {
        val old = report("legacy", "local_user", "问题", "回答").also { reports[it.id] = it }
        lateinit var pending: Continuation<AiReportEntity?>
        var delayed = false
        intercept = { name, args ->
            if (name == "getAiReport" && !delayed) {
                delayed = true
                @Suppress("UNCHECKED_CAST")
                pending = args.last() as Continuation<AiReportEntity?>
                COROUTINE_SUSPENDED
            } else Unhandled
        }
        val vm = viewModel()
        vm.setActiveOwner("alice")
        val deletion = async(start = CoroutineStart.UNDISPATCHED) { vm.deleteConversation(summary(old)) }
        assertFalse(deletion.isCompleted)
        assertFalse(vm.undoDeleteConversation(old.id))
        pending.resume(old)
        runCurrent()
        assertTrue(deletion.await())
        assertFalse(reports.containsKey(old.id))
        assertTrue(vm.undoDeleteConversation(old.id))
        assertEquals(old, reports[old.id])
    }

    @Test fun `delete failure leaves report and gives no undo success`() = runTest {
        val old = report("old", "alice", "问题", "回答").also { reports[it.id] = it }
        intercept = { name, _ -> if (name == "deleteAiReport") error("disk failure") else Unhandled }
        val vm = viewModel()
        vm.setActiveOwner("alice")
        assertFalse(vm.deleteConversation(summary(old)))
        assertEquals("history_delete_failed", vm.state.value.errorCode)
        assertFalse(vm.undoDeleteConversation(old.id))
        assertEquals(old, reports[old.id])
    }

    @Test fun `old greeting completion cannot overwrite new account greeting`() = runTest {
        val date = LocalDate.now(CaesarTime.zone).toString()
        lateinit var pending: Continuation<DailyGreetingEntity?>
        val bobGreeting = DailyGreetingEntity("bob:$date", date, "先完成眼前这一小步", "LOCAL", 2)
        intercept = { name, args ->
            if (name != "getDailyGreeting") Unhandled
            else if (args[0] == "alice:$date") {
                @Suppress("UNCHECKED_CAST")
                pending = args.last() as Continuation<DailyGreetingEntity?>
                COROUTINE_SUSPENDED
            } else bobGreeting
        }
        val vm = viewModel()
        vm.setActiveOwner("alice")
        vm.ensureDailyGreeting(AiContextSnapshot(userId = "alice"))
        runCurrent()
        vm.setActiveOwner("bob")
        vm.ensureDailyGreeting(AiContextSnapshot(userId = "bob"))
        runCurrent()
        pending.resume(DailyGreetingEntity("alice:$date", date, "稳稳走好今天的每一步", "LOCAL", 1))
        runCurrent()
        assertEquals(bobGreeting.text, vm.dailyGreeting.value?.text)
    }

    @Test fun `an earlier save merge cannot replace messages from a newer turn`() = runTest {
        val reads = mutableListOf<Continuation<AiReportEntity?>>()
        var id = ""
        intercept = { name, args ->
            if (name == "getAiReport") {
                id = args[0] as String
                @Suppress("UNCHECKED_CAST")
                reads += args.last() as Continuation<AiReportEntity?>
                COROUTINE_SUSPENDED
            } else Unhandled
        }
        val vm = viewModel()
        vm.setActiveOwner("alice")
        vm.send("你是谁", AiContextSnapshot(userId = "alice"))
        vm.send("你叫什么", AiContextSnapshot(userId = "alice"))
        assertEquals(1, reads.size)
        reads[0].resume(report(id, "alice", "后台旧问题", "后台旧回答"))
        runCurrent()
        assertEquals(2, reads.size)
        // The second save is still blocked. The first save must not paint its stale merge.
        assertEquals("你叫什么", vm.state.value.messages.last { it.role == "user" }.content)
        reads[1].resume(reports[id])
        runCurrent()
        assertTrue(reports.getValue(id).messagesJson.contains("你叫什么"))
    }

    @Test fun `continuing legacy history forks into current owner without mutating source`() = runTest {
        val old = report("legacy", "local_user", "旧问题", "旧回答").also { reports[it.id] = it }
        val vm = viewModel()
        vm.setActiveOwner("alice")
        vm.openConversationById(old.id, "local_user")
        runCurrent()
        vm.send("你是谁", AiContextSnapshot(userId = "alice"))
        vm.state.first { state -> state.messages.any { it.role == "user" && it.content == "你是谁" } }
        runCurrent()
        assertEquals(old, reports[old.id])
        val fork = reports.values.single { it.id != old.id }
        assertEquals("alice", fork.ownerUserId)
        assertTrue(fork.messagesJson.contains("旧问题"))
        assertTrue(fork.messagesJson.contains("你是谁"))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `failed legacy image migration preserves report and source until reopening succeeds`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = "legacy-retry-${UUID.randomUUID()}"
        val source = File(context.cacheDir, "$id.jpg")
        FileOutputStream(source).use { output ->
            Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).also { bitmap ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                bitmap.recycle()
            }
        }
        val blockedDirectory = File(context.noBackupFilesDir, "ai-conversations/$id/attachments")
        assertTrue(blockedDirectory.parentFile!!.mkdirs())
        blockedDirectory.writeText("A file blocks the attachment directory")
        val original = report(id, "local_user", "旧图片", "旧回答").copy(messagesJson = AiConversationCodec.encode(listOf(
            AiConversationMessage("user", "旧图片", attachmentPaths = listOf(source.absolutePath)),
            AiConversationMessage("assistant", "旧回答"),
        )))
        reports[id] = original
        val vm = viewModel()
        try {
            vm.setActiveOwner("alice")
            vm.openConversationById(id, "local_user")
            vm.state.first { it.errorCode == "image_migration_failed" && !it.importingImage }
            assertEquals(original, reports[id])
            assertEquals(listOf(source.absolutePath), vm.state.value.messages.first().attachmentPaths)
            assertTrue(source.isFile)

            assertTrue(blockedDirectory.delete())
            vm.openConversationById(id, "local_user")
            vm.state.first { !it.importingImage && it.messages.firstOrNull()?.attachmentRefs?.isNotEmpty() == true }
            val migrated = reports.getValue(id)
            assertEquals("local_user", migrated.ownerUserId)
            assertEquals(original.createdAt, migrated.createdAt)
            assertEquals(original.updatedAt, migrated.updatedAt)
            assertTrue(AiConversationCodec.decode(migrated.messagesJson).first().attachmentRefs.isNotEmpty())
            assertFalse(migrated.messagesJson.contains(source.absolutePath))
            assertTrue(File(vm.state.value.messages.first().attachmentPaths.single()).isFile)
            assertTrue(source.isFile)
            assertNull(vm.state.value.errorCode)
        } finally {
            CaesarImageProcessor(context).deleteConversation(id)
            source.delete()
        }
    }

    @Test fun `memory replay clears synchronously and queued edits cannot cross account switch`() = runTest {
        fun memory(id: String, owner: String) = AgentMemoryEntity(id, "fact", "私有内容", "test", 1.0, 1, 2, null, owner)
        val alice = MutableStateFlow(listOf(memory("a", "alice")))
        val bob = MutableStateFlow(listOf(memory("b", "bob")))
        val local = MutableStateFlow(listOf(memory("legacy", "local_user")))
        val writes = mutableListOf<Pair<String, String>>()
        intercept = { name, args ->
            when (name) {
                "getAgentMemoriesFlow" -> when (args[0]) { "alice" -> alice; "bob" -> bob; else -> local }
                "updateAgentMemoryContent" -> { writes += (args[0] as String) to (args[2] as String); 1 }
                "deleteAgentMemory" -> { writes += (args[0] as String) to (args[1] as String); 1 }
                else -> Unhandled
            }
        }
        val vm = viewModel()
        vm.setActiveOwner("alice")
        runCurrent()
        assertEquals(listOf("a"), vm.memories.value.map { it.id })
        vm.updateMemory("a", "不应写入")
        vm.setActiveOwner("bob")
        assertTrue(vm.memories.value.isEmpty())
        alice.value = listOf(memory("late-a", "alice"))
        runCurrent()
        assertEquals(listOf("b"), vm.memories.value.map { it.id })
        assertTrue(writes.isEmpty())
        vm.showLocalMemories(true)
        assertTrue(vm.memories.value.isEmpty())
        runCurrent()
        assertEquals(listOf("legacy"), vm.memories.value.map { it.id })
        vm.forgetMemory("legacy")
        runCurrent()
        assertEquals(listOf("legacy" to "local_user"), writes)
    }

    private fun report(id: String, owner: String, question: String, answer: String) = AiReportEntity(
        id, "LOCAL", "FAST", "test", "LOCAL_DETERMINISTIC", "", "title", answer,
        AiConversationCodec.encode(listOf(AiConversationMessage("user", question), AiConversationMessage("assistant", answer))),
        1, 2, owner,
    )

    private fun summary(report: AiReportEntity) = AiReportSummary(
        report.id, AiProvider.LOCAL, AiMode.FAST, report.model, report.executionEngine, report.requestId,
        report.title, report.summary, report.createdAt, report.updatedAt, report.ownerUserId,
    )

    private object Unhandled
}
