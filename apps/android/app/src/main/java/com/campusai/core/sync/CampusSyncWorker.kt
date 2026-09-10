package com.campusai.core.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.campusai.core.auth.AuthRepository
import com.campusai.core.database.CampusDao
import com.campusai.core.database.CampusDatabase
import com.campusai.core.database.CourseScheduleEntity
import com.campusai.core.database.TimeRecordEntity
import com.campusai.core.network.SupabaseClient
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

class CampusSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!SupabaseClient.isConfigured()) return Result.success()
        val auth = AuthRepository(applicationContext)
        if (!auth.state.value.signedIn) return Result.success()
        auth.refresh()
        val userId = auth.state.value.userId
        if (userId.isBlank()) return Result.success()

        val dao = CampusDatabase.getDatabase(applicationContext).campusDao()
        return runCatching {
            val pushSucceeded = pushTimeEntries(dao, userId) and pushCourses(dao, userId)
            pullTimeEntries(dao, userId)
            pullCourses(dao, userId)
            val tombstoneCutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
            dao.purgeOldTimeTombstones(tombstoneCutoff)
            dao.purgeOldCourseTombstones(tombstoneCutoff)
            if (pushSucceeded) Result.success() else Result.retry()
        }.getOrElse { Result.retry() }
    }

    private suspend fun pushTimeEntries(dao: CampusDao, userId: String): Boolean {
        var complete = true
        dao.getPendingTimeRecords(userId).forEach { local ->
            runCatching {
                if (local.deletedAt != null) {
                    if (local.remoteId == null) dao.purgeTimeRecord(local.id)
                    else {
                        val response = SupabaseClient.rpc(
                            "soft_delete_time_entry",
                            JSONObject().put("target_entry", local.remoteId).put("expected_version", (local.version - 1).coerceAtLeast(1)),
                        ).getOrThrow()
                        dao.updateTimeRecord(local.copy(version = response.optString("value").toIntOrNull() ?: local.version, syncState = "synced"))
                    }
                } else {
                    val response = SupabaseClient.rpc(
                        "sync_time_entry",
                        JSONObject()
                            .put("client_entry", local.clientId)
                            .put("entry_title", local.title)
                            .put("entry_category", local.category)
                            .put("entry_description", local.remark)
                            .put("entry_starts_at", Instant.ofEpochMilli(local.startTime).toString())
                            .put("entry_ends_at", Instant.ofEpochMilli(local.endTime).toString())
                            .put("client_version", local.version)
                            .put("client_updated_at", Instant.ofEpochMilli(local.updatedAt).toString()),
                    ).getOrThrow()
                    val remote = response.getJSONObject("entry")
                    dao.updateTimeRecord(
                        local.copy(
                            remoteId = remote.getString("id"),
                            clientId = remote.getString("client_id"),
                            version = remote.getInt("version"),
                            userId = userId,
                            syncState = if (response.optBoolean("conflict")) "conflict" else "synced",
                            updatedAt = remoteTime(remote, "updated_at"),
                        ),
                    )
                }
            }.onFailure {
                complete = false
                dao.updateTimeRecord(local.copy(syncState = "failed"))
            }
        }
        return complete
    }

    private suspend fun pushCourses(dao: CampusDao, userId: String): Boolean {
        var complete = true
        dao.getPendingCourseSchedules(userId).forEach { local ->
            runCatching {
                if (local.deletedAt != null) {
                    if (local.remoteId == null) dao.purgeCourseSchedule(local.id)
                    else {
                        val response = SupabaseClient.rpc(
                            "delete_course_schedule",
                            JSONObject().put("target_course", local.remoteId).put("expected_version", (local.version - 1).coerceAtLeast(1)),
                        ).getOrThrow()
                        dao.updateCourseSchedule(local.copy(version = response.optString("value").toIntOrNull() ?: local.version, syncState = "synced"))
                    }
                } else {
                    val response = SupabaseClient.rpc(
                        "sync_course_schedule",
                        JSONObject()
                            .put("client_course", local.clientId)
                            .put("course_name", local.name)
                            .put("course_weekday", local.weekday)
                            .put("course_start_minute", local.startMinute)
                            .put("course_end_minute", local.endMinute)
                            .put("course_location", local.location)
                            .put("course_teacher", local.teacher)
                            .put("course_weeks", local.weeks)
                            .put("course_source_hash", local.sourceHash)
                            .put("client_version", local.version)
                            .put("client_updated_at", Instant.ofEpochMilli(local.updatedAt).toString()),
                    ).getOrThrow()
                    val remote = response.getJSONObject("entry")
                    dao.updateCourseSchedule(
                        local.copy(
                            remoteId = remote.getString("id"),
                            clientId = remote.getString("client_id"),
                            version = remote.getInt("version"),
                            userId = userId,
                            syncState = if (response.optBoolean("conflict")) "conflict" else "synced",
                            updatedAt = remoteTime(remote, "updated_at"),
                        ),
                    )
                }
            }.onFailure {
                complete = false
                dao.updateCourseSchedule(local.copy(syncState = "failed"))
            }
        }
        return complete
    }

    private suspend fun pullTimeEntries(dao: CampusDao, userId: String) {
        val rows = SupabaseClient.restGet(
            "time_entries",
            mapOf(
                "select" to "id,user_id,client_id,title,category,description,starts_at,ends_at,version,updated_at,deleted_at",
                "user_id" to "eq.$userId",
                "order" to "updated_at.asc",
                "limit" to "1000",
            ),
        ).getOrThrow()
        repeat(rows.length()) { index ->
            val remote = rows.getJSONObject(index)
            val clientId = remote.getString("client_id")
            val existing = dao.getTimeRecordByClientId(clientId)
            if (!remote.isNull("deleted_at")) {
                if (existing?.syncState == "synced" && existing.deletedAt == null) dao.purgeTimeRecord(existing.id)
            } else if (existing == null) {
                dao.insertTimeRecord(remoteTimeEntity(remote, userId))
            } else if (existing.syncState == "synced") {
                dao.updateTimeRecord(remoteTimeEntity(remote, userId).copy(id = existing.id))
            }
        }
    }

    private suspend fun pullCourses(dao: CampusDao, userId: String) {
        val rows = SupabaseClient.restGet(
            "course_schedules",
            mapOf(
                "select" to "id,user_id,client_id,name,weekday,start_minute,end_minute,location,teacher,weeks,source_hash,version,updated_at,deleted_at",
                "user_id" to "eq.$userId",
                "order" to "updated_at.asc",
                "limit" to "1000",
            ),
        ).getOrThrow()
        repeat(rows.length()) { index ->
            val remote = rows.getJSONObject(index)
            val clientId = remote.getString("client_id")
            val existing = dao.getCourseByClientId(clientId) ?: dao.getCourseBySourceHash(remote.getString("source_hash"))
            if (!remote.isNull("deleted_at")) {
                if (existing?.syncState == "synced" && existing.deletedAt == null) dao.purgeCourseSchedule(existing.id)
            } else if (existing == null) {
                dao.insertCourseSchedules(listOf(remoteCourseEntity(remote, userId)))
            } else if (existing.syncState == "synced") {
                dao.updateCourseSchedule(remoteCourseEntity(remote, userId).copy(id = existing.id))
            }
        }
    }

    private fun remoteTimeEntity(item: JSONObject, userId: String) = TimeRecordEntity(
        title = item.getString("title"),
        category = item.getString("category"),
        startTime = remoteTime(item, "starts_at"),
        endTime = remoteTime(item, "ends_at"),
        durationMinutes = ((remoteTime(item, "ends_at") - remoteTime(item, "starts_at")) / 60_000L).coerceAtLeast(0),
        remark = item.optString("description"),
        userId = userId,
        clientId = item.getString("client_id"),
        remoteId = item.getString("id"),
        version = item.getInt("version"),
        syncState = "synced",
        updatedAt = remoteTime(item, "updated_at"),
    )

    private fun remoteCourseEntity(item: JSONObject, userId: String) = CourseScheduleEntity(
        name = item.getString("name"),
        weekday = item.getInt("weekday"),
        startMinute = item.getInt("start_minute"),
        endMinute = item.getInt("end_minute"),
        location = item.optString("location"),
        teacher = item.optString("teacher"),
        weeks = item.optString("weeks"),
        sourceHash = item.getString("source_hash"),
        userId = userId,
        clientId = item.getString("client_id"),
        remoteId = item.getString("id"),
        version = item.getInt("version"),
        syncState = "synced",
        updatedAt = remoteTime(item, "updated_at"),
    )

    private fun remoteTime(item: JSONObject, key: String): Long = OffsetDateTime.parse(item.getString(key)).toInstant().toEpochMilli()
}

object CampusSyncScheduler {
    private const val PERIODIC_WORK = "campusai-periodic-sync"
    private const val IMMEDIATE_WORK = "campusai-immediate-sync"
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<CampusSyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<CampusSyncWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.KEEP, request)
    }
}
