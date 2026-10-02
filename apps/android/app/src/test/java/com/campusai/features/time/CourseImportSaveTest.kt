package com.campusai.features.time

import android.database.sqlite.SQLiteException
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.campusai.core.database.CampusDao
import com.campusai.core.database.CourseScheduleEntity
import com.campusai.core.database.TimeRecordEntity
import com.campusai.core.model.CourseSchedule
import com.campusai.core.sync.CampusSyncScheduler
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CourseImportSaveTest {
    private val course = CourseSchedule(name = "数学", weekday = 1, startMinute = 480, endMinute = 580, sourceHash = "math")
    private fun dao(import: (List<CourseScheduleEntity>) -> List<Long>): CampusDao = Proxy.newProxyInstance(
        CampusDao::class.java.classLoader, arrayOf(CampusDao::class.java),
    ) { _, method, args -> when (method.name) {
        "getAllTimeRecordsFlow" -> flowOf(emptyList<TimeRecordEntity>())
        "importCourseSchedules" -> { @Suppress("UNCHECKED_CAST") import(args!![0] as List<CourseScheduleEntity>) }
        else -> error("Unexpected DAO call: ${method.name}")
    } } as CampusDao

    @Test fun `a changed account cannot receive an earlier import`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            var called = false
            val vm = TimeViewModel(dao { called = true; listOf(-1L) }, ApplicationProvider.getApplicationContext(), "alice")
            store.put("time", vm)
            vm.setActiveUser("bob")
            assertTrue(runCatching { vm.importCourses(listOf(course), "alice") }.exceptionOrNull() is IllegalStateException)
            assertFalse(called)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `database failure propagates to the preview instead of becoming a detached coroutine failure`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val vm = TimeViewModel(dao { throw SQLiteException("disk full") }, ApplicationProvider.getApplicationContext(), "alice")
            store.put("time", vm)
            val failure = runCatching { vm.importCourses(listOf(course), "alice") }.exceptionOrNull()
            assertTrue(failure is SQLiteException)
            assertEquals("disk full", failure?.message)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `duplicate retry retains the captured owner and returns the actual duplicate count`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            var received = emptyList<CourseScheduleEntity>()
            val vm = TimeViewModel(dao { received = it; listOf(-1L) }, ApplicationProvider.getApplicationContext(), "alice")
            store.put("time", vm)
            assertEquals(CourseImportResult(0, 1), vm.importCourses(listOf(course), "alice"))
            assertEquals("alice", received.single().userId)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `committed import reports success when sync scheduling fails`() = runTest {
        val application = ApplicationProvider.getApplicationContext<Context>()
        // WorkManager bypasses Context when either singleton is already initialized. Temporarily
        // clear both caches to exercise its real initialization failure, then restore them exactly.
        val managerClass = Class.forName("androidx.work.impl.WorkManagerImpl")
        val managerCaches = listOf("sDelegatedInstance", "sDefaultInstance").map { name ->
            managerClass.getDeclaredField(name).apply { isAccessible = true }.let { it to it.get(null) }
        }
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            managerCaches.forEach { (field, _) -> field.set(null, null) }
            var schedulingAttempts = 0
            val context = object : ContextWrapper(application) {
                override fun getApplicationContext(): Context {
                    schedulingAttempts++
                    throw IllegalStateException("sync scheduling unavailable")
                }
            }
            assertEquals("sync scheduling unavailable", runCatching { CampusSyncScheduler.enqueue(context) }.exceptionOrNull()?.message)
            var writes = 0
            val vm = TimeViewModel(dao { rows ->
                writes++
                assertEquals("alice", rows.single().userId)
                listOf(42L)
            }, context, "alice")
            store.put("time", vm)
            assertEquals(CourseImportResult(1, 0), vm.importCourses(listOf(course), "alice"))
            assertEquals(1, writes)
            assertEquals(2, schedulingAttempts)
        } finally {
            managerCaches.forEach { (field, value) -> field.set(null, value) }
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
