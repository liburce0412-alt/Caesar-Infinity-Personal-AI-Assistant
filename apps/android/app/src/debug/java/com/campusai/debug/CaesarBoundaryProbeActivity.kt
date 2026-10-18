package com.campusai.debug

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Build
import android.util.AtomicFile
import androidx.room.Room
import androidx.work.WorkManager
import com.campusai.core.agent.*
import com.campusai.core.ai.*
import com.campusai.core.database.AiReportEntity
import com.campusai.core.database.CampusDao
import com.campusai.core.database.CampusDatabase
import com.campusai.core.health.*
import com.campusai.core.model.AiConversationMessage
import com.campusai.core.model.AiMode
import com.campusai.core.network.cloudFreePayload
import com.campusai.core.profile.ProfileRepository
import com.campusai.core.time.AiClock
import com.campusai.core.time.CaesarTime
import com.campusai.features.ai.*
import com.campusai.features.community.CampusRepository
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

/** Debug shell-only, separate process. Synthetic owners are not real authentication switches. */
class CaesarBoundaryProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runId = intent.getStringExtra("run_id").orEmpty()
        if (intent.action != ACTION || !intent.getBooleanExtra("explicit_opt_in", false) ||
            !runId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
            finish()
            return
        }
        val fixtureRoot = File(cacheDir, "caesar-boundary-$runId").canonicalFile
        val reportFile = File(filesDir, "caesar-boundary-probe/$runId.json")
        if (fixtureRoot.exists() || reportFile.exists()) {
            finish()
            return
        }
        scope.launch {
            val checks = JSONArray()
            try {
                check(Build.VERSION.SDK_INT >= 28 && Application.getProcessName().endsWith(":caesar_boundary_probe"))
                check(!WorkManager.isInitialized()) { "Unexpected default WorkManager initialization" }
                check(fixtureRoot.parentFile == cacheDir.canonicalFile && fixtureRoot.mkdirs())
                val isolated = FixtureContext(this@CaesarBoundaryProbeActivity, fixtureRoot)
                withContext(Dispatchers.IO) {
                    suspend fun verify(name: String, block: suspend () -> Unit) {
                        try {
                            withTimeout(20_000) { block() }
                            checks.put(JSONObject().put("name", name).put("passed", true))
                        } catch (cancelled: CancellationException) {
                            if (cancelled !is TimeoutCancellationException) throw cancelled
                            checks.put(JSONObject().put("name", name).put("passed", false).put("errorType", "timeout"))
                        } catch (error: Throwable) {
                            checks.put(JSONObject().put("name", name).put("passed", false).put("errorType", error.javaClass.simpleName))
                        }
                    }
                    verify("room_history_paging_owner_and_full_body") { history(isolated) }
                    verify("legacy_image_copy_delete_restore_and_retry") { images(isolated) }
                    verify("time_write_lifecycle_and_turn_idempotency") { timeWrites(isolated) }
                    verify("confirmation_cancel_and_owner_denial") { confirmation(isolated) }
                    verify("cloud_health_and_memory_payload_boundary") { cloudBoundary() }
                }
            } catch (error: Throwable) {
                checks.put(JSONObject().put("name", "isolation_setup").put("passed", false).put("errorType", error.javaClass.simpleName))
            } finally {
                // Only this run's canonical, direct cache child is disposable.
                if (fixtureRoot.parentFile == cacheDir.canonicalFile) fixtureRoot.deleteRecursively()
                val cleanupSucceeded = !fixtureRoot.exists()
                val report = JSONObject().put("schema", "caesar.boundary.probe.v1").put("runId", runId)
                    .put("syntheticDataOnly", true).put("realAuthenticationSwitch", false)
                    .put("database", "room_in_memory").put("networkRequests", 0)
                    .put("fixtureCleanupSucceeded", cleanupSucceeded).put("checks", checks)
                    .put("passed", checks.length() == 5 && cleanupSucceeded &&
                        (0 until checks.length()).all { checks.getJSONObject(it).getBoolean("passed") })
                reportFile.parentFile!!.mkdirs()
                // The transport waits for the final filename, which must never expose partial JSON.
                val atomicReport = AtomicFile(reportFile)
                val output = atomicReport.startWrite()
                try {
                    output.write(report.toString(2).toByteArray(Charsets.UTF_8))
                    atomicReport.finishWrite(output)
                } catch (error: Throwable) {
                    atomicReport.failWrite(output)
                    throw error
                }
                finish()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun history(context: Context) {
        val queries = CopyOnWriteArrayList<String>()
        val database = Room.inMemoryDatabaseBuilder(context, CampusDatabase::class.java)
            .setQueryCallback({ sql, _ -> queries += sql }, { command -> command.run() }).build()
        val observers = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val dao = database.campusDao()
            val body = AiConversationCodec.encode(listOf(AiConversationMessage("user", "完整正文".repeat(16_384))))
            for (index in 1..85) dao.insertAiReport(report("alice-${index.toString().padStart(3, '0')}", "alice", body))
            dao.insertAiReport(report("bob-only", "bob", "[]"))
            dao.insertAiReport(report("legacy-only", "local_user", "[]"))
            queries.clear()
            val first = dao.observeAiReportSummaries("alice", 40).first()
            val second = dao.observeAiReportSummaries("alice", 80).first()
            val all = dao.observeAiReportSummaries("alice", 120).first()
            check(first.size == 40 && second.size == 80 && all.size == 85)
            check(second.take(40) == first && all.take(80) == second && all.map { it.id }.distinct().size == 85)
            val selects = queries.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) && it.contains("ai_reports") }
            check(selects.isNotEmpty() && selects.none { it.contains("messagesJson", ignoreCase = true) || it.contains("SELECT *", ignoreCase = true) })
            check(dao.getAiReport(first.first().id, "bob") == null)
            val full = checkNotNull(dao.getAiReport(first.first().id, "alice"))
            check(full.messagesJson == body)
            dao.deleteAiReport(full.id, "bob")
            check(dao.getAiReport(full.id, "alice") == full)
            dao.deleteAiReport(full.id, "alice")
            check(dao.getAiReport(full.id, "alice") == null)
            dao.insertAiReport(full)

            val controller = AiHistoryController(dao, observers)
            observers.launch { controller.history.collect() }
            withContext(Dispatchers.Main.immediate) { controller.selectOwner("alice") }
            withTimeout(5_000) { controller.history.first { it.size == 41 } }
            withContext(Dispatchers.Main.immediate) { controller.loadMore() }
            withTimeout(5_000) { controller.history.first { it.size == 81 } }
            withContext(Dispatchers.Main.immediate) {
                controller.selectOwner("bob")
                check(controller.history.value.isEmpty() && !controller.viewingLocal.value)
            }
            check(withTimeout(5_000) { controller.history.first { it.isNotEmpty() } }.single().id == "bob-only")
            withContext(Dispatchers.Main.immediate) { controller.selectOwner("local_user") }
            check(withTimeout(5_000) { controller.history.first { it.isNotEmpty() } }.single().id == "legacy-only")
        } finally {
            observers.coroutineContext[Job]?.cancelAndJoin()
            database.close()
        }
    }

    private suspend fun images(context: Context) {
        val source = File(context.cacheDir, "legacy.jpg")
        source.parentFile!!.mkdirs()
        source.outputStream().use { stream ->
            val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
            try { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)) } finally { bitmap.recycle() }
        }
        val processor = CaesarImageProcessor(context)
        val old = listOf(AiConversationMessage("user", "合成旧图片", attachmentPaths = listOf(source.path), missingAttachmentCount = 1))
        val migrated = processor.migrateLegacy("legacy-fixture", old)
        check(source.isFile && migrated.single().attachmentRefs.size == 1)
        val encoded = AiConversationCodec.encode(migrated)
        check(!encoded.contains(source.path))
        val reopened = processor.hydrate(AiConversationCodec.decode(encoded))
        val (fork, _) = processor.copyForConversation("alice-fixture", reopened, emptyList())
        check(fork.single().attachmentRefs.single().relativePath != reopened.single().attachmentRefs.single().relativePath)
        // Removing metadata/undo must leave images usable. Final source cleanup cannot damage the fork.
        val database = Room.inMemoryDatabaseBuilder(context, CampusDatabase::class.java).build()
        try {
            val original = report("legacy-fixture", "local_user", encoded)
            database.campusDao().insertAiReport(original)
            database.campusDao().deleteAiReport(original.id, "local_user")
            check(File(reopened.single().attachmentPaths.single()).isFile)
            database.campusDao().insertAiReport(original)
            check(processor.hydrate(AiConversationCodec.decode(database.campusDao().getAiReport(original.id)!!.messagesJson)).single().attachmentPaths.size == 1)
        } finally { database.close() }
        processor.deleteConversation("legacy-fixture")
        check(File(fork.single().attachmentPaths.single()).isFile)
        check(processor.migrateLegacy("alice-fixture", fork).single().missingAttachmentCount == 1)
        val broken = File(context.cacheDir, "retry.jpg").apply { writeText("invalid fixture") }
        val retry = listOf(AiConversationMessage("user", "合成失败重试", attachmentPaths = listOf(broken.path)))
        check(runCatching { processor.migrateLegacy("retry-fixture", retry) }.isFailure && broken.isFile)
        source.copyTo(broken, overwrite = true)
        check(processor.migrateLegacy("retry-fixture", retry).single().attachmentRefs.size == 1)
    }

    private suspend fun timeWrites(context: Context) {
        val database = Room.inMemoryDatabaseBuilder(context, CampusDatabase::class.java).build()
        try {
            val dao = database.campusDao()
            val provider = ProbeProvider()
            val engine = CaesarAgentEngine(provider, registry(dao))
            val history = mutableListOf<AiConversationMessage>()
            suspend fun call(prompt: String, name: String, args: JSONObject, advance: Boolean = true): JSONObject {
                provider.configure(name, args)
                val request = assembleCaesarTurnRequest(AiMode.FAST, history.toList(), prompt, prompt,
                    JSONObject(), emptyList(), "fixture-session", "alice", "unused")
                val events = engine.stream(request).toList()
                check(events.none { it is AiEvent.Error || it is AiEvent.Surface })
                check(events.filterIsInstance<AiEvent.ToolFinished>().single().success)
                if (advance) { history += request.messages.last(); history += AiConversationMessage("assistant", "完成") }
                return checkNotNull(provider.result)
            }
            val args = JSONObject().put("title", "合成测试").put("category", "学习").put("startTime", 0).put("endTime", 60_000).put("remark", "")
            val first = call("添加时间记录", "time.create_record", args, advance = false).getJSONObject("data").getInt("id")
            check(call("添加时间记录", "time.create_record", args).getJSONObject("data").getInt("id") == first)
            val second = call("添加时间记录", "time.create_record", args).getJSONObject("data").getInt("id")
            check(first != second && dao.getAllTimeRecordsFlow("alice", false).first().size == 2)
            val beforeForeign = dao.timeRecordById(first)
            val denied = registry(dao).execute("time.delete_record", JSONObject().put("id", first), executionContext("bob", "foreign-delete"))
            check(denied is CaesarToolResult.Denied && dao.timeRecordById(first) == beforeForeign)
            call("修改时间记录", "time.edit_record", JSONObject(args.toString()).put("id", first).put("endTime", 120_000))
            check(dao.timeRecordById(first)!!.durationMinutes == 2L)
            call("删除记录", "time.delete_record", JSONObject().put("id", first))
            check(dao.timeRecordById(first)!!.deletedAt != null)
            call("撤销删除恢复记录", "time.undo_delete", JSONObject().put("id", first))
            check(dao.timeRecordById(first)!!.deletedAt == null)
            call("删除记录", "time.delete_record", JSONObject().put("id", first))
            check(dao.timeRecordById(first)!!.deletedAt != null)
            call("删除记录", "time.delete_record", JSONObject().put("id", second))
            check(dao.getAllTimeRecordsFlow("alice", false).first().isEmpty())
        } finally { database.close() }
    }

    private suspend fun confirmation(context: Context) {
        val database = Room.inMemoryDatabaseBuilder(context, CampusDatabase::class.java).build()
        try {
            val dao = database.campusDao()
            val memory = CaesarMemoryStore(dao)
            val proposal = memory.propose("fact", "合成隔离记忆", "fixture", 1.0, ownerUserId = "alice")
            check(!memory.confirm(proposal.id, "bob") && memory.confirm(proposal.id, "alice"))
            val tools = registry(dao)
            val args = JSONObject().put("id", proposal.id)
            check(tools.execute("memory.forget", args, executionContext("alice", "read-only").copy(autonomyMode = AutonomyMode.READ_ONLY)) is CaesarToolResult.Denied)
            val provider = ProbeProvider()
            val engine = CaesarAgentEngine(provider, tools)
            val request = AiRequest(AiMode.FAST, listOf(AiConversationMessage("user", "删除记忆")),
                userPrompt = "删除记忆", ownerUserId = "alice", sessionId = "confirmation-fixture", requiresLocal = true,
                allowedToolNames = setOf("memory.forget"))
            suspend fun propose(owner: String): String {
                provider.configure("memory.forget", args)
                val events = engine.stream(request.copy(ownerUserId = owner)).toList()
                val surface = CaesarSurface.fromJson(events.filterIsInstance<AiEvent.Surface>().single().json)!!
                return surface.components.filterIsInstance<CaesarComponent.Button>().single().actionId
            }
            val cancelled = propose("alice")
            check(memory.context("alice").size == 1)
            engine.cancel()
            check(engine.confirm(cancelled).toList().filterIsInstance<AiEvent.Error>().single().code == "confirmation_expired")
            val bob = propose("bob")
            check(engine.confirm(bob).toList().filterIsInstance<AiEvent.Error>().single().code == "memory_unavailable")
            check(memory.context("alice").size == 1 && memory.context("bob").isEmpty())
            val alice = propose("alice")
            check(engine.confirm(alice).toList().filterIsInstance<AiEvent.ToolFinished>().single().success)
            check(memory.context("alice").isEmpty())
            check(engine.confirm(alice).toList().filterIsInstance<AiEvent.Error>().single().code == "confirmation_expired")
        } finally { database.close() }
    }

    private fun cloudBoundary() {
        val request = AiRequest(AiMode.FAST, listOf(
            AiConversationMessage("user", "SYNTHETIC_PRIVATE_HISTORY", cloudHealthSensitive = true),
            AiConversationMessage("assistant", "SYNTHETIC_PRIVATE_REPLY", cloudHealthSensitive = true),
            AiConversationMessage("user", "你好")),
            structuredContextJson = JSONObject().put("clock", AiClock.context())
                .put("confirmedMemories", JSONArray(listOf("SYNTHETIC_PRIVATE_MEMORY")))
                .put("health", JSONObject().put("source", "SYNTHETIC_RAW_SOURCE").put("samples", listOf(1, 2)))
                .toString())
        val excluded = cloudFreePayload(request).toString()
        check(!excluded.contains("SYNTHETIC_") && !excluded.contains("consentedDailyHealthSummary"))
        val included = cloudFreePayload(request.copy(cloudHealthDisclosure = CloudHealthDisclosure.Included(
            CloudDailyHealthSummary(CaesarTime.today().toString(), steps = 42))))
        check(included.getJSONObject("context").getJSONObject("consentedDailyHealthSummary").getLong("steps") == 42L)
        check(!included.toString().contains("SYNTHETIC_"))
        check(!cloudFreePayload(request).toString().contains("consentedDailyHealthSummary"))
    }

    private fun registry(dao: CampusDao) = CaesarAppTools(dao, CampusRepository(), ProfileRepository(), NoHealth,
        CaesarIdempotencyStore(dao), CaesarMemoryStore(dao)).registry()

    private fun executionContext(owner: String, key: String) = ToolExecutionContext("fixture-session", owner, "删除记忆或删除记录",
        AutonomyMode.OWNER_DIRECT, explicitUserIntent = true, idempotencyKey = key)

    private fun report(id: String, owner: String, body: String) = AiReportEntity(id, "LOCAL", "FAST", "fixture",
        executionEngine = "LOCAL_DETERMINISTIC", requestId = "fixture-request", title = "合成历史", summary = "合成摘要",
        messagesJson = body, createdAt = 1, updatedAt = 2, ownerUserId = owner)

    private class FixtureContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = File(root, "no_backup").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = error("Persistent databases are forbidden in this probe")
        override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences =
            error("Preferences are forbidden in this probe")
    }

    private class ProbeProvider : AiEngine {
        private var name = ""
        private var args = "{}"
        var result: JSONObject? = null
            private set
        fun configure(name: String, args: JSONObject) { this.name = name; this.args = args.toString(); result = null }
        override fun stream(request: AiRequest) = flow {
            if (request.messages.lastOrNull()?.role == "tool") {
                result = JSONObject(request.messages.last().content)
                emit(AiEvent.Done(1))
            } else emit(AiEvent.ToolCallRequested(name, args, "<tool_call>$name</tool_call>"))
        }
        override fun cancel() = Unit
    }

    private object NoHealth : HealthGateway {
        override val readPermissions: Set<String> = emptySet()
        override fun availability(): HealthAvailability = error("No device health access")
        override suspend fun grantedPermissions(): Set<String> = error("No device health access")
        override suspend fun snapshot(period: HealthPeriod): Result<HealthSnapshot> = error("No device health access")
    }

    companion object { const val ACTION = "com.campusai.debug.RUN_BOUNDARY_PROBE" }
}
