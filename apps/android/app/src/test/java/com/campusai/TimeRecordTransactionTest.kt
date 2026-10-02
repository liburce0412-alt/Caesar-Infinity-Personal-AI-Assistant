package com.campusai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.campusai.core.database.CampusDatabase
import com.campusai.core.database.DailyGoalSnapshotEntity
import com.campusai.core.database.TimeRecordEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimeRecordTransactionTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CampusDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = database.campusDao()
    @After fun close() = database.close()
    private fun record(owner: String) = TimeRecordEntity(title = "阅读", category = "学习", startTime = 0,
        endTime = 60_000, durationMinutes = 1, remark = "", userId = owner)

    @Test fun `snapshot failure rolls back record and a retry produces one complete save`() = runBlocking {
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER reject_snapshot BEFORE INSERT ON daily_goal_snapshots BEGIN SELECT RAISE(ABORT, 'disk full'); END")
        val row = record("alice")
        val snapshot = DailyGoalSnapshotEntity("alice", "2026-10-02", 240)
        assertNotNull(runCatching { dao.insertTimeRecordWithSnapshot(row, snapshot) }.exceptionOrNull())
        assertTrue(dao.getAllTimeRecordsFlow("alice", false).first().isEmpty())
        assertTrue(dao.getDailyGoalSnapshotsFlow("alice", false).first().isEmpty())
        sql.execSQL("DROP TRIGGER reject_snapshot")
        dao.insertTimeRecordWithSnapshot(row, snapshot)
        assertEquals(1, dao.getAllTimeRecordsFlow("alice", false).first().size)
        assertEquals(listOf(snapshot), dao.getDailyGoalSnapshotsFlow("alice", false).first())
    }

    @Test fun `editing cannot change another account or deleted record while local rows remain editable`() = runBlocking {
        val alice = dao.insertTimeRecord(record("alice")).toInt()
        val local = dao.insertTimeRecord(record("local_user")).toInt()
        assertEquals(0, dao.editOwnedTimeRecord(alice, "other", "学习", 0, 60_000, 1, "", "bob"))
        assertEquals("阅读", dao.timeRecordById(alice)!!.title)
        assertEquals(1, dao.editOwnedTimeRecord(local, "本机阅读", "学习", 0, 60_000, 1, "", "bob"))
        dao.softDeleteTimeRecord(alice)
        assertEquals(0, dao.editOwnedTimeRecord(alice, "deleted", "学习", 0, 60_000, 1, "", "alice"))
    }
}
