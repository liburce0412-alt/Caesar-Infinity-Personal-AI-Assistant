package com.campusai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.campusai.core.agent.CaesarAgentEngine
import com.campusai.core.agent.CaesarAppTools
import com.campusai.core.agent.CaesarIdempotencyStore
import com.campusai.core.agent.CaesarMemoryStore
import com.campusai.core.ai.AiEngine
import com.campusai.core.ai.AiEvent
import com.campusai.core.ai.AiRequest
import com.campusai.core.database.CampusDatabase
import com.campusai.core.database.TimeRecordEntity
import com.campusai.core.health.HealthAvailability
import com.campusai.core.health.HealthGateway
import com.campusai.core.health.HealthPeriod
import com.campusai.core.health.HealthSnapshot
import com.campusai.core.model.AiConversationMessage
import com.campusai.core.model.AiMode
import com.campusai.core.profile.ProfileRepository
import com.campusai.features.ai.assembleCaesarTurnRequest
import com.campusai.features.community.CampusRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Script only the provider: writes, persisted deduplication and owner guards use real Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaesarTurnIdempotencyTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CampusDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = database.campusDao()
    private val registry = CaesarAppTools(dao, CampusRepository(), ProfileRepository(), NoHealth,
        CaesarIdempotencyStore(dao), CaesarMemoryStore(dao)).registry()
    private val provider = ToolProvider()
    private val engine = CaesarAgentEngine(provider, registry)
    private val history = mutableListOf<AiConversationMessage>()

    @After fun close() = database.close()

    @Test fun `same conversation create edit delete undo delete completes against real rows`() = runBlocking {
        val original = TimeRecordEntity(title = "原记录", category = "学习", startTime = 0,
            endTime = 60_000, durationMinutes = 1, remark = "", userId = "alice")
        val originalId = dao.insertTimeRecord(original).toInt()
        val foreignId = dao.insertTimeRecord(original.copy(userId = "bob")).toInt()
        val baseline = listOf(dao.timeRecordById(originalId), dao.timeRecordById(foreignId))

        val created = turn("添加时间记录", "time.create_record", createArgs())
        val id = created.getJSONObject("data").getInt("id")
        turn("修改时间记录", "time.edit_record", createArgs("改为三分钟", 180_000).put("id", id))
        assertEquals(3L, dao.timeRecordById(id)!!.durationMinutes)
        turn("删除记录", "time.delete_record", JSONObject().put("id", id))
        assertNotNull(dao.timeRecordById(id)!!.deletedAt)
        turn("撤销删除恢复记录", "time.undo_delete", JSONObject().put("id", id))
        assertNull(dao.timeRecordById(id)!!.deletedAt)
        turn("删除记录", "time.delete_record", JSONObject().put("id", id))
        assertNotNull("A fresh delete turn must not replay the first delete's cached success", dao.timeRecordById(id)!!.deletedAt)
        assertEquals(listOf(originalId), dao.getAllTimeRecordsFlow("alice", false).first().map { it.id })
        assertEquals(baseline, listOf(dao.timeRecordById(originalId), dao.timeRecordById(foreignId)))
    }

    @Test fun `same args explicit next turn creates twice while duplicate calls and retry create once per turn`() = runBlocking {
        val request = request("添加时间记录")
        provider.configure("time.create_record", createArgs(), repetitions = 2)
        assertSuccessful(engine.stream(request).toList())
        val first = provider.results.last().getJSONObject("data").getInt("id")
        assertEquals(2, provider.results.size)
        assertEquals(first, provider.results.first().getJSONObject("data").getInt("id"))
        assertEquals(1, dao.getAllTimeRecordsFlow("alice", false).first().size)

        provider.configure("time.create_record", createArgs())
        assertSuccessful(engine.stream(request).toList())
        assertEquals(first, provider.results.single().getJSONObject("data").getInt("id"))
        history += request.messages.last()
        history += AiConversationMessage("assistant", "已完成")
        val second = turn("添加时间记录", "time.create_record", createArgs()).getJSONObject("data").getInt("id")
        assertNotEquals(first, second)
        assertEquals(2, dao.getAllTimeRecordsFlow("alice", false).first().size)
    }

    @Test fun `editing back to an earlier value in a later turn really restores that value`() = runBlocking {
        val id = turn("添加时间记录", "time.create_record", createArgs()).getJSONObject("data").getInt("id")
        turn("修改时间记录", "time.edit_record", createArgs("版本A").put("id", id))
        turn("修改时间记录", "time.edit_record", createArgs("版本B").put("id", id))
        assertEquals("版本B", dao.timeRecordById(id)!!.title)
        turn("修改时间记录", "time.edit_record", createArgs("版本A").put("id", id))
        assertEquals("版本A", dao.timeRecordById(id)!!.title)
    }

    @Test fun `cancel after committed action and retry keep one row and owner namespaces remain independent`() = runBlocking {
        val request = request("添加时间记录")
        provider.configure("time.create_record", createArgs(), holdContinuation = true)
        val job = launch { engine.stream(request).toList() }
        withTimeout(5_000) { provider.continuationReached.await() }
        assertEquals(1, dao.getAllTimeRecordsFlow("alice", false).first().size)
        job.cancelAndJoin()
        engine.cancel()

        provider.configure("time.create_record", createArgs())
        assertSuccessful(engine.stream(request).toList())
        assertEquals(1, dao.getAllTimeRecordsFlow("alice", false).first().size)
        provider.configure("time.create_record", createArgs())
        assertSuccessful(engine.stream(request.copy(ownerUserId = "bob")).toList())
        assertEquals(1, dao.getAllTimeRecordsFlow("alice", false).first().size)
        assertEquals(1, dao.getAllTimeRecordsFlow("bob", false).first().size)
    }

    private suspend fun turn(prompt: String, name: String, arguments: JSONObject): JSONObject {
        val request = request(prompt)
        provider.configure(name, arguments)
        assertSuccessful(engine.stream(request).toList())
        history += request.messages.last()
        history += AiConversationMessage("assistant", "已完成")
        return provider.results.single()
    }

    private fun assertSuccessful(events: List<AiEvent>) {
        assertFalse(events.any { it is AiEvent.Error })
        assertFalse("Reversible time writes retain their existing direct-execution policy", events.any { it is AiEvent.Surface })
        assertTrue(events.filterIsInstance<AiEvent.ToolFinished>().all { it.success })
        assertTrue(events.last() is AiEvent.Done)
    }

    private fun request(prompt: String) = assembleCaesarTurnRequest(AiMode.FAST, history.toList(), prompt, prompt,
        JSONObject(), emptyList(), "one-conversation", "alice", "unused-local-model")

    private fun createArgs(title: String = "隔离测试记录", end: Long = 120_000) = JSONObject()
        .put("title", title).put("category", "学习").put("startTime", 0).put("endTime", end).put("remark", "")

    private class ToolProvider : AiEngine {
        private var name = ""
        private var arguments = "{}"
        private var repetitions = 1
        private var sent = 0
        private var holdContinuation = false
        var continuationReached = CompletableDeferred<Unit>()
            private set
        val results = mutableListOf<JSONObject>()

        fun configure(name: String, args: JSONObject, repetitions: Int = 1, holdContinuation: Boolean = false) {
            this.name = name
            arguments = args.toString()
            this.repetitions = repetitions
            this.holdContinuation = holdContinuation
            sent = 0
            results.clear()
            continuationReached = CompletableDeferred()
        }

        override fun stream(request: AiRequest) = flow {
            request.messages.lastOrNull()?.takeIf { it.role == "tool" }?.let {
                results += JSONObject(it.content)
                continuationReached.complete(Unit)
                if (holdContinuation) awaitCancellation()
            }
            if (sent++ < repetitions) emit(AiEvent.ToolCallRequested(name, arguments, "<tool_call>$name</tool_call>"))
            else {
                emit(AiEvent.Delta("已完成"))
                emit(AiEvent.Done(1))
            }
        }
        override fun cancel() = Unit
    }

    private object NoHealth : HealthGateway {
        override val readPermissions: Set<String> = emptySet()
        override fun availability(): HealthAvailability = HealthAvailability.Unsupported
        override suspend fun grantedPermissions(): Set<String> = emptySet()
        override suspend fun snapshot(period: HealthPeriod): Result<HealthSnapshot> = error("No health access in this fixture")
    }
}
