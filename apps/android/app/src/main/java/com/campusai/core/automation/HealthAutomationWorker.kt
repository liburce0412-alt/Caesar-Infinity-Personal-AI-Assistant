package com.campusai.core.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.campusai.core.database.CampusDatabase
import java.util.concurrent.TimeUnit

class HealthAutomationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val runtime = ForegroundHealthTaskRuntime.get(applicationContext,
            CampusDatabase.getDatabase(applicationContext).campusDao())
        val config = runtime.store.read(HealthTaskDefaults.TASK_ID)
        if (config?.enabled != true || !config.options.runInBackground) return Result.success()
        // No automatic retry: the next configured interval handles an offline or failed attempt.
        runtime.runner.run(config.id, background = true)
        return Result.success()
    }
}

object HealthAutomationScheduler {
    internal const val UNIQUE_WORK = "caesar-health-automation"

    suspend fun apply(context: Context, config: ScheduledTaskConfig?): kotlin.Result<Unit> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (config?.enabled != true || !config.options.runInBackground) {
            manager.cancelUniqueWork(UNIQUE_WORK).result.get()
        } else {
            val request = PeriodicWorkRequestBuilder<HealthAutomationWorker>(config.backgroundIntervalMinutes.toLong(), TimeUnit.MINUTES)
                .setInitialDelay(config.backgroundIntervalMinutes.toLong(), TimeUnit.MINUTES)
                .build()
            manager.enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request).result.get()
        }
        Unit
        }
    }
}
