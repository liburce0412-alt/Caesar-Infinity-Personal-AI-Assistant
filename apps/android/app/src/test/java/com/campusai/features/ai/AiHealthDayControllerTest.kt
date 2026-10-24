package com.campusai.features.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.impl.WorkManagerImpl
import com.campusai.core.health.*
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiHealthDayControllerTest {
    private val midnight = Instant.parse("2026-10-17T16:00:00Z").toEpochMilli()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun initializeWorkManager() {
        if (WorkManagerImpl.getInstance() == null) WorkManagerImpl.initialize(context, Configuration.Builder().build())
    }

    @Test fun `a read begun before midnight cannot publish yesterday after midnight`() = runTest {
        var now = midnight - 1
        val old = snapshot(now)
        val gate = CompletableDeferred<Unit>()
        val gateway = Gateway { period ->
            if (period.startEpochMillis < midnight) { gate.await(); Result.success(old) }
            else Result.success(snapshot(now))
        }
        val controller = AiHealthController(context, this, gateway) { now }
        controller.refreshHealthStatus()
        runCurrent()
        assertTrue(controller.healthState.value.loading)
        now = midnight + 1
        gate.complete(Unit)
        runCurrent()
        assertFalse(controller.healthState.value.loading)
        assertEquals(snapshot(now), controller.healthState.value.snapshot)
        assertEquals(listOf(midnight - 86_400_000L, midnight), gateway.periods.map { it.startEpochMillis })
        controller.close()
    }

    @Test fun `day refresh clears old displayed snapshot before waiting and asks for the new Beijing window`() = runTest {
        var now = midnight - 1
        val old = snapshot(now)
        val gateway = Gateway { Result.success(old) }
        val controller = AiHealthController(context, this, gateway) { now }
        controller.refreshHealthStatus()
        runCurrent()
        assertEquals(old, controller.healthState.value.snapshot)
        val gate = CompletableDeferred<Unit>()
        gateway.read = { gate.await(); Result.failure(IllegalStateException("No current-day cache")) }
        now = midnight + 1
        controller.refreshHealthStatus()
        runCurrent()
        assertTrue(controller.healthState.value.loading)
        assertNull(controller.healthState.value.snapshot)
        assertEquals(midnight, gateway.periods.last().startEpochMillis)
        assertEquals(now, gateway.periods.last().endEpochMillis)
        gate.complete(Unit)
        runCurrent()
        assertNull(controller.healthState.value.snapshot)
        assertFalse(controller.healthState.value.loading)
        controller.close()
    }

    @Test fun `cache commit refreshes the visible timestamp without waiting for an automation message`() = runTest {
        var now = midnight + 1
        val updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val gateway = Gateway { Result.success(snapshot(now)) }
        val controller = AiHealthController(context, this, gateway, updates) { now }
        controller.refreshHealthStatus()
        runCurrent()
        assertEquals(now, controller.healthState.value.snapshot?.lastSyncAt)
        now += 10_000L
        updates.emit(Unit)
        runCurrent()
        assertEquals(now, controller.healthState.value.snapshot?.lastSyncAt)
        assertFalse(controller.healthState.value.loading)
        controller.close()
        val reads = gateway.periods.size
        updates.emit(Unit)
        runCurrent()
        assertEquals(reads, gateway.periods.size)
    }

    private fun snapshot(now: Long) = HealthSnapshot(setOf("local.health"), HealthPeriods.parse("today", now),
        now, now, HealthFreshness.FRESH, HealthMetrics(steps = 123), emptySet(), 1.0)

    private class Gateway(var read: suspend (HealthPeriod) -> Result<HealthSnapshot>) : HealthGateway {
        val periods = mutableListOf<HealthPeriod>()
        override val readPermissions = emptySet<String>()
        override fun availability() = HealthAvailability.Available
        override suspend fun grantedPermissions() = emptySet<String>()
        override suspend fun snapshot(period: HealthPeriod): Result<HealthSnapshot> {
            periods += period
            return read(period)
        }
    }
}
