package com.campusai.features.time

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.campusai.core.database.CampusDao
import com.campusai.core.database.TimeRecordEntity
import com.campusai.core.database.DailyGoalSnapshotEntity
import com.campusai.core.model.DailyContributionCalculator
import com.campusai.core.model.TimeRecord
import com.campusai.core.model.CourseSchedule
import com.campusai.core.database.CourseScheduleEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.campusai.core.sync.CampusSyncScheduler

@OptIn(ExperimentalCoroutinesApi::class)
class TimeViewModel(private val dao: CampusDao, private val appContext: Context, initialUserId: String?) : ViewModel() {

    private val activeUser = MutableStateFlow(initialUserId?.takeIf { it.isNotBlank() } ?: "local_user")
    val activeUserId: StateFlow<String> = activeUser.asStateFlow()

    // Reactive complete record list mapped to domain layer
    val timeRecords: StateFlow<List<TimeRecord>> = activeUser.flatMapLatest { userId -> dao.getAllTimeRecordsFlow(userId, userId != "local_user") }
        .map { list -> list.map { it.toDomain() } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val dailyTargetSnapshots: StateFlow<Map<LocalDate, Long>> = activeUser.flatMapLatest { userId ->
        dao.getDailyGoalSnapshotsFlow(userId, userId != "local_user")
    }.map { snapshots ->
        // Local rows are shown alongside signed-in rows. Let the active account win if both own a
        // snapshot for the same date; today they share the same 240-minute default, and this keeps
        // the precedence deterministic once goals become configurable.
        val currentUser = activeUser.value
        buildMap {
            snapshots.filter { it.userId == "local_user" }.forEach { snapshot ->
                runCatching { LocalDate.parse(snapshot.localDate) }.getOrNull()?.let { date ->
                    put(date, snapshot.targetMinutes)
                }
            }
            snapshots.filter { it.userId == currentUser }.forEach { snapshot ->
                runCatching { LocalDate.parse(snapshot.localDate) }.getOrNull()?.let { date ->
                    put(date, snapshot.targetMinutes)
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val courses: StateFlow<List<CourseSchedule>> = activeUser.flatMapLatest { userId ->
        dao.getCourseSchedulesFlow(userId, userId != "local_user")
    }.map { rows -> rows.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isInserting = MutableStateFlow(false)
    val isInserting: StateFlow<Boolean> = _isInserting.asStateFlow()

    init {
        viewModelScope.launch {
            timeRecords.collectLatest { records ->
                val snapshots = records.mapNotNull { it.dailyGoalSnapshot() }.distinctBy {
                    it.userId to it.localDate
                }
                if (snapshots.isNotEmpty()) dao.insertDailyGoalSnapshots(snapshots)
            }
        }
    }

    fun setActiveUser(userId: String?) {
        activeUser.value = userId?.takeIf { it.isNotBlank() } ?: "local_user"
    }

    // Add record
    suspend fun addTimeRecord(
        title: String,
        category: String,
        startTime: Long,
        endTime: Long,
        remark: String,
        expectedOwner: String,
    ) {
        check(expectedOwner == activeUser.value) { "账号已切换，请重新打开记录" }
        check(_isInserting.compareAndSet(false, true)) { "正在保存记录，请稍候" }
        try {
            val durationMin = if (endTime > startTime) {
                (endTime - startTime) / (1000 * 60)
            } else {
                0L
            }
            val record = TimeRecord(
                title = title,
                category = category,
                startTime = startTime,
                endTime = endTime,
                durationMinutes = durationMin,
                remark = remark,
                userId = expectedOwner,
            )
            dao.insertTimeRecordWithSnapshot(TimeRecordEntity.fromDomain(record), record.dailyGoalSnapshot())
            // A committed local record must not be offered for duplicate retry if sync scheduling fails.
            runCatching { CampusSyncScheduler.enqueue(appContext) }
        } finally { _isInserting.value = false }
    }

    // Delete record
    fun deleteTimeRecord(id: Int) {
        viewModelScope.launch {
            dao.softDeleteTimeRecord(id)
        }
    }

    fun confirmDeleteTimeRecord() = CampusSyncScheduler.enqueue(appContext)

    fun undoDeleteTimeRecord(id: Int) {
        viewModelScope.launch {
            dao.undoDeleteTimeRecord(id)
            CampusSyncScheduler.enqueue(appContext)
        }
    }

    suspend fun editTimeRecord(id: Int, title: String, category: String, startTime: Long, endTime: Long, remark: String, expectedOwner: String) {
        check(expectedOwner == activeUser.value) { "账号已切换，请重新打开记录" }
        check(_isInserting.compareAndSet(false, true)) { "正在保存记录，请稍候" }
        try {
            val duration = ((endTime - startTime) / 60_000L).coerceAtLeast(0)
            check(dao.editOwnedTimeRecord(id, title, category, startTime, endTime, duration, remark, expectedOwner) == 1) {
                "记录已不可用，请重新打开后再试"
            }
            runCatching { CampusSyncScheduler.enqueue(appContext) }
        } finally { _isInserting.value = false }
    }

    fun deleteCourse(id: Int) {
        viewModelScope.launch {
            dao.softDeleteCourseSchedule(id)
            CampusSyncScheduler.enqueue(appContext)
        }
    }

    suspend fun importCourses(courses: List<CourseSchedule>, expectedOwner: String): CourseImportResult {
        check(expectedOwner == activeUser.value) { "账号已切换，请重新导入课程表" }
        val results = dao.importCourseSchedules(courses.map { CourseScheduleEntity.fromDomain(it, expectedOwner) })
        val inserted = results.count { it != -1L }
        // Match record saves: the committed import succeeds even if scheduling sync fails.
        if (inserted > 0) runCatching { CampusSyncScheduler.enqueue(appContext) }
        return CourseImportResult(inserted, results.size - inserted)
    }

    fun getStatsToday(records: List<TimeRecord>): Long = com.campusai.core.model.TimeRecordCalendar.inRange(records, "日").sumOf { it.durationMinutes }
    fun getStatsThisWeek(records: List<TimeRecord>): Long = com.campusai.core.model.TimeRecordCalendar.inRange(records, "周").sumOf { it.durationMinutes }
    fun getStatsThisMonth(records: List<TimeRecord>): Long = com.campusai.core.model.TimeRecordCalendar.inRange(records, "月").sumOf { it.durationMinutes }
    fun getStreakDays(records: List<TimeRecord>): Int = com.campusai.core.model.TimeRecordCalendar.streak(records)

}

data class CourseImportResult(val inserted: Int, val duplicates: Int)

private fun TimeRecord.dailyGoalSnapshot(zoneId: ZoneId = ZoneId.systemDefault()): DailyGoalSnapshotEntity? {
    if (durationMinutes <= 0L || endTime <= startTime) return null
    return DailyGoalSnapshotEntity(
        userId = userId,
        localDate = Instant.ofEpochMilli(endTime).atZone(zoneId).toLocalDate().toString(),
        targetMinutes = DailyContributionCalculator.DEFAULT_TARGET_MINUTES,
        createdAt = endTime,
    )
}

class TimeViewModelFactory(private val dao: CampusDao, private val appContext: Context, private val initialUserId: String?) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TimeViewModel::class.java)) {
            return TimeViewModel(dao, appContext.applicationContext, initialUserId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
