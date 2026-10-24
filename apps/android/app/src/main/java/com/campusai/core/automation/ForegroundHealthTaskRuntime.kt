package com.campusai.core.automation

import android.content.Context
import com.campusai.core.database.CampusDao
import com.campusai.core.network.PersonalCloudHealthAutoMessageClient
import com.campusai.core.security.PersonalAiProviderStore

/** Shared by the foreground timer and opt-in WorkManager timer; the runner serializes attempts. */
class ForegroundHealthTaskRuntime(
    context: Context,
    dao: CampusDao,
) {
    // Restore the existing session for free-cloud requests even after a cold background start.
    private val auth = com.campusai.core.auth.AuthRepository.getInstance(context.applicationContext)
    val store: ScheduledTaskStore = NoBackupScheduledTaskStore(context.applicationContext)
    val aiClient: HealthAutoMessageClient = PersonalCloudHealthAutoMessageClient(
        PersonalAiProviderStore(context.applicationContext),
        localRuntime = { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            com.campusai.core.localai.LocalAiRuntime.get(context.applicationContext)
        } },
    )
    val runner: ForegroundHealthTaskRunner = DefaultForegroundHealthTaskRunner(
        store = store,
        source = MiFitnessForegroundHealthCloudSource(context.applicationContext),
        aiClient = aiClient,
        conversationWriter = RoomHealthTaskConversationWriter(dao),
        notificationPublisher = AndroidHealthTaskNotificationPublisher(context.applicationContext),
        progress = AndroidHealthTaskProgressPublisher(context.applicationContext),
    )

    companion object {
        @Volatile
        private var instance: ForegroundHealthTaskRuntime? = null

        fun get(context: Context, dao: CampusDao): ForegroundHealthTaskRuntime =
            instance ?: synchronized(this) {
                instance ?: ForegroundHealthTaskRuntime(context.applicationContext, dao).also { instance = it }
            }
    }
}
