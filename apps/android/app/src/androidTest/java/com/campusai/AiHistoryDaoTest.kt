package com.campusai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.campusai.core.database.AiReportEntity
import com.campusai.core.database.CampusDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiHistoryDaoTest {
    private lateinit var database: CampusDatabase

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CampusDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun stableConversationIsUpdatedDeletedAndRestored() = runBlocking {
        val original = report(summary = "第一轮", updatedAt = 100)
        database.campusDao().insertAiReport(original)
        database.campusDao().insertAiReport(report(summary = "继续后的第二轮", updatedAt = 200))

        val updated = database.campusDao().getAiReportsFlow().first()
        assertEquals(1, updated.size)
        assertEquals("继续后的第二轮", updated.single().summary)
        assertEquals(200L, updated.single().updatedAt)

        database.campusDao().deleteAiReport("conversation-1")
        assertEquals(0, database.campusDao().getAiReportsFlow().first().size)

        database.campusDao().insertAiReport(updated.single())
        assertEquals("conversation-1", database.campusDao().getAiReportsFlow().first().single().id)
    }

    private fun report(summary: String, updatedAt: Long) = AiReportEntity(
        id = "conversation-1",
        provider = "LOCAL",
        mode = "FAST",
        model = "Qwen3.5-2B · MNN 4-bit",
        title = "HiFresh",
        summary = summary,
        messagesJson = "[]",
        executionEngine = "MNN",
        requestId = "test-request",
        createdAt = 50,
        updatedAt = updatedAt,
    )
}
