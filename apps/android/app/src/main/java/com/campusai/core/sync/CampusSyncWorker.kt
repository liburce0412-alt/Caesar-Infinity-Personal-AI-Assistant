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
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

class CampusSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private var sessionToken: String = ""

    override suspend fun doWork(): Result {
        if (!SupabaseClient.isConfigured()) return Result.success()
        val auth = AuthRepository.getInstance(applicationContext)
        if (!auth.state.value.signedIn) return Result.success()
        if (!auth.refresh()) return if (auth.state.value.signedIn) Result.retry() else Result.success()
        val userId = auth.state.value.userId
        if (userId.isBlank()) return Result.success()

        sessionToken = SupabaseClient.userJwt
        val dao = CampusDatabase.getDatabase(applicationContext).campusDao()
        return runCatching {
            dao.claimGuestTimeRecords(userId)
            dao.claimGuestCourses(userId)
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
                    if (local.remoteId == null) {
                        // A previous upload may have committed even if its response was lost.
                        // Check the immutable client ID before discarding its local deletion.
                        val remote = findRemote("time_entries", userId, local.clientId)
                        if (remote == null) complete = dao.purgeUnsentTime(local) && complete
                        else {
                            val deleted = !remote.isNull("deleted_at")
                            complete = dao.acknowledgeTime(local, local.copy(remoteId = remote.getString("id"),
                                clientId = remote.getString("client_id"), version = remote.getInt("version") + if (deleted) 0 else 1,
                                syncState = if (deleted) "synced" else "pending")) && complete
                            if (!deleted) complete = false
                        }
                    }
                    else {
                        val response = syncRpc(
                            "soft_delete_time_entry",
                            JSONObject().put("target_entry", local.remoteId).put("expected_version", (local.version - 1).coerceAtLeast(1)),
                        ).getOrThrow()
                        complete = dao.acknowledgeTime(local, local.copy(version = response.optString("value").toIntOrNull() ?: local.version, syncState = "synced")) && complete
                    }
                } else {
                    val response = syncRpc(
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
                    if (response.optBoolean("conflict")) {
                        dao.applyRemoteTime(remoteTimeEntity(remote, userId), local)
                        complete = false // A rescued local copy needs a subsequent push.
                    } else {
                        complete = dao.acknowledgeTime(local, local.copy(
                            remoteId = remote.getString("id"), clientId = remote.getString("client_id"),
                            version = remote.getInt("version"), syncState = "synced",
                            updatedAt = remoteTime(remote, "updated_at"),
                        )) && complete
                    }
                }
            }.onFailure {
                complete = false
                dao.failTime(local)
            }
        }
        return complete
    }

    private suspend fun pushCourses(dao: CampusDao, userId: String): Boolean {
        var complete = true
        dao.getPendingCourseSchedules(userId).forEach { local ->
            runCatching {
                if (local.deletedAt != null) {
                    if (local.remoteId == null) {
                        // A previous upload may have committed even if its response was lost.
                        // Check the immutable client ID before discarding its local deletion.
                        val remote = findRemote("course_schedules", userId, local.clientId, local.sourceHash)
                        if (remote == null) complete = dao.purgeUnsentCourse(local) && complete
                        else {
                            val deleted = !remote.isNull("deleted_at")
                            complete = dao.acknowledgeCourse(local, local.copy(remoteId = remote.getString("id"),
                                clientId = remote.getString("client_id"), version = remote.getInt("version") + if (deleted) 0 else 1,
                                syncState = if (deleted) "synced" else "pending")) && complete
                            if (!deleted) complete = false
                        }
                    }
                    else {
                        val response = syncRpc(
                            "delete_course_schedule",
                            JSONObject().put("target_course", local.remoteId).put("expected_version", (local.version - 1).coerceAtLeast(1)),
                        ).getOrThrow()
                        complete = dao.acknowledgeCourse(local, local.copy(version = response.optString("value").toIntOrNull() ?: local.version, syncState = "synced")) && complete
                    }
                } else {
                    val response = syncRpc(
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
                            .apply { if (local.periodStart > 0) {
                                put("course_period_start", local.periodStart)
                                put("course_period_end", local.periodEnd)
                                put("course_period_start_times", local.periodStartTimes)
                            } }
                            .put("client_version", local.version)
                            .put("client_updated_at", Instant.ofEpochMilli(local.updatedAt).toString()),
                    ).getOrThrow()
                    val remote = response.getJSONObject("entry")
                    if (response.optBoolean("conflict")) {
                        dao.applyRemoteCourse(remoteCourseEntity(remote, userId), local)
                        complete = false // A rescued local copy needs a subsequent push.
                    } else {
                        complete = dao.acknowledgeCourse(local, local.copy(
                            remoteId = remote.getString("id"), clientId = remote.getString("client_id"),
                            version = remote.getInt("version"), syncState = "synced",
                            updatedAt = remoteTime(remote, "updated_at"),
                        )) && complete
                    }
                }
            }.onFailure {
                complete = false
                dao.failCourse(local)
            }
        }
        return complete
    }

    private suspend fun findRemote(table: String, userId: String, clientId: String, sourceHash: String? = null): JSONObject? {
        suspend fun find(field: String, value: String): JSONObject? {
            val rows = SupabaseClient.restGet(table, mapOf("select" to "id,client_id,version,deleted_at",
                "user_id" to "eq.$userId", field to "eq.$value", "limit" to "1"), sessionToken = sessionToken).getOrThrow()
            check(sessionToken == SupabaseClient.userJwt) { "登录状态已变化" }
            return rows.optJSONObject(0)
        }
        return find("client_id", clientId) ?: sourceHash?.let { find("source_hash", it) }
    }

    private suspend fun syncRpc(name: String, payload: JSONObject): kotlin.Result<JSONObject> {
        check(sessionToken == SupabaseClient.userJwt) { "登录状态已变化" }
        val result = SupabaseClient.rpc(name, payload, sessionToken)
        check(sessionToken == SupabaseClient.userJwt) { "登录状态已变化" }
        return result
    }

    private suspend fun pullTimeEntries(dao: CampusDao, userId: String) = pullPages("time_entries", userId) {
        dao.applyRemoteTime(remoteTimeEntity(it, userId))
    }

    private suspend fun pullCourses(dao: CampusDao, userId: String) = pullPages("course_schedules", userId) {
        dao.applyRemoteCourse(remoteCourseEntity(it, userId))
    }

    private suspend fun pullPages(table: String, userId: String, apply: suspend (JSONObject) -> Unit) {
        walkSyncPages(fetch = { cursor ->
            check(sessionToken == SupabaseClient.userJwt) { "登录状态已变化" }
            val rows = SupabaseClient.restGet(table, syncPageParameters(userId, cursor), sessionToken = sessionToken).getOrThrow()
            check(sessionToken == SupabaseClient.userJwt) { "登录状态已变化" }
            List(rows.length()) { rows.getJSONObject(it) }
        }, apply = apply)
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
        deletedAt = if (item.isNull("deleted_at")) null else remoteTime(item, "deleted_at"),
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
        periodStart = item.optInt("period_start"),
        periodEnd = item.optInt("period_end"),
        periodStartTimes = item.optString("period_start_times", ""),
        userId = userId,
        clientId = item.getString("client_id"),
        remoteId = item.getString("id"),
        version = item.getInt("version"),
        syncState = "synced",
        updatedAt = remoteTime(item, "updated_at"),
        deletedAt = if (item.isNull("deleted_at")) null else remoteTime(item, "deleted_at"),
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
