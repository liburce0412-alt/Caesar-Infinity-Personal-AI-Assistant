package com.campusai.core.health.mifitness

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MiFitnessDeviceSyncTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `manual request is package scoped with no user data and never opens an activity`() {
        installMiFitness()
        assertEquals(MiFitnessDeviceSyncStatus.REQUESTED, MiFitnessDeviceSync(app).request())
        val sent = shadowOf(app).broadcastIntents.single { it.action == MiFitnessDeviceSync.ACTION_REQUEST_SYNC }
        assertEquals("com.mi.health", sent.`package`)
        assertNull(sent.extras)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun `missing disabled and stopped apps do not receive a broadcast or launch`() {
        assertEquals(MiFitnessDeviceSyncStatus.APP_UNAVAILABLE, MiFitnessDeviceSync(app).request())
        installMiFitness(enabled = false)
        assertEquals(MiFitnessDeviceSyncStatus.APP_UNAVAILABLE, MiFitnessDeviceSync(app).request())
        installMiFitness(flags = ApplicationInfo.FLAG_STOPPED)
        assertEquals(MiFitnessDeviceSyncStatus.APP_STOPPED, MiFitnessDeviceSync(app).request())
        assertTrue(shadowOf(app).broadcastIntents.none { it.action == MiFitnessDeviceSync.ACTION_REQUEST_SYNC })
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun `broadcast rejection becomes a safe fallback instead of crashing`() {
        installMiFitness()
        val blocked = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun sendBroadcast(intent: Intent) { throw SecurityException("private platform error") }
        }
        assertEquals(MiFitnessDeviceSyncStatus.BLOCKED, MiFitnessDeviceSync(blocked).request())
    }

    @Test fun `one request immediately starts cloud refresh without masking cloud failure`() = runTest {
        val events = mutableListOf<String>()
        val cloudError = MiFitnessStepsSyncException("network_failed", "safe")
        val job = launch {
            val result = requestThenRefreshMiFitness(
                requestDeviceSync = { events += "request"; MiFitnessDeviceSyncStatus.REQUESTED },
                onDeviceRequest = { events += it.name },
                refreshCloud = { events += "cloud"; Result.failure(cloudError) },
            )
            assertSame(cloudError, result.exceptionOrNull())
        }
        runCurrent()
        job.join()
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(listOf("request", "REQUESTED", "cloud"), events)
    }

    @Test fun `unavailable device sync still reads cloud immediately`() = runTest {
        MiFitnessDeviceSyncStatus.entries.filter { it != MiFitnessDeviceSyncStatus.REQUESTED }.forEach { status ->
            var reads = 0
            requestThenRefreshMiFitness(
                requestDeviceSync = { status },
                onDeviceRequest = { assertEquals(status, it) },
                refreshCloud = { reads++; Result.failure(IllegalStateException("no data")) },
            )
            assertEquals(1, reads)
        }
    }

    @Test fun `cancelling before request progress is committed prevents a cloud read`() = runTest {
        var requests = 0
        var reads = 0
        val job = launch {
            requestThenRefreshMiFitness(
                requestDeviceSync = { requests++; MiFitnessDeviceSyncStatus.REQUESTED },
                onDeviceRequest = { awaitCancellation() },
                refreshCloud = { reads++; Result.failure(IllegalStateException("no data")) },
            )
        }
        runCurrent()
        job.cancelAndJoin()
        advanceTimeBy(20_000)
        assertEquals(1, requests)
        assertEquals(0, reads)
    }

    private fun installMiFitness(enabled: Boolean = true, flags: Int = 0) {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = MiFitnessDeviceSync.PACKAGE_NAME
            applicationInfo = ApplicationInfo().apply {
                packageName = MiFitnessDeviceSync.PACKAGE_NAME
                this.enabled = enabled
                this.flags = flags
            }
        })
    }
}
