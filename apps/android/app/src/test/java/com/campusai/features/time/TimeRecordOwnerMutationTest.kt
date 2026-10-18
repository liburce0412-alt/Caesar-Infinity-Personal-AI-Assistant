package com.campusai.features.time

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.campusai.core.database.CampusDatabase
import com.campusai.core.database.CourseScheduleEntity
import com.campusai.core.database.TimeRecordEntity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimeRecordOwnerMutationTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CampusDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = database.campusDao()
    private val store = ViewModelStore()
    private lateinit var vm: TimeViewModel

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        vm = TimeViewModel(dao, ApplicationProvider.getApplicationContext(), "alice").also { store.put("time", it) }
    }
    @After fun close() { store.clear(); database.close(); Dispatchers.resetMain() }

    @Test fun `foreign record and course never mutate even when ids come from a stale screen`() = runTest {
        val id = dao.insertTimeRecord(record("alice")).toInt()
        val courseId = dao.insertCourseSchedules(listOf(course("alice"))).single().toInt()
        val original = dao.timeRecordById(id)
        val originalCourse = dao.courseById(courseId)
        vm.setActiveUser("bob")
        assertTrue(runCatching { vm.deleteTimeRecord(id, "bob") }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { vm.deleteCourse(courseId, "bob") }.exceptionOrNull() is IllegalStateException)
        assertEquals(original, dao.timeRecordById(id))
        assertEquals(originalCourse, dao.courseById(courseId))

        vm.setActiveUser("alice")
        vm.deleteTimeRecord(id, "alice")
        val tombstone = dao.timeRecordById(id)
        assertNotNull(tombstone!!.deletedAt)
        vm.setActiveUser("bob")
        assertTrue(runCatching { vm.undoDeleteTimeRecord(id, "bob") }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { vm.undoDeleteTimeRecord(id, "alice") }.exceptionOrNull() is IllegalStateException)
        assertEquals(tombstone, dao.timeRecordById(id))
    }

    @Test fun `queued delete captured by previous account is refused before execution`() = runTest {
        val id = dao.insertTimeRecord(record("alice")).toInt()
        val courseId = dao.insertCourseSchedules(listOf(course("alice"))).single().toInt()
        val before = dao.timeRecordById(id)
        val beforeCourse = dao.courseById(courseId)
        val recordDeletion = async(start = CoroutineStart.LAZY) { runCatching { vm.deleteTimeRecord(id, "alice") } }
        val courseDeletion = async(start = CoroutineStart.LAZY) { runCatching { vm.deleteCourse(courseId, "alice") } }
        vm.setActiveUser("bob")
        assertTrue(recordDeletion.await().exceptionOrNull() is IllegalStateException)
        assertTrue(courseDeletion.await().exceptionOrNull() is IllegalStateException)
        assertEquals(before, dao.timeRecordById(id))
        assertEquals(beforeCourse, dao.courseById(courseId))
    }

    @Test fun `owned deletion restore and explicit local legacy mutation preserve stored owners`() = runTest {
        val owned = dao.insertTimeRecord(record("alice")).toInt()
        val local = dao.insertTimeRecord(record("local_user")).toInt()
        val localCourse = dao.insertCourseSchedules(listOf(course("local_user"))).single().toInt()
        vm.deleteTimeRecord(owned, "alice")
        vm.undoDeleteTimeRecord(owned, "alice")
        assertNull(dao.timeRecordById(owned)!!.deletedAt)
        assertEquals("alice", dao.timeRecordById(owned)!!.userId)
        vm.setActiveUser("bob")
        vm.deleteTimeRecord(local, "bob")
        vm.undoDeleteTimeRecord(local, "bob")
        vm.deleteCourse(localCourse, "bob")
        assertNull(dao.timeRecordById(local)!!.deletedAt)
        assertEquals("local_user", dao.timeRecordById(local)!!.userId)
        assertNotNull(dao.courseById(localCourse)!!.deletedAt)
        assertEquals("local_user", dao.courseById(localCourse)!!.userId)
        assertTrue(runCatching { vm.deleteTimeRecord(999, "bob") }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { vm.undoDeleteTimeRecord(999, "bob") }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { vm.deleteCourse(999, "bob") }.exceptionOrNull() is IllegalStateException)
    }

    private fun record(owner: String) = TimeRecordEntity(title = "合成记录", category = "学习", startTime = 0,
        endTime = 60_000, durationMinutes = 1, remark = "", userId = owner)
    private fun course(owner: String) = CourseScheduleEntity(name = "合成课程", weekday = 1, startMinute = 480,
        endMinute = 540, location = "", teacher = "", weeks = "第1周", sourceHash = owner, userId = owner)
}
