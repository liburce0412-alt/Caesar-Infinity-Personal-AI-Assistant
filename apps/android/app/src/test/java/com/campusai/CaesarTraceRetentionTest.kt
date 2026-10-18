package com.campusai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.campusai.core.agent.CaesarTraceEvent
import com.campusai.core.agent.RoomCaesarTraceSink
import com.campusai.core.database.AgentTraceEntity
import com.campusai.core.database.AiReportEntity
import com.campusai.core.database.CampusDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CaesarTraceRetentionTest {
    private lateinit var database: CampusDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), CampusDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test fun `concurrent diagnostic writes retain only 200 total and keep account reads isolated`() = runTest {
        val dao = database.campusDao()
        coroutineScope {
            (0..219).map { index -> async { dao.insertAgentTrace(trace(index)) } }.awaitAll()
        }
        assertEquals(200, countTraces())
        val alice = dao.getAgentTraces(ownerUserId = "alice")
        val bob = dao.getAgentTraces(ownerUserId = "bob")
        assertEquals(100, alice.size)
        assertEquals(100, bob.size)
        assertTrue(alice.all { it.ownerUserId == "alice" && it.createdAt >= 20 })
        assertTrue(bob.all { it.ownerUserId == "bob" && it.createdAt >= 20 })
        dao.deleteAllAgentTraces("alice")
        assertEquals(100, countTraces())
        assertEquals(bob, dao.getAgentTraces(ownerUserId = "bob"))
    }

    @Test fun `trace sink captures event owner and never trims a user conversation`() = runTest {
        val dao = database.campusDao()
        val report = AiReportEntity(
            "conversation", "LOCAL", "FAST", "Qwen", "LOCAL_MNN", "request", "title", "summary",
            "[{\"content\":\"preserve\"}]", 1, 2, "alice",
        )
        dao.insertAiReport(report)
        repeat(200) { dao.insertAgentTrace(trace(it)) }
        RoomCaesarTraceSink(dao).record(CaesarTraceEvent(
            sessionId = "session", kind = "tool", name = "time.list", durationMs = 1,
            success = true, ownerUserId = "captured-account",
        ))
        assertEquals(200, countTraces())
        assertEquals("session", dao.getAgentTraces(ownerUserId = "captured-account").single().sessionId)
        assertEquals(report, dao.getAiReport(report.id, "alice"))
    }

    @Test fun `a pruning failure rolls back the diagnostic insertion`() = runTest {
        val dao = database.campusDao()
        repeat(200) { dao.insertAgentTrace(trace(it)) }
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_trace_prune BEFORE DELETE ON agent_traces
            BEGIN SELECT RAISE(ABORT, 'test pruning failure'); END
        """.trimIndent())
        assertTrue(runCatching { dao.insertAgentTrace(trace(200)) }.isFailure)
        assertEquals(200, countTraces())
        assertFalse(dao.getAgentTraces(ownerUserId = "alice").any { it.id == "trace-0200" })
    }

    private fun countTraces(): Int = database.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM agent_traces").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun trace(index: Int) = AgentTraceEntity(
        id = "trace-${index.toString().padStart(4, '0')}", sessionId = "session", kind = "tool",
        name = "test", durationMs = 1, success = true, errorCode = null, createdAt = index.toLong(),
        ownerUserId = if (index % 2 == 0) "alice" else "bob",
    )
}
