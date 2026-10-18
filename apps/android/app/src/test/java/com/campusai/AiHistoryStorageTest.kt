package com.campusai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.campusai.core.database.AiReportEntity
import com.campusai.core.database.CampusDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiHistoryStorageTest {
    private lateinit var database: CampusDatabase
    private val queries = Collections.synchronizedList(mutableListOf<String>())

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), CampusDatabase::class.java,
        ).allowMainThreadQueries()
            .setQueryCallback({ sql, _ -> queries.add(sql) }, Executor { it.run() })
            .build()
    }

    @After fun tearDown() = database.close()

    @Test fun `history reads a bounded projection without loading large conversation bodies`() = runTest {
        val dao = database.campusDao()
        val body = "[{\"role\":\"assistant\",\"content\":\"${"x".repeat(512_000)}\"}]"
        repeat(4) { dao.insertAiReport(report("conversation-$it", updatedAt = it.toLong(), body = body)) }
        queries.clear()

        val page = dao.observeAiReportSummaries("alice", limit = 2).first()
        assertEquals(listOf("conversation-3", "conversation-2"), page.map { it.id })
        val reads = synchronized(queries) {
            queries.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) && it.contains("FROM ai_reports") }
        }
        assertTrue(reads.isNotEmpty())
        assertTrue(reads.all { !it.contains("messagesJson") && !it.contains("SELECT *") })
        assertEquals(body, dao.getAiReport(page.first().id, "alice")?.messagesJson)
    }

    @Test fun `keyset pages preserve ties and do not shift when a newer conversation arrives`() = runTest {
        val dao = database.campusDao()
        listOf("c", "b", "a").forEach { dao.insertAiReport(report(it, updatedAt = 100)) }
        dao.insertAiReport(report("older", updatedAt = 50))
        val first = dao.getAiReportSummaryPage("alice", limit = 2)
        assertEquals(listOf("c", "b"), first.map { it.id })
        dao.insertAiReport(report("new", updatedAt = 200))
        val cursor = first.last()
        val second = dao.getAiReportSummaryPage("alice", cursor.updatedAt, cursor.id, limit = 2)
        assertEquals(listOf("a", "older"), second.map { it.id })
        assertTrue(dao.getAiReportSummaryPage("alice", second.last().updatedAt, second.last().id, 2).isEmpty())
    }

    @Test fun `account reads and deletes stay isolated and undo keeps the original full report`() = runTest {
        val dao = database.campusDao()
        val alice = report("alice-report", body = "[{\"content\":\"private body\"}]")
        dao.insertAiReport(alice)
        dao.insertAiReport(report("bob-report", owner = "bob"))
        dao.insertAiReport(report("legacy-report", owner = "local_user"))
        assertEquals(listOf("alice-report"), dao.observeAiReportSummaries("alice").first().map { it.id })
        assertEquals(listOf("bob-report"), dao.getAiReportSummaryPage("bob").map { it.id })
        assertEquals(listOf("legacy-report"), dao.getAiReportSummaryPage("local_user").map { it.id })
        assertNull(dao.getAiReport(alice.id, "bob"))
        dao.deleteAiReport(alice.id, "bob")
        assertEquals(alice, dao.getAiReport(alice.id, "alice"))
        assertTrue(runCatching { dao.insertAiReport(alice.copy(ownerUserId = "bob")) }.isFailure)
        assertEquals(alice, dao.getAiReport(alice.id, "alice"))

        val deleted = requireNotNull(dao.getAiReport(alice.id, "alice")).toDomain()
        dao.deleteAiReport(alice.id, "alice")
        assertNull(dao.getAiReport(alice.id, "alice"))
        dao.insertAiReport(AiReportEntity.fromDomain(deleted))
        assertEquals(alice, dao.getAiReport(alice.id, "alice"))
        assertFalse(dao.getAiReportSummaryPage("bob").any { it.id == alice.id })
    }

    private fun report(
        id: String,
        owner: String = "alice",
        updatedAt: Long = 100,
        body: String = "[]",
    ) = AiReportEntity(
        id = id, provider = "LOCAL", mode = "FAST", model = "Qwen3.5-2B",
        executionEngine = "LOCAL_MNN", requestId = "request-$id", title = id,
        summary = "summary-$id", messagesJson = body, createdAt = 1, updatedAt = updatedAt,
        ownerUserId = owner,
    )
}
