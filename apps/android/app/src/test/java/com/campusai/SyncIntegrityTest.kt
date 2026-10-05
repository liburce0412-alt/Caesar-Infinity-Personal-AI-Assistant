package com.campusai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.campusai.core.database.CampusDatabase
import com.campusai.core.database.CourseScheduleEntity
import com.campusai.core.database.TimeRecordEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncIntegrityTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CampusDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = database.campusDao()
    @After fun close() = database.close()
    private fun course(owner: String, hash: String = "same") = CourseScheduleEntity(
        name = "数学", weekday = 1, startMinute = 480, endMinute = 540,
        location = "", teacher = "", weeks = "", sourceHash = hash, userId = owner,
    )
    private suspend fun record(): TimeRecordEntity {
        val row = TimeRecordEntity(title = "Original", category = "学习", startTime = 1,
            endTime = 60001, durationMinutes = 1, remark = "", userId = "alice")
        return row.copy(id = dao.insertTimeRecord(row).toInt())
    }

    @Test fun `course editing preserves identity and rejects other owners and deleted rows`() = runBlocking {
        val original = course("alice").copy(remoteId = "remote-course", version = 4, syncState = "synced")
        val id = dao.insertCourseSchedules(listOf(original)).single().toInt()
        val edit = original.toDomain().copy(id = id, name = "Java 程序设计", location = "三教 3206", teacher = "张老师",
            weekday = 4, weeks = "第5周", periodStart = 1, periodEnd = 2)
        assertFalse(dao.editOwnedCourse(edit, "bob"))
        assertTrue(dao.editOwnedCourse(edit, "alice"))
        val saved = dao.courseById(id)!!
        assertEquals(edit, saved.toDomain())
        assertEquals(original.clientId, saved.clientId)
        assertEquals(original.remoteId, saved.remoteId)
        assertEquals(original.sourceHash, saved.sourceHash)
        assertEquals(5, saved.version)
        assertEquals("pending", saved.syncState)
        dao.acknowledgeCourse(original.copy(id = id), original.copy(id = id, syncState = "synced"))
        assertEquals("三教 3206", dao.courseById(id)!!.location)
        dao.softDeleteCourseSchedule(id)
        assertFalse(dao.editOwnedCourse(edit, "alice"))
    }

    @Test fun `same course hash belongs independently to two accounts`() = runBlocking {
        val alice = course("alice").copy(syncState = "synced")
        dao.insertCourseSchedules(listOf(alice, course("bob")))
        dao.applyRemoteCourse(course("bob").copy(name = "Bob math", syncState = "synced", version = 2))
        assertEquals("数学", dao.getCourseBySourceHash("same", "alice")!!.name)
        assertNotNull(dao.getCourseBySourceHash("same", "bob"))
        assertNull(dao.getCourseByClientId(alice.clientId, "bob"))
    }

    @Test fun `a reused client ID cannot overwrite another owners time or course`() = runBlocking {
        val alice = record()
        dao.insertTimeRecord(alice.copy(id = 0, userId = "bob", title = "Bob"))
        assertEquals("Original", dao.getTimeRecordByClientId(alice.clientId, "alice")!!.title)
        assertEquals("Bob", dao.getTimeRecordByClientId(alice.clientId, "bob")!!.title)
        val first = course("alice")
        val ids = dao.insertCourseSchedules(listOf(first, first.copy(userId = "bob")))
        assertTrue(ids.all { it > 0 })
    }

    @Test fun `rescued course satisfies remote fingerprint contract`() = runBlocking {
        val local = course("alice").copy(name = "数学".repeat(80))
        val id = dao.insertCourseSchedules(listOf(local)).single().toInt()
        dao.applyRemoteCourse(local.copy(id = id, name = "remote", syncState = "synced", version = 3), local.copy(id = id))
        val rescued = dao.getPendingCourseSchedules("alice").single()
        assertTrue(rescued.sourceHash.matches(Regex("[0-9a-f]{64}")))
        assertTrue(rescued.name.length <= 160)
        assertEquals("remote", dao.courseById(id)!!.name)
    }

    @Test fun `guest ownership is claimed once without replacing an account duplicate`() = runBlocking {
        dao.insertCourseSchedules(listOf(course("local_user"), course("alice"), course("local_user", "unique")))
        dao.claimGuestCourses("alice")
        assertNotNull(dao.getCourseBySourceHash("same", "local_user"))
        assertNotNull(dao.getCourseBySourceHash("same", "alice"))
        assertEquals("alice", dao.getCourseBySourceHash("unique", "alice")!!.userId)
        assertTrue(dao.getPendingCourseSchedules("bob").isEmpty())
    }

    @Test fun `success and failure cannot roll back an edit`() = runBlocking {
        val snapshot = record()
        dao.editTimeRecord(snapshot.id, "Edited", "运动", 1, 120001, 2, "new", snapshot.updatedAt + 1)
        assertFalse(dao.acknowledgeTime(snapshot, snapshot.copy(remoteId = "remote", version = 4, syncState = "synced")))
        dao.failTime(snapshot)
        val row = dao.timeRecordById(snapshot.id)!!
        assertEquals("Edited", row.title)
        assertEquals("new", row.remark)
        assertEquals("pending", row.syncState)
        assertEquals(5, row.version)
        assertEquals("remote", row.remoteId)
    }

    @Test fun `delete during upload and undo during delete remain pending`() = runBlocking {
        val original = record()
        dao.softDeleteTimeRecord(original.id, original.updatedAt + 1)
        dao.acknowledgeTime(original, original.copy(remoteId = "remote", syncState = "synced"))
        val deleted = dao.timeRecordById(original.id)!!
        assertNotNull(deleted.deletedAt)
        dao.undoDeleteTimeRecord(deleted.id, deleted.updatedAt + 1)
        dao.acknowledgeTime(deleted, deleted.copy(syncState = "synced"))
        assertNull(dao.timeRecordById(original.id)!!.deletedAt)
        assertEquals("pending", dao.timeRecordById(original.id)!!.syncState)
    }

    @Test fun `undo prevents purge of an unsent tombstone`() = runBlocking {
        val original = record()
        dao.softDeleteTimeRecord(original.id)
        val deleted = dao.timeRecordById(original.id)!!
        dao.undoDeleteTimeRecord(original.id)
        assertFalse(dao.purgeUnsentTime(deleted))
        assertNotNull(dao.timeRecordById(original.id))
    }

    @Test fun `remote pull never overwrites pending edits`() = runBlocking {
        val row = record()
        dao.applyRemoteTime(row.copy(title = "Remote", version = 8, syncState = "synced"))
        assertEquals("Original", dao.timeRecordById(row.id)!!.title)
    }

    @Test fun `conflict rescues the latest local payload and accepts the remote tombstone`() = runBlocking {
        val snapshot = record()
        dao.editTimeRecord(snapshot.id, "Latest", "学习", 1, 120001, 2, "", snapshot.updatedAt + 1)
        val remote = snapshot.copy(title = "Remote", remoteId = "remote", version = 5, syncState = "synced", deletedAt = 8000)
        dao.applyRemoteTime(remote, snapshot)
        assertEquals(8000L, dao.timeRecordById(snapshot.id)!!.deletedAt)
        val rescued = dao.getPendingTimeRecords("alice").single()
        assertEquals("Latest（同步冲突副本）", rescued.title)
        assertNotEquals(snapshot.clientId, rescued.clientId)
        dao.applyRemoteTime(remote)
        assertEquals(1, dao.getPendingTimeRecords("alice").size)
    }

    @Test fun `course deletion racing upload retains deletion`() = runBlocking {
        val row = course("alice")
        val snapshot = row.copy(id = dao.insertCourseSchedules(listOf(row)).single().toInt())
        dao.softDeleteCourseSchedule(snapshot.id)
        assertFalse(dao.acknowledgeCourse(snapshot, snapshot.copy(remoteId = "remote", syncState = "synced")))
        dao.failCourse(snapshot)
        assertNotNull(dao.courseById(snapshot.id)!!.deletedAt)
        assertEquals("pending", dao.courseById(snapshot.id)!!.syncState)
    }

    @Test fun `delete after several offline edits rebases and can retry`() = runBlocking {
        val row = record().copy(version = 8, syncState = "failed", deletedAt = 9000, remoteId = "remote")
        dao.updateTimeRecord(row)
        dao.applyRemoteTime(row.copy(title = "server", version = 3, syncState = "synced", deletedAt = null))
        val retry = dao.timeRecordById(row.id)!!
        assertEquals(4, retry.version)
        assertEquals("pending", retry.syncState)
        assertEquals(9000L, retry.deletedAt)
        assertEquals("Original", retry.title)
    }

    @Test fun `an already deleted remote row acknowledges a newer local tombstone`() = runBlocking {
        val row = record().copy(version = 8, syncState = "failed", deletedAt = 9000, remoteId = "remote")
        dao.updateTimeRecord(row)
        dao.applyRemoteTime(row.copy(version = 3, syncState = "synced", deletedAt = 8000))
        assertEquals("synced", dao.timeRecordById(row.id)!!.syncState)
        assertTrue(dao.getPendingTimeRecords("alice").isEmpty())
    }

    @Test fun `reimport restores a deleted course instead of silently skipping it`() = runBlocking {
        val row = course("alice")
        val id = dao.insertCourseSchedules(listOf(row)).single().toInt()
        dao.softDeleteCourseSchedule(id)
        assertEquals(id.toLong(), dao.importCourseSchedules(listOf(row)).single())
        assertNull(dao.courseById(id)!!.deletedAt)
        assertEquals("pending", dao.courseById(id)!!.syncState)
    }
}
