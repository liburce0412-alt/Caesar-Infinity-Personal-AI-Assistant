package com.campusai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.campusai.core.agent.AutonomyMode
import com.campusai.core.agent.CaesarAppTools
import com.campusai.core.agent.CaesarAgentEngine
import com.campusai.core.agent.CaesarIdempotencyStore
import com.campusai.core.agent.CaesarIntentEvidence
import com.campusai.core.agent.CaesarMemoryStore
import com.campusai.core.agent.CaesarToolResult
import com.campusai.core.agent.ToolExecutionContext
import com.campusai.core.ai.AiEngine
import com.campusai.core.ai.AiEvent
import com.campusai.core.ai.AiRequest
import com.campusai.core.database.CampusDatabase
import com.campusai.core.database.CourseScheduleEntity
import com.campusai.core.database.TimeRecordEntity
import com.campusai.core.health.HealthAvailability
import com.campusai.core.health.HealthGateway
import com.campusai.core.health.HealthPeriod
import com.campusai.core.health.HealthSnapshot
import com.campusai.core.model.AiConversationMessage
import com.campusai.core.model.AiMode
import com.campusai.core.network.OpenAiProviderToolCall
import com.campusai.core.network.ProviderToolCallEnvelope
import com.campusai.core.network.cloudFreePayload
import com.campusai.core.profile.ProfileRepository
import com.campusai.features.community.CampusRepository
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaesarAppToolsOwnerBoundaryTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CampusDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = database.campusDao()
    private val idempotency = CaesarIdempotencyStore(dao)
    private val registry = CaesarAppTools(
        dao, CampusRepository(), ProfileRepository(), NoOpHealthGateway, idempotency, CaesarMemoryStore(dao),
    ).registry()

    @After fun close() = database.close()

    @Test fun `time list computes exact returned totals without foreign or deleted records`() = runBlocking {
        val expected = insertSummaryRecords()
        val foreignId = dao.insertTimeRecord(record("bob").copy(startTime = 9_000_000,
            endTime = 15_000_000, durationMinutes = 100)).toInt()
        val deletedId = dao.insertTimeRecord(record("alice").copy(startTime = 8_000_000,
            endTime = 14_000_000, durationMinutes = 100, deletedAt = 14_000_001)).toInt()
        val before = (expected.map { it.id } + foreignId + deletedId).map { dao.timeRecordById(it) }

        val result = execute("time.list_records", JSONObject().put("limit", 50), "alice") as CaesarToolResult.Success
        val json = JSONObject(result.contentJson)
        assertTrue(json.getBoolean("ok"))
        val data = json.getJSONArray("data")
        assertEquals(expected.map { it.id }, (0 until data.length()).map { data.getJSONObject(it).getInt("id") })
        assertEquals(listOf(90L, 25L, 50L, 85L), (0 until data.length()).map { data.getJSONObject(it).getLong("durationMinutes") })
        val summary = json.getJSONObject("summary")
        assertEquals("returned_records", summary.getString("scope"))
        assertEquals(4, summary.getInt("recordCount"))
        assertEquals(250L, summary.getLong("totalDurationMinutes"))
        assertEquals(before, before.map { dao.timeRecordById(it!!.id) })
    }

    @Test fun `limited time list totals only returned records and leaves source records intact`() = runBlocking {
        val before = insertSummaryRecords()
        val result = execute("time.list_records", JSONObject().put("limit", 2), "alice") as CaesarToolResult.Success
        val json = JSONObject(result.contentJson)
        val data = json.getJSONArray("data")
        assertEquals(before.take(2).map { it.id }, (0 until data.length()).map { data.getJSONObject(it).getInt("id") })
        assertEquals(listOf(90L, 25L), (0 until data.length()).map { data.getJSONObject(it).getLong("durationMinutes") })
        val summary = json.getJSONObject("summary")
        assertEquals("returned_records", summary.getString("scope"))
        assertEquals(2, summary.getInt("recordCount"))
        assertEquals(115L, summary.getLong("totalDurationMinutes"))
        assertEquals(before, dao.getAllTimeRecordsFlow("alice", false).first())
    }

    @Test fun `empty owner time list has zero totals and local legacy remains included`() = runBlocking {
        val foreignId = dao.insertTimeRecord(record("bob")).toInt()
        val empty = execute("time.list_records", JSONObject().put("limit", 50), "alice") as CaesarToolResult.Success
        val json = JSONObject(empty.contentJson)
        assertEquals(0, json.getJSONArray("data").length())
        val summary = json.getJSONObject("summary")
        assertEquals("returned_records", summary.getString("scope"))
        assertEquals(0, summary.getInt("recordCount"))
        assertEquals(0L, summary.getLong("totalDurationMinutes"))

        // Legacy stored duration is authoritative even if it differs from the timestamp interval.
        val localId = dao.insertTimeRecord(record("local_user").copy(durationMinutes = 7)).toInt()
        val localBefore = dao.timeRecordById(localId)
        val local = execute("time.list_records", JSONObject().put("limit", 50), "alice") as CaesarToolResult.Success
        val withLocal = JSONObject(local.contentJson)
        assertEquals(1, withLocal.getJSONArray("data").length())
        assertEquals(localId, withLocal.getJSONArray("data").getJSONObject(0).getInt("id"))
        assertEquals(1, withLocal.getJSONObject("summary").getInt("recordCount"))
        assertEquals(7L, withLocal.getJSONObject("summary").getLong("totalDurationMinutes"))
        assertEquals("bob", dao.timeRecordById(foreignId)!!.userId)
        assertEquals(localBefore, dao.timeRecordById(localId))
    }

    @Test fun `ordinary course result preserves array shape full rows and owner scope`() = runBlocking {
        val rows = (1..10).map { course("alice").copy(name = "课程$it", sourceHash = "course-$it", location = "教室$it", teacher = "老师$it") }
        dao.insertCourseSchedules(rows + course("local_user").copy(name = "本机旧课程"))
        dao.insertCourseSchedules(listOf(course("bob").copy(name = "foreign-course")))
        val result = execute("course.list", JSONObject(), "alice") as CaesarToolResult.Success
        val json = JSONObject(result.contentJson)
        val data = json.getJSONArray("data")
        assertEquals(11, data.length())
        assertEquals(11, json.getInt("dataTotalCount"))
        assertEquals(11, json.getInt("dataIncludedCount"))
        assertEquals(0, json.getInt("dataOmittedCount"))
        assertFalse(result.contentJson.contains("foreign-course"))
        assertTrue(result.contentJson.contains("本机旧课程"))
        for (index in 0 until data.length()) {
            val item = data.getJSONObject(index)
            val stored = dao.courseById(item.getInt("id"))!!
            assertEquals(stored.name, item.getString("name"))
            assertEquals(stored.location, item.getString("location"))
            assertEquals(stored.teacher, item.getString("teacher"))
            assertEquals(stored.weeks, item.getString("weeks"))
        }
    }

    @Test fun `large real course tool result survives free replay with complete bounded rows`() = runBlocking {
        val rows = (1..80).map { course("alice").copy(name = "完整课程名\\\"".repeat(20) + it,
            sourceHash = "course-$it", location = "教室".repeat(40), teacher = "老师", weeks = "第1至20周") }
        assertTrue(rows.sumOf { it.name.length + it.location.length } > 8_000)
        dao.insertCourseSchedules(rows)
        dao.insertCourseSchedules(listOf(course("bob").copy(name = "foreign-course")))
        var calls = 0
        var replay: JSONObject? = null
        val delegate = object : AiEngine {
            override fun stream(request: AiRequest) = flow {
                val payload = cloudFreePayload(request)
                if (calls++ == 0) {
                    val call = OpenAiProviderToolCall("course-call", "course_list", "course.list", "{}", null)
                    emit(AiEvent.ToolCallRequested("course.list", "{}", ProviderToolCallEnvelope.encode(call)))
                } else {
                    val messages = payload.getJSONArray("messages")
                    replay = JSONObject(messages.getJSONObject(messages.length() - 1).getString("content"))
                    emit(AiEvent.Delta("已读取部分课程，其他课程未在本次列出。"))
                    emit(AiEvent.Done(1))
                }
            }
            override fun cancel() = Unit
        }
        val events = CaesarAgentEngine(delegate, registry).stream(AiRequest(AiMode.FAST,
            listOf(AiConversationMessage("user", "查询课程")), sessionId = "session", ownerUserId = "alice",
            userPrompt = "查询课程", allowedToolNames = setOf("course.list"))).toList()
        assertEquals(2, calls)
        assertTrue(events.last() is AiEvent.Done)
        assertTrue(events.any { it is AiEvent.ToolFinished && it.name == "course.list" && it.success })
        val json = requireNotNull(replay)
        assertTrue(json.toString().length <= 8_000)
        val data = json.getJSONArray("data")
        assertTrue(data.length() in 1..79)
        assertEquals(80, json.getInt("dataTotalCount"))
        assertEquals(data.length(), json.getInt("dataIncludedCount"))
        assertEquals(80 - data.length(), json.getInt("dataOmittedCount"))
        assertFalse(json.toString().contains("foreign-course"))
        for (index in 0 until data.length()) {
            val item = data.getJSONObject(index)
            val stored = dao.courseById(item.getInt("id"))!!
            assertEquals(stored.name, item.getString("name"))
            assertEquals(stored.location, item.getString("location"))
            assertEquals(stored.weeks, item.getString("weeks"))
        }
        assertEquals(80, dao.getCourseSchedulesFlow("alice", false).first().size)
    }

    @Test fun `memory tools bind proposals reads and deletion to captured execution owner`() = runBlocking {
        val proposal = execute("memory.propose", JSONObject().put("type", "fact").put("content", "Alice private memory"), "alice") as CaesarToolResult.Success
        val id = JSONObject(proposal.contentJson).getString("proposalId")
        assertEquals("alice", dao.getAgentMemoriesFlow("alice").first().single().ownerUserId)
        assertTrue(dao.getAgentMemoriesFlow("bob").first().isEmpty())
        assertEquals(1, dao.confirmAgentMemory(id, ownerUserId = "alice"))
        val alice = execute("memory.list", JSONObject(), "alice") as CaesarToolResult.Success
        val bob = execute("memory.list", JSONObject(), "bob") as CaesarToolResult.Success
        assertTrue(alice.contentJson.contains("Alice private memory"))
        assertFalse(bob.contentJson.contains("Alice private memory"))
        assertDenied(execute("memory.forget", JSONObject().put("id", id), "bob", confirmed = true), "memory_unavailable")
        assertEquals(id, dao.getAgentMemoriesFlow("alice").first().single().id)
        assertTrue(execute("memory.forget", JSONObject().put("id", id), "alice", confirmed = true) is CaesarToolResult.Success)
        assertTrue(dao.getAgentMemoriesFlow("alice").first().isEmpty())
    }

    @Test fun `foreign account cannot edit delete or restore a time record`() = runBlocking {
        val id = dao.insertTimeRecord(record("alice")).toInt()
        val original = dao.timeRecordById(id)
        assertDenied(execute("time.edit_record", editArgs(id), "bob"), "record_unavailable")
        assertEquals(original, dao.timeRecordById(id))
        assertDenied(execute("time.delete_record", idArgs(id), "bob"), "record_unavailable")
        assertEquals(original, dao.timeRecordById(id))

        assertTrue(execute("time.delete_record", idArgs(id), "alice") is CaesarToolResult.Success)
        val tombstone = dao.timeRecordById(id)
        assertNotNull(tombstone!!.deletedAt)
        assertDenied(execute("time.undo_delete", idArgs(id), "bob"), "record_unavailable")
        assertEquals(tombstone, dao.timeRecordById(id))
        assertTrue(execute("time.undo_delete", idArgs(id), "alice") is CaesarToolResult.Success)
        assertNull(dao.timeRecordById(id)!!.deletedAt)
    }

    @Test fun `local legacy rows remain writable and missing rows never report success`() = runBlocking {
        val id = dao.insertTimeRecord(record("local_user")).toInt()
        assertTrue(execute("time.edit_record", editArgs(id), "bob") is CaesarToolResult.Success)
        assertEquals("修改后", dao.timeRecordById(id)!!.title)
        assertEquals("local_user", dao.timeRecordById(id)!!.userId)
        assertTrue(execute("time.delete_record", idArgs(id), "bob") is CaesarToolResult.Success)
        assertTrue(execute("time.undo_delete", idArgs(id), "bob") is CaesarToolResult.Success)
        assertNull(dao.timeRecordById(id)!!.deletedAt)
        assertDenied(execute("time.edit_record", editArgs(999), "bob"), "record_unavailable")
        assertDenied(execute("time.delete_record", idArgs(999), "bob"), "record_unavailable")
        assertDenied(execute("time.undo_delete", idArgs(999), "bob"), "record_unavailable")
    }

    @Test fun `course delete respects captured owner and permits local legacy courses`() = runBlocking {
        val alice = dao.insertCourseSchedules(listOf(course("alice"))).single().toInt()
        val local = dao.insertCourseSchedules(listOf(course("local_user"))).single().toInt()
        val original = dao.courseById(alice)
        assertDenied(execute("course.delete", idArgs(alice), "bob"), "course_unavailable")
        assertEquals(original, dao.courseById(alice))
        assertTrue(execute("course.delete", idArgs(alice), "alice") is CaesarToolResult.Success)
        assertNotNull(dao.courseById(alice)!!.deletedAt)
        assertTrue(execute("course.delete", idArgs(local), "bob") is CaesarToolResult.Success)
        assertNotNull(dao.courseById(local)!!.deletedAt)
        assertDenied(execute("course.delete", idArgs(999), "bob"), "course_unavailable")
    }

    @Test fun `owners have independent persisted actions while duplicate requests and legacy history survive`() = runBlocking {
        val args = createArgs()
        val name = "time.create_record"
        val canonical = CaesarIntentEvidence.canonicalArguments(args)
        val oldKey = MessageDigest.getInstance("SHA-256")
            .digest("session\u0000$name\u0000$canonical".toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
        val oldResult = "{\"legacyResult\":true}"
        assertTrue(idempotency.begin(oldKey, name, canonical))
        idempotency.complete(oldKey, name, canonical, oldResult)
        val oldAction = dao.getAgentAction(oldKey)

        val aliceKey = CaesarIntentEvidence.idempotencyKey("session", name, canonical, "alice")
        val bobKey = CaesarIntentEvidence.idempotencyKey("session", name, canonical, "bob")
        assertNotEquals(aliceKey, bobKey)
        assertNotEquals(oldKey, aliceKey)
        val alice = execute(name, args, "alice") as CaesarToolResult.Success
        val bob = execute(name, args, "bob") as CaesarToolResult.Success
        assertNotEquals(oldResult, alice.contentJson)
        assertNotEquals(alice.contentJson, bob.contentJson)
        assertEquals(alice, execute(name, args, "alice"))
        assertEquals(bob, execute(name, args, "bob"))
        assertEquals(1, dao.getAllTimeRecordsFlow("alice", false).first().size)
        assertEquals(1, dao.getAllTimeRecordsFlow("bob", false).first().size)
        assertEquals("completed", dao.getAgentAction(aliceKey)!!.status)
        assertEquals("completed", dao.getAgentAction(bobKey)!!.status)
        assertEquals(oldAction, dao.getAgentAction(oldKey))
    }

    @Test fun `owner scoped keys retain canonical argument and guest identity semantics`() {
        val first = """{"b":{"y":2,"x":1},"a":3}"""
        val reordered = """{"a":3,"b":{"x":1,"y":2}}"""
        assertEquals(
            CaesarIntentEvidence.idempotencyKey("session", "time.edit_record", first, "alice"),
            CaesarIntentEvidence.idempotencyKey("session", "time.edit_record", reordered, "alice"),
        )
        assertEquals(
            CaesarIntentEvidence.idempotencyKey("session", "time.edit_record", first, "local_user"),
            CaesarIntentEvidence.idempotencyKey("session", "time.edit_record", first, ""),
        )
    }

    private suspend fun execute(name: String, args: JSONObject, owner: String, confirmed: Boolean = false): CaesarToolResult = registry.execute(
        name, args, ToolExecutionContext(
            sessionId = "session",
            ownerUserId = owner,
            userPrompt = "修改时间记录或删除课程",
            autonomyMode = AutonomyMode.OWNER_DIRECT,
            explicitUserIntent = true,
            idempotencyKey = CaesarIntentEvidence.idempotencyKey("session", name, args.toString(), owner),
            confirmationGranted = confirmed,
        ),
    )

    private fun assertDenied(result: CaesarToolResult, code: String) {
        assertTrue("Expected denial, got $result", result is CaesarToolResult.Denied)
        assertEquals(code, (result as CaesarToolResult.Denied).code)
    }

    private fun idArgs(id: Int) = JSONObject().put("id", id)
    private suspend fun insertSummaryRecords(): List<TimeRecordEntity> = listOf(90L, 25L, 50L, 85L).mapIndexed { index, minutes ->
        val start = (4 - index) * 1_000_000L
        val row = record("alice").copy(startTime = start, endTime = start + minutes * 60_000, durationMinutes = minutes)
        row.copy(id = dao.insertTimeRecord(row).toInt())
    }

    private fun createArgs() = JSONObject().put("title", "修改后").put("category", "学习")
        .put("startTime", 0).put("endTime", 120_000).put("remark", "")
    private fun editArgs(id: Int) = createArgs().put("id", id)
    private fun record(owner: String) = TimeRecordEntity(title = "阅读", category = "学习", startTime = 0,
        endTime = 60_000, durationMinutes = 1, remark = "", userId = owner)
    private fun course(owner: String) = CourseScheduleEntity(name = "Java", weekday = 1,
        startMinute = 480, endMinute = 540, location = "", teacher = "", weeks = "1-16", sourceHash = owner, userId = owner)

    private object NoOpHealthGateway : HealthGateway {
        override val readPermissions: Set<String> = emptySet()
        override fun availability(): HealthAvailability = HealthAvailability.Unsupported
        override suspend fun grantedPermissions(): Set<String> = emptySet()
        override suspend fun snapshot(period: HealthPeriod): Result<HealthSnapshot> =
            Result.failure(IllegalStateException("Health is unused in owner boundary tests."))
    }
}
