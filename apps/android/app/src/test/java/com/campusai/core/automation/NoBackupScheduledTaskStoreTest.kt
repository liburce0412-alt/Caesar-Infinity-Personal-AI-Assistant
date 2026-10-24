package com.campusai.core.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.campusai.core.ai.CloudAiProvider
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoBackupScheduledTaskStoreTest {
    @Test fun customIntervalSurvivesReloadWithoutBeingReplacedByBackgroundMinimum() = runTest {
        val store = NoBackupScheduledTaskStore(context)
        val config = ScheduledTaskConfig("daily", ScheduledTaskType.HEALTH_CLOUD_STATUS, true, 7,
            CloudAiProvider.GOOGLE_GEMINI, ScheduledTaskConfig.CLOUD_FREE_MODEL, false,
            options = HealthTaskOptions(syncOnly = true, runInBackground = true,
                aiMode = HealthTaskAiMode.CAESAR_CLOUD_FREE))
        store.save(config).getOrThrow()
        val restored = checkNotNull(NoBackupScheduledTaskStore(context).read("daily"))
        assertEquals(config, restored)
        assertEquals(7, restored.intervalMinutes)
        assertEquals(15, restored.backgroundIntervalMinutes)
    }

    @Test fun options_roundtrip_and_legacy_config_remains_cloud_only() = runTest {
        val store = NoBackupScheduledTaskStore(context)
        val config = ScheduledTaskConfig("daily", ScheduledTaskType.HEALTH_CLOUD_STATUS, true, 15,
            CloudAiProvider.GOOGLE_GEMINI, ScheduledTaskConfig.CLOUD_FREE_MODEL, true,
            options = HealthTaskOptions(syncOnly = true, runInBackground = true,
                aiMode = HealthTaskAiMode.CAESAR_CLOUD_FREE, cloudFreeOwnerUserId = "owner"))
        store.save(config).getOrThrow()
        assertEquals(config, store.read("daily"))
        val legacy = org.json.JSONObject(file.readText())
        val row = legacy.getJSONArray("tasks").getJSONObject(0)
        listOf("syncOnly", "syncBeforeMessage", "runInBackground", "aiMode", "cloudFreeOwnerUserId").forEach(row::remove)
        row.put("modelId", "gemini-2.5-flash")
        file.writeText(legacy.toString())
        val restored = checkNotNull(store.read("daily"))
        assertFalse(restored.options.syncBeforeMessage)
        assertFalse(restored.options.runInBackground)
        assertEquals(HealthTaskAiMode.PERSONAL_CLOUD, restored.options.aiMode)
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file = File(context.noBackupFilesDir, "automation/scheduled_tasks_v1.json")

    @After
    fun tearDown() {
        file.delete()
        file.parentFile?.delete()
    }

    @Test
    fun persists_only_non_secret_task_configuration_under_no_backup() = runTest {
        val store = NoBackupScheduledTaskStore(context)
        val config = ScheduledTaskConfig(
            id = "daily",
            type = ScheduledTaskType.HEALTH_CLOUD_STATUS,
            enabled = true,
            provider = CloudAiProvider.GOOGLE_GEMINI,
            modelId = "gemini-2.5-flash",
            includeHealthSummary = true,
        )

        assertTrue(store.save(config).isSuccess)
        assertEquals(config, NoBackupScheduledTaskStore(context).read("daily"))
        assertTrue(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        val raw = file.readText()
        assertFalse(raw.contains("apiKey", ignoreCase = true))
        assertFalse(raw.contains("passToken", ignoreCase = true))
    }

    @Test
    fun corruptStoreIsSurfacedAndNeverReplacedBySave() = runTest {
        file.parentFile?.mkdirs()
        val corrupt = "{\"version\":1,\"tasks\":["
        file.writeText(corrupt)
        val store = NoBackupScheduledTaskStore(context)

        val readFailure = runCatching { store.readAll() }.exceptionOrNull()
        val saveFailure = store.save(
            ScheduledTaskConfig(
                id = "daily",
                type = ScheduledTaskType.HEALTH_CLOUD_STATUS,
                enabled = true,
                provider = CloudAiProvider.GOOGLE_GEMINI,
                modelId = "gemini-2.5-flash",
                includeHealthSummary = true,
            ),
        ).exceptionOrNull()

        assertEquals("task_store_read_failed", (readFailure as HealthTaskException).code)
        assertEquals("task_store_read_failed", (saveFailure as HealthTaskException).code)
        assertEquals(corrupt, file.readText())
    }

    @Test
    fun saveIfCurrent_is_atomic_across_store_instances() = runTest {
        val storeA = NoBackupScheduledTaskStore(context)
        val storeB = NoBackupScheduledTaskStore(context)
        val initial = ScheduledTaskConfig(
            id = "daily",
            type = ScheduledTaskType.HEALTH_CLOUD_STATUS,
            enabled = true,
            intervalMinutes = 5,
            provider = CloudAiProvider.GOOGLE_GEMINI,
            modelId = "gemini-2.5-flash",
            includeHealthSummary = true,
        )
        val replacementA = initial.copy(intervalMinutes = 10)
        val replacementB = initial.copy(intervalMinutes = 15)

        repeat(40) {
            storeA.save(initial).getOrThrow()
            val expected = checkNotNull(storeA.read("daily"))
            val start = CompletableDeferred<Unit>()
            val first = async(Dispatchers.IO) {
                start.await()
                storeA.saveIfCurrent(expected, replacementA).getOrThrow()
            }
            val second = async(Dispatchers.IO) {
                start.await()
                storeB.saveIfCurrent(expected, replacementB).getOrThrow()
            }

            start.complete(Unit)
            val outcomes = listOf(first.await(), second.await())
            val final = checkNotNull(NoBackupScheduledTaskStore(context).read("daily"))

            assertEquals(1, outcomes.count { it })
            assertTrue(final == replacementA || final == replacementB)
        }
    }
}
