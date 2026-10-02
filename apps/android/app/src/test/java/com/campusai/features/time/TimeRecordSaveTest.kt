package com.campusai.features.time

import android.database.sqlite.SQLiteException
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.campusai.core.database.CampusDao
import com.campusai.core.database.TimeRecordEntity
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
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
class TimeRecordSaveTest {
    private val store = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun close() { store.clear(); Dispatchers.resetMain() }

    private fun viewModel(handle: (String, Array<out Any?>) -> Any?): TimeViewModel {
        val dao = Proxy.newProxyInstance(CampusDao::class.java.classLoader, arrayOf(CampusDao::class.java)) { _, method, args ->
            if (method.name == "getAllTimeRecordsFlow") flowOf(emptyList<TimeRecordEntity>())
            else handle(method.name, args.orEmpty())
        } as CampusDao
        return TimeViewModel(dao, ApplicationProvider.getApplicationContext(), "alice").also { store.put("time", it) }
    }

    @Test fun `failed insert resets pending and retry writes the captured owner`() = runTest {
        var calls = 0
        var received: TimeRecordEntity? = null
        val vm = viewModel { name, args ->
            check(name == "insertTimeRecordWithSnapshot")
            calls++
            if (calls == 1) throw SQLiteException("disk full")
            received = args[0] as TimeRecordEntity
            Unit
        }
        val failure = runCatching { vm.addTimeRecord("阅读", "学习", 0, 60_000, "笔记", "alice") }.exceptionOrNull()
        assertTrue(failure is SQLiteException)
        assertFalse(vm.isInserting.value)
        vm.addTimeRecord("阅读", "学习", 0, 60_000, "笔记", "alice")
        assertEquals(2, calls)
        assertEquals("alice", received!!.userId)
        assertEquals(1L, received!!.durationMinutes)
        assertFalse(vm.isInserting.value)
    }

    @Test fun `failed edit and missing record both remain retryable failures`() = runTest {
        var calls = 0
        val vm = viewModel { name, args ->
            check(name == "editOwnedTimeRecord")
            assertEquals("alice", args[7])
            calls++
            if (calls == 1) throw SQLiteException("busy")
            if (calls == 2) 0 else 1
        }
        assertTrue(runCatching { vm.editTimeRecord(1, "阅读", "学习", 0, 60_000, "", "alice") }.exceptionOrNull() is SQLiteException)
        assertFalse(vm.isInserting.value)
        assertTrue(runCatching { vm.editTimeRecord(1, "阅读", "学习", 0, 60_000, "", "alice") }.exceptionOrNull() is IllegalStateException)
        assertFalse(vm.isInserting.value)
        vm.editTimeRecord(1, "阅读", "学习", 0, 60_000, "", "alice")
        assertEquals(3, calls)
    }

    @Test fun `account change rejects earlier add and edit before database access`() = runTest {
        val vm = viewModel { _, _ -> error("Database must not receive another account's draft") }
        vm.setActiveUser("bob")
        assertTrue(runCatching { vm.addTimeRecord("阅读", "学习", 0, 60_000, "", "alice") }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { vm.editTimeRecord(1, "阅读", "学习", 0, 60_000, "", "alice") }.exceptionOrNull() is IllegalStateException)
        assertFalse(vm.isInserting.value)
    }

    @Test fun `pending write rejects duplicate submit and cancellation restores pending state`() = runTest {
        lateinit var continuation: Continuation<Unit>
        var calls = 0
        val vm = viewModel { name, args ->
            check(name == "insertTimeRecordWithSnapshot")
            calls++
            @Suppress("UNCHECKED_CAST")
            continuation = args.last() as Continuation<Unit>
            COROUTINE_SUSPENDED
        }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { vm.addTimeRecord("阅读", "学习", 0, 60_000, "", "alice") }
        assertTrue(vm.isInserting.value)
        assertTrue(runCatching { vm.addTimeRecord("阅读", "学习", 0, 60_000, "", "alice") }.exceptionOrNull() is IllegalStateException)
        assertEquals(1, calls)
        continuation.resumeWithException(CancellationException("screen left"))
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
        assertFalse(vm.isInserting.value)
    }
}
