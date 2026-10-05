package com.campusai.core.database

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.campusai.core.model.TimeRecord
import com.campusai.core.model.Goods
import com.campusai.core.model.Friend
import com.campusai.core.model.ChatMessage
import com.campusai.core.model.UserMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.campusai.core.model.CourseSchedule
import com.campusai.core.model.AiMode
import com.campusai.core.model.AiProvider
import com.campusai.core.model.AiReport
import com.campusai.core.model.DailyGreeting
import java.util.UUID

// ==========================================
// Room Entities
// ==========================================

@Entity(tableName = "time_records", indices = [Index(value = ["userId", "clientId"], unique = true)])
data class TimeRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val category: String,
    val startTime: Long,
    val endTime: Long,
    val durationMinutes: Long,
    val remark: String,
    val userId: String,
    val clientId: String = UUID.randomUUID().toString(),
    val remoteId: String? = null,
    val version: Int = 1,
    val syncState: String = "pending",
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
) {
    fun toDomain() = TimeRecord(id, title, category, startTime, endTime, durationMinutes, remark, userId)
    
    companion object {
        fun fromDomain(d: TimeRecord) = TimeRecordEntity(
            id = d.id,
            title = d.title,
            category = d.category,
            startTime = d.startTime,
            endTime = d.endTime,
            durationMinutes = d.durationMinutes,
            remark = d.remark,
            userId = d.userId
        )
    }
}

@Entity(tableName = "goods")
data class GoodsEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val description: String,
    val imageUrl: String,
    val price: Double,
    val sellerName: String,
    val sellerId: String,
    val createdAt: Long,
    val isFavorite: Boolean
) {
    fun toDomain() = Goods(id, title, description, imageUrl, price, sellerName, sellerId, createdAt, isFavorite)
    
    companion object {
        fun fromDomain(d: Goods) = GoodsEntity(
            id = d.id,
            title = d.title,
            description = d.description,
            imageUrl = d.imageUrl,
            price = d.price,
            sellerName = d.sellerName,
            sellerId = d.sellerId,
            createdAt = d.createdAt,
            isFavorite = d.isFavorite
        )
    }
}

@Entity(tableName = "friends")
data class FriendEntity(
    @PrimaryKey val id: String,
    val nickname: String,
    val avatarUrl: String,
    val bio: String,
    val status: String
) {
    fun toDomain() = Friend(id, nickname, avatarUrl, bio, status)
    
    companion object {
        fun fromDomain(d: Friend) = FriendEntity(
            id = d.id,
            nickname = d.nickname,
            avatarUrl = d.avatarUrl,
            bio = d.bio,
            status = d.status
        )
    }
}

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val friendId: String,
    val senderId: String,
    val content: String,
    val timestamp: Long,
    val isRead: Boolean
) {
    fun toDomain() = ChatMessage(id, friendId, senderId, content, timestamp, isRead)
    
    companion object {
        fun fromDomain(d: ChatMessage) = ChatMessageEntity(
            id = d.id,
            friendId = d.friendId,
            senderId = d.senderId,
            content = d.content,
            timestamp = d.timestamp,
            isRead = d.isRead
        )
    }
}

@Entity(tableName = "user_messages")
data class UserMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val profileUserId: String,
    val authorName: String,
    val authorAvatar: String,
    val content: String,
    val timestamp: Long,
    val isApproved: Boolean
) {
    fun toDomain() = UserMessage(id, profileUserId, authorName, authorAvatar, content, timestamp, isApproved)
    
    companion object {
        fun fromDomain(d: UserMessage) = UserMessageEntity(
            id = d.id,
            profileUserId = d.profileUserId,
            authorName = d.authorName,
            authorAvatar = d.authorAvatar,
            content = d.content,
            timestamp = d.timestamp,
            isApproved = d.isApproved
        )
    }
}

@Entity(tableName = "course_schedules", indices = [Index(value = ["userId", "sourceHash"], unique = true), Index(value = ["userId", "clientId"], unique = true)])
data class CourseScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val weekday: Int,
    val startMinute: Int,
    val endMinute: Int,
    val location: String,
    val teacher: String,
    val weeks: String,
    val sourceHash: String,
    val userId: String = "local_user",
    val clientId: String = UUID.randomUUID().toString(),
    val remoteId: String? = null,
    val version: Int = 1,
    val syncState: String = "pending",
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val periodStart: Int = 0,
    @ColumnInfo(defaultValue = "0") val periodEnd: Int = 0,
    @ColumnInfo(defaultValue = "''") val periodStartTimes: String = "",
) {
    fun toDomain() = CourseSchedule(id, name, weekday, startMinute, endMinute, location, teacher, weeks, sourceHash, periodStart, periodEnd, periodStartTimes)
    companion object {
        fun fromDomain(value: CourseSchedule, userId: String = "local_user") = CourseScheduleEntity(id=value.id, name=value.name, weekday=value.weekday, startMinute=value.startMinute, endMinute=value.endMinute, location=value.location, teacher=value.teacher, weeks=value.weeks, sourceHash=value.sourceHash, userId=userId, periodStart=value.periodStart, periodEnd=value.periodEnd, periodStartTimes=value.periodStartTimes)
    }
}

@Entity(tableName = "ai_reports")
data class AiReportEntity(
    @PrimaryKey val id: String,
    val provider: String,
    val mode: String,
    val model: String,
    val executionEngine: String,
    val requestId: String,
    val title: String,
    val summary: String,
    val messagesJson: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toDomain() = AiReport(
        id = id,
        provider = provider.toAiProvider(),
        mode = mode.toAiMode(),
        model = model,
        executionEngine = executionEngine,
        requestId = requestId,
        title = title,
        summary = summary,
        messagesJson = messagesJson,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
    companion object {
        fun fromDomain(value: AiReport) = AiReportEntity(
            value.id,
            value.provider.name,
            value.mode.name,
            value.model,
            value.executionEngine,
            value.requestId,
            value.title,
            value.summary,
            value.messagesJson,
            value.createdAt,
            value.updatedAt,
        )
    }
}

@Entity(tableName = "daily_greetings")
data class DailyGreetingEntity(
    @PrimaryKey val id: String,
    val localDate: String,
    val text: String,
    val provider: String,
    val generatedAt: Long,
) {
    fun toDomain() = DailyGreeting(localDate, text, provider.toAiProvider(), generatedAt)
}

@Entity(tableName = "agent_memories", indices = [Index(value = ["confirmedAt", "expiresAt"])])
data class AgentMemoryEntity(
    @PrimaryKey val id: String,
    val type: String,
    val content: String,
    val source: String,
    val confidence: Double,
    val createdAt: Long,
    val confirmedAt: Long?,
    val expiresAt: Long?,
)

@Entity(tableName = "agent_traces", indices = [Index(value = ["sessionId", "createdAt"])])
data class AgentTraceEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val kind: String,
    val name: String,
    val durationMs: Long,
    val success: Boolean,
    val errorCode: String?,
    val createdAt: Long,
)

@Entity(tableName = "agent_actions")
data class AgentActionEntity(
    @PrimaryKey val idempotencyKey: String,
    val toolName: String,
    val argumentsHash: String,
    val status: String,
    val resultJson: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "health_summary_cache")
data class HealthSummaryCacheEntity(
    @PrimaryKey val windowKey: String,
    val summaryJson: String,
    val source: String,
    val observedAt: Long,
    val lastSyncAt: Long?,
)

@Entity(tableName = "daily_goal_snapshots", primaryKeys = ["userId", "localDate"])
data class DailyGoalSnapshotEntity(
    val userId: String,
    val localDate: String,
    val targetMinutes: Long,
    val createdAt: Long = System.currentTimeMillis(),
)

private fun String.toAiMode() = runCatching { AiMode.valueOf(this) }.getOrDefault(AiMode.FAST)
private fun String.toAiProvider() = runCatching { AiProvider.valueOf(this) }.getOrDefault(AiProvider.AUTO)

// ==========================================
// Room DAOs
// ==========================================

@Dao
interface CampusDao {
    // Time Records
    @Query("SELECT * FROM time_records WHERE deletedAt IS NULL AND (userId = :activeUser OR (:includeLocal = 1 AND userId = 'local_user')) ORDER BY startTime DESC")
    fun getAllTimeRecordsFlow(activeUser: String, includeLocal: Boolean): Flow<List<TimeRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTimeRecord(entity: TimeRecordEntity): Long

    @Transaction
    suspend fun insertTimeRecordWithSnapshot(entity: TimeRecordEntity, snapshot: DailyGoalSnapshotEntity?) {
        insertTimeRecord(entity)
        snapshot?.let { insertDailyGoalSnapshots(listOf(it)) }
    }

    @Query("UPDATE time_records SET deletedAt = :deletedAt, updatedAt = :deletedAt, version = version + 1, syncState = 'pending' WHERE id = :id AND deletedAt IS NULL")
    suspend fun softDeleteTimeRecord(id: Int, deletedAt: Long = System.currentTimeMillis())

    @Query("UPDATE time_records SET deletedAt = NULL, updatedAt = :updatedAt, version = version + 1, syncState = 'pending' WHERE id = :id AND deletedAt IS NOT NULL")
    suspend fun undoDeleteTimeRecord(id: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE time_records SET title = :title, category = :category, startTime = :startTime, endTime = :endTime, durationMinutes = :durationMinutes, remark = :remark, updatedAt = :updatedAt, version = version + 1, syncState = 'pending' WHERE id = :id AND deletedAt IS NULL")
    suspend fun editTimeRecord(id: Int, title: String, category: String, startTime: Long, endTime: Long, durationMinutes: Long, remark: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE time_records SET title = :title, category = :category, startTime = :startTime, endTime = :endTime, durationMinutes = :durationMinutes, remark = :remark, updatedAt = :updatedAt, version = version + 1, syncState = 'pending' WHERE id = :id AND deletedAt IS NULL AND (userId = :expectedOwner OR userId = 'local_user')")
    suspend fun editOwnedTimeRecord(id: Int, title: String, category: String, startTime: Long, endTime: Long, durationMinutes: Long, remark: String, expectedOwner: String, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("SELECT * FROM time_records WHERE syncState IN ('pending', 'failed') AND userId = :activeUser ORDER BY updatedAt")
    suspend fun getPendingTimeRecords(activeUser: String): List<TimeRecordEntity>

    @Query("SELECT * FROM time_records WHERE clientId = :clientId AND userId = :userId LIMIT 1")
    suspend fun getTimeRecordByClientId(clientId: String, userId: String): TimeRecordEntity?

    @Update
    suspend fun updateTimeRecord(entity: TimeRecordEntity)

    @Query("DELETE FROM time_records WHERE id = :id")
    suspend fun purgeTimeRecord(id: Int)

    @Query("DELETE FROM time_records WHERE deletedAt IS NOT NULL AND deletedAt < :before AND syncState = 'synced'")
    suspend fun purgeOldTimeTombstones(before: Long)

    // Claim guest rows once, before any network request. Same-hash account rows win;
    // conflicting guest rows remain local and are never uploaded under another owner.
    @Query("UPDATE OR IGNORE time_records SET userId = :userId WHERE userId = 'local_user'")
    suspend fun claimGuestTimeRecords(userId: String)

    @Query("UPDATE OR IGNORE course_schedules SET userId = :userId WHERE userId = 'local_user'")
    suspend fun claimGuestCourses(userId: String)

    @Query("SELECT * FROM time_records WHERE id = :id")
    suspend fun timeRecordById(id: Int): TimeRecordEntity?

    @Query("SELECT * FROM course_schedules WHERE id = :id")
    suspend fun courseById(id: Int): CourseScheduleEntity?

    @Transaction
    suspend fun editOwnedCourse(course: CourseSchedule, expectedOwner: String): Boolean {
        val existing = courseById(course.id) ?: return false
        if (existing.deletedAt != null || existing.userId !in setOf(expectedOwner, "local_user")) return false
        updateCourseSchedule(existing.copy(name = course.name, weekday = course.weekday,
            startMinute = course.startMinute, endMinute = course.endMinute, location = course.location,
            teacher = course.teacher, weeks = course.weeks, periodStart = course.periodStart,
            periodEnd = course.periodEnd, periodStartTimes = course.periodStartTimes,
            version = existing.version + 1, updatedAt = System.currentTimeMillis(), syncState = "pending"))
        return true
    }

    @Transaction
    suspend fun importCourseSchedules(entities: List<CourseScheduleEntity>): List<Long> = entities.map { incoming ->
        val previous = getCourseBySourceHash(incoming.sourceHash, incoming.userId)
        if (previous?.deletedAt != null) {
            updateCourseSchedule(incoming.copy(id = previous.id, clientId = previous.clientId,
                remoteId = previous.remoteId, version = previous.version + 1))
            previous.id.toLong()
        } else insertCourseSchedules(listOf(incoming)).single()
    }

    @Transaction
    suspend fun acknowledgeTime(snapshot: TimeRecordEntity, acknowledged: TimeRecordEntity): Boolean {
        val current = timeRecordById(snapshot.id) ?: return false
        if (current.userId != snapshot.userId) return false
        if (current == snapshot) {
            updateTimeRecord(acknowledged)
            return true
        }
        // A request completed after an edit/delete/undo. Retain every current payload field.
        updateTimeRecord(current.copy(remoteId = acknowledged.remoteId, clientId = acknowledged.clientId,
            version = maxOf(current.version, acknowledged.version + 1), syncState = "pending"))
        return false
    }

    @Transaction
    suspend fun failTime(snapshot: TimeRecordEntity) {
        if (timeRecordById(snapshot.id) == snapshot) updateTimeRecord(snapshot.copy(syncState = "failed"))
    }

    @Transaction
    suspend fun purgeUnsentTime(snapshot: TimeRecordEntity): Boolean {
        if (timeRecordById(snapshot.id) != snapshot) return false
        purgeTimeRecord(snapshot.id)
        return true
    }

    @Transaction
    suspend fun acknowledgeCourse(snapshot: CourseScheduleEntity, acknowledged: CourseScheduleEntity): Boolean {
        val current = courseById(snapshot.id) ?: return false
        if (current.userId != snapshot.userId) return false
        if (current == snapshot) {
            updateCourseSchedule(acknowledged)
            return true
        }
        // A request completed after an edit/delete/undo. Retain every current payload field.
        updateCourseSchedule(current.copy(remoteId = acknowledged.remoteId, clientId = acknowledged.clientId,
            version = maxOf(current.version, acknowledged.version + 1), syncState = "pending"))
        return false
    }

    @Transaction
    suspend fun failCourse(snapshot: CourseScheduleEntity) {
        if (courseById(snapshot.id) == snapshot) updateCourseSchedule(snapshot.copy(syncState = "failed"))
    }

    @Transaction
    suspend fun purgeUnsentCourse(snapshot: CourseScheduleEntity): Boolean {
        if (courseById(snapshot.id) != snapshot) return false
        purgeCourseSchedule(snapshot.id)
        return true
    }

    @Transaction
    suspend fun applyRemoteTime(remote: TimeRecordEntity, conflictedSnapshot: TimeRecordEntity? = null) {
        val current = conflictedSnapshot?.let { timeRecordById(it.id) }
            ?: getTimeRecordByClientId(remote.clientId, remote.userId)
        if (current != null && current.userId != remote.userId) return
        if (current == null) {
            insertTimeRecord(remote)
        } else if (current.deletedAt != null && remote.deletedAt != null) {
            updateTimeRecord(remote.copy(id = current.id))
        } else if (conflictedSnapshot != null || current.syncState == "conflict" ||
            (current.deletedAt != null && current.syncState == "failed" && remote.version >= current.version)) {
            // Keep both: the canonical server row and a visible, independently syncable local copy.
            // A local deletion has no payload to rescue; the newer server row wins.
            if (current.deletedAt == null) {
                val rescued = current.copy(id = 0, title = current.title.take(108) + "（同步冲突副本）",
                    clientId = UUID.randomUUID().toString(), remoteId = null,
                    version = 1, syncState = "pending", updatedAt = System.currentTimeMillis())
                insertTimeRecord(rescued)
            }
            updateTimeRecord(remote.copy(id = current.id))
        } else if (current.deletedAt != null && current.syncState == "failed") {
            // Several offline edits may have advanced the local counter more than the
            // server. Rebase a pending delete onto the version observed by this pull.
            updateTimeRecord(current.copy(remoteId = remote.remoteId, clientId = remote.clientId,
                version = remote.version + 1, syncState = "pending"))
        } else if (current.syncState == "synced" && remote.version >= current.version) {
            updateTimeRecord(remote.copy(id = current.id))
        }
    }

    @Transaction
    suspend fun applyRemoteCourse(remote: CourseScheduleEntity, conflictedSnapshot: CourseScheduleEntity? = null) {
        val current = conflictedSnapshot?.let { courseById(it.id) }
            ?: getCourseByClientId(remote.clientId, remote.userId) ?: getCourseBySourceHash(remote.sourceHash, remote.userId)
        if (current != null && current.userId != remote.userId) return
        if (current == null) {
            insertCourseSchedules(listOf(remote))
        } else if (current.deletedAt != null && remote.deletedAt != null) {
            updateCourseSchedule(remote.copy(id = current.id))
        } else if (conflictedSnapshot != null || current.syncState == "conflict" ||
            (current.deletedAt != null && current.syncState == "failed" && remote.version >= current.version)) {
            // Keep both: the canonical server row and a visible, independently syncable local copy.
            // A local deletion has no payload to rescue; the newer server row wins.
            if (current.deletedAt == null) {
                val rescued = current.copy(id = 0, name = current.name.take(148) + "（同步冲突副本）", sourceHash = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", ""),
                    clientId = UUID.randomUUID().toString(), remoteId = null,
                    version = 1, syncState = "pending", updatedAt = System.currentTimeMillis())
                insertCourseSchedules(listOf(rescued))
            }
            updateCourseSchedule(remote.copy(id = current.id))
        } else if (current.deletedAt != null && current.syncState == "failed") {
            // Several offline edits may have advanced the local counter more than the
            // server. Rebase a pending delete onto the version observed by this pull.
            updateCourseSchedule(current.copy(remoteId = remote.remoteId, clientId = remote.clientId,
                version = remote.version + 1, syncState = "pending"))
        } else if (current.syncState == "synced" && remote.version >= current.version) {
            updateCourseSchedule(remote.copy(id = current.id))
        }
    }

    // Goods Market
    @Query("SELECT * FROM goods ORDER BY createdAt DESC")
    fun getAllGoodsFlow(): Flow<List<GoodsEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGoods(entity: GoodsEntity): Long

    @Query("DELETE FROM goods WHERE id = :id")
    suspend fun deleteGoodsById(id: Int)

    @Query("UPDATE goods SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateGoodsFavorite(id: Int, isFavorite: Boolean)

    // Friends List
    @Query("SELECT * FROM friends")
    fun getAllFriendsFlow(): Flow<List<FriendEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFriend(entity: FriendEntity)

    @Query("DELETE FROM friends WHERE id = :id")
    suspend fun deleteFriendById(id: String)

    // Chats Messages
    @Query("SELECT * FROM chat_messages WHERE friendId = :friendId ORDER BY timestamp ASC")
    fun getMessagesForFriendFlow(friendId: String): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatMessage(entity: ChatMessageEntity): Long

    @Query("UPDATE chat_messages SET isRead = 1 WHERE friendId = :friendId AND senderId = :friendId")
    suspend fun markMessagesAsRead(friendId: String)

    // Message Board Walls
    @Query("SELECT * FROM user_messages WHERE profileUserId = :profileUserId ORDER BY timestamp DESC")
    fun getMessagesForProfileFlow(profileUserId: String): Flow<List<UserMessageEntity>>

    @Query("SELECT * FROM user_messages ORDER BY timestamp DESC")
    fun getAllMessagesFlow(): Flow<List<UserMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserMessage(entity: UserMessageEntity): Long

    @Query("DELETE FROM user_messages WHERE id = :id")
    suspend fun deleteUserMessageById(id: Int)

    @Query("UPDATE user_messages SET isApproved = :isApproved WHERE id = :id")
    suspend fun updateUserMessageApproval(id: Int, isApproved: Boolean)

    @Query("SELECT * FROM course_schedules WHERE deletedAt IS NULL AND (userId = :activeUser OR (:includeLocal = 1 AND userId = 'local_user')) ORDER BY weekday, startMinute")
    fun getCourseSchedulesFlow(activeUser: String, includeLocal: Boolean): Flow<List<CourseScheduleEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCourseSchedules(entities: List<CourseScheduleEntity>): List<Long>

    @Delete
    suspend fun hardDeleteCourseSchedule(entity: CourseScheduleEntity)

    @Query("UPDATE course_schedules SET deletedAt = :deletedAt, updatedAt = :deletedAt, version = version + 1, syncState = 'pending' WHERE id = :id AND deletedAt IS NULL")
    suspend fun softDeleteCourseSchedule(id: Int, deletedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM course_schedules WHERE syncState IN ('pending', 'failed') AND userId = :activeUser ORDER BY updatedAt")
    suspend fun getPendingCourseSchedules(activeUser: String): List<CourseScheduleEntity>

    @Query("SELECT * FROM course_schedules WHERE clientId = :clientId AND userId = :userId LIMIT 1")
    suspend fun getCourseByClientId(clientId: String, userId: String): CourseScheduleEntity?

    @Query("SELECT * FROM course_schedules WHERE sourceHash = :sourceHash AND userId = :userId LIMIT 1")
    suspend fun getCourseBySourceHash(sourceHash: String, userId: String): CourseScheduleEntity?

    @Update
    suspend fun updateCourseSchedule(entity: CourseScheduleEntity)

    @Query("DELETE FROM course_schedules WHERE id = :id")
    suspend fun purgeCourseSchedule(id: Int)

    @Query("DELETE FROM course_schedules WHERE deletedAt IS NOT NULL AND deletedAt < :before AND syncState = 'synced'")
    suspend fun purgeOldCourseTombstones(before: Long)

    @Query("SELECT * FROM ai_reports ORDER BY updatedAt DESC")
    fun getAiReportsFlow(): Flow<List<AiReportEntity>>

    @Query("SELECT * FROM ai_reports WHERE id = :id LIMIT 1")
    suspend fun getAiReport(id: String): AiReportEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAiReport(entity: AiReportEntity)

    @Query("DELETE FROM ai_reports WHERE id = :id")
    suspend fun deleteAiReport(id: String)

    @Query("SELECT * FROM daily_greetings WHERE id = :id LIMIT 1")
    suspend fun getDailyGreeting(id: String): DailyGreetingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyGreeting(entity: DailyGreetingEntity)

    @Query("SELECT * FROM agent_memories WHERE confirmedAt IS NOT NULL AND (expiresAt IS NULL OR expiresAt > :now) ORDER BY confirmedAt DESC LIMIT :limit")
    suspend fun getConfirmedAgentMemories(now: Long, limit: Int = 24): List<AgentMemoryEntity>

    @Query("SELECT * FROM agent_memories ORDER BY createdAt DESC")
    fun getAgentMemoriesFlow(): Flow<List<AgentMemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgentMemory(entity: AgentMemoryEntity)

    @Query("UPDATE agent_memories SET confirmedAt = :confirmedAt WHERE id = :id AND confirmedAt IS NULL")
    suspend fun confirmAgentMemory(id: String, confirmedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE agent_memories SET content = :content WHERE id = :id")
    suspend fun updateAgentMemoryContent(id: String, content: String): Int

    @Query("DELETE FROM agent_memories WHERE id = :id")
    suspend fun deleteAgentMemory(id: String)

    @Query("DELETE FROM agent_memories")
    suspend fun deleteAllAgentMemories()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgentTrace(entity: AgentTraceEntity)

    @Query("SELECT * FROM agent_traces ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getAgentTraces(limit: Int = 200): List<AgentTraceEntity>

    @Query("DELETE FROM agent_traces")
    suspend fun deleteAllAgentTraces()

    @Query("SELECT * FROM agent_actions WHERE idempotencyKey = :key LIMIT 1")
    suspend fun getAgentAction(key: String): AgentActionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgentAction(entity: AgentActionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAgentActionIfAbsent(entity: AgentActionEntity): Long

    @Query("""
        UPDATE agent_actions
        SET toolName = :toolName, argumentsHash = :argumentsHash, status = 'running', resultJson = NULL, updatedAt = :updatedAt
        WHERE idempotencyKey = :key AND status = 'failed'
    """)
    suspend fun restartFailedAgentAction(key: String, toolName: String, argumentsHash: String, updatedAt: Long): Int

    @Query("""
        UPDATE agent_actions
        SET status = 'completed', resultJson = :resultJson, updatedAt = :updatedAt
        WHERE idempotencyKey = :key AND status = 'running'
    """)
    suspend fun completeRunningAgentAction(key: String, resultJson: String, updatedAt: Long): Int

    @Query("""
        UPDATE agent_actions
        SET status = 'failed', resultJson = NULL, updatedAt = :updatedAt
        WHERE idempotencyKey = :key AND status = 'running'
    """)
    suspend fun failRunningAgentAction(key: String, updatedAt: Long): Int

    @Query("SELECT * FROM health_summary_cache WHERE windowKey = :windowKey LIMIT 1")
    suspend fun getHealthSummaryCache(windowKey: String): HealthSummaryCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHealthSummaryCache(entity: HealthSummaryCacheEntity)

    @Query("SELECT * FROM daily_goal_snapshots WHERE userId = :activeUser OR (:includeLocal = 1 AND userId = 'local_user') ORDER BY localDate")
    fun getDailyGoalSnapshotsFlow(activeUser: String, includeLocal: Boolean): Flow<List<DailyGoalSnapshotEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDailyGoalSnapshots(entities: List<DailyGoalSnapshotEntity>): List<Long>
}

// ==========================================
// Database Setup
// ==========================================

@Database(
    entities = [
        TimeRecordEntity::class,
        GoodsEntity::class,
        FriendEntity::class,
        ChatMessageEntity::class,
        UserMessageEntity::class,
        CourseScheduleEntity::class,
        AiReportEntity::class,
        DailyGreetingEntity::class,
        AgentMemoryEntity::class,
        AgentTraceEntity::class,
        AgentActionEntity::class,
        HealthSummaryCacheEntity::class,
        DailyGoalSnapshotEntity::class,
    ],
    version = 10,
    exportSchema = true
)
abstract class CampusDatabase : RoomDatabase() {
    abstract fun campusDao(): CampusDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `course_schedules` (
                      `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      `name` TEXT NOT NULL,
                      `weekday` INTEGER NOT NULL,
                      `startMinute` INTEGER NOT NULL,
                      `endMinute` INTEGER NOT NULL,
                      `location` TEXT NOT NULL,
                      `teacher` TEXT NOT NULL,
                      `weeks` TEXT NOT NULL,
                      `sourceHash` TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_course_schedules_sourceHash` ON `course_schedules` (`sourceHash`)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `ai_reports` (
                      `id` TEXT NOT NULL,
                      `mode` TEXT NOT NULL,
                      `title` TEXT NOT NULL,
                      `summary` TEXT NOT NULL,
                      `messagesJson` TEXT NOT NULL,
                      `createdAt` INTEGER NOT NULL,
                      PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `time_records_new` (
                      `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `category` TEXT NOT NULL,
                      `startTime` INTEGER NOT NULL, `endTime` INTEGER NOT NULL, `durationMinutes` INTEGER NOT NULL,
                      `remark` TEXT NOT NULL, `userId` TEXT NOT NULL, `clientId` TEXT NOT NULL, `remoteId` TEXT,
                      `version` INTEGER NOT NULL, `syncState` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO `time_records_new`
                    (`id`,`title`,`category`,`startTime`,`endTime`,`durationMinutes`,`remark`,`userId`,`clientId`,`remoteId`,`version`,`syncState`,`updatedAt`,`deletedAt`)
                    SELECT `id`,`title`,`category`,`startTime`,`endTime`,`durationMinutes`,`remark`,`userId`,lower(
                      hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || substr(hex(randomblob(2)),2) ||
                      '-' || substr('89ab', abs(random()) % 4 + 1, 1) || substr(hex(randomblob(2)),2) || '-' || hex(randomblob(6))
                    ),NULL,1,'pending',`endTime`,NULL FROM `time_records`
                """.trimIndent())
                db.execSQL("DROP TABLE `time_records`")
                db.execSQL("ALTER TABLE `time_records_new` RENAME TO `time_records`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_time_records_clientId` ON `time_records` (`clientId`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `course_schedules_new` (
                      `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `weekday` INTEGER NOT NULL,
                      `startMinute` INTEGER NOT NULL, `endMinute` INTEGER NOT NULL, `location` TEXT NOT NULL,
                      `teacher` TEXT NOT NULL, `weeks` TEXT NOT NULL, `sourceHash` TEXT NOT NULL, `userId` TEXT NOT NULL, `clientId` TEXT NOT NULL,
                      `remoteId` TEXT, `version` INTEGER NOT NULL, `syncState` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO `course_schedules_new`
                    (`id`,`name`,`weekday`,`startMinute`,`endMinute`,`location`,`teacher`,`weeks`,`sourceHash`,`userId`,`clientId`,`remoteId`,`version`,`syncState`,`updatedAt`,`deletedAt`)
                    SELECT `id`,`name`,`weekday`,`startMinute`,`endMinute`,`location`,`teacher`,`weeks`,`sourceHash`,'local_user',lower(
                      hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || substr(hex(randomblob(2)),2) ||
                      '-' || substr('89ab', abs(random()) % 4 + 1, 1) || substr(hex(randomblob(2)),2) || '-' || hex(randomblob(6))
                    ),NULL,1,'pending',CAST(strftime('%s','now') AS INTEGER) * 1000,NULL FROM `course_schedules`
                """.trimIndent())
                db.execSQL("DROP TABLE `course_schedules`")
                db.execSQL("ALTER TABLE `course_schedules_new` RENAME TO `course_schedules`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_course_schedules_sourceHash` ON `course_schedules` (`sourceHash`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_course_schedules_clientId` ON `course_schedules` (`clientId`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `ai_reports` ADD COLUMN `provider` TEXT NOT NULL DEFAULT 'AUTO'")
                db.execSQL("ALTER TABLE `ai_reports` ADD COLUMN `model` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `ai_reports` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `ai_reports` SET `updatedAt` = `createdAt` WHERE `updatedAt` = 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `daily_greetings` (
                      `id` TEXT NOT NULL,
                      `localDate` TEXT NOT NULL,
                      `text` TEXT NOT NULL,
                      `provider` TEXT NOT NULL,
                      `generatedAt` INTEGER NOT NULL,
                      PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `agent_memories` (
                      `id` TEXT NOT NULL, `type` TEXT NOT NULL, `content` TEXT NOT NULL,
                      `source` TEXT NOT NULL, `confidence` REAL NOT NULL, `createdAt` INTEGER NOT NULL,
                      `confirmedAt` INTEGER, `expiresAt` INTEGER, PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_memories_confirmedAt_expiresAt` ON `agent_memories` (`confirmedAt`, `expiresAt`)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `agent_traces` (
                      `id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `kind` TEXT NOT NULL,
                      `name` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `success` INTEGER NOT NULL,
                      `errorCode` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_traces_sessionId_createdAt` ON `agent_traces` (`sessionId`, `createdAt`)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `agent_actions` (
                      `idempotencyKey` TEXT NOT NULL, `toolName` TEXT NOT NULL, `argumentsHash` TEXT NOT NULL,
                      `status` TEXT NOT NULL, `resultJson` TEXT, `createdAt` INTEGER NOT NULL,
                      `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`idempotencyKey`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `health_summary_cache` (
                      `windowKey` TEXT NOT NULL, `summaryJson` TEXT NOT NULL, `source` TEXT NOT NULL,
                      `observedAt` INTEGER NOT NULL, `lastSyncAt` INTEGER, PRIMARY KEY(`windowKey`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `daily_goal_snapshots` (
                      `userId` TEXT NOT NULL,
                      `localDate` TEXT NOT NULL,
                      `targetMinutes` INTEGER NOT NULL,
                      `createdAt` INTEGER NOT NULL,
                      PRIMARY KEY(`userId`, `localDate`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `daily_goal_snapshots` (`userId`, `localDate`, `targetMinutes`, `createdAt`)
                    SELECT `userId`,
                           strftime('%Y-%m-%d', `endTime` / 1000, 'unixepoch', 'localtime'),
                           240,
                           MIN(`endTime`)
                    FROM `time_records`
                    WHERE `deletedAt` IS NULL
                      AND `durationMinutes` > 0
                      AND `endTime` > `startTime`
                      AND strftime('%Y-%m-%d', `endTime` / 1000, 'unixepoch', 'localtime') IS NOT NULL
                    GROUP BY `userId`, strftime('%Y-%m-%d', `endTime` / 1000, 'unixepoch', 'localtime')
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `ai_reports` ADD COLUMN `executionEngine` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `ai_reports` ADD COLUMN `requestId` TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    UPDATE `ai_reports`
                    SET `executionEngine` = CASE
                        WHEN `provider` = 'LOCAL' THEN 'LOCAL_MNN'
                        ELSE 'CLOUD_OPENAI_COMPATIBLE'
                    END,
                    `requestId` = 'legacy:' || `id`
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_course_schedules_sourceHash")
                db.execSQL("DROP INDEX IF EXISTS index_course_schedules_clientId")
                db.execSQL("DROP INDEX IF EXISTS index_time_records_clientId")
                db.execSQL("CREATE UNIQUE INDEX index_course_schedules_userId_clientId ON course_schedules(userId, clientId)")
                db.execSQL("CREATE UNIQUE INDEX index_time_records_userId_clientId ON time_records(userId, clientId)")
                db.execSQL("CREATE UNIQUE INDEX index_course_schedules_userId_sourceHash ON course_schedules(userId, sourceHash)")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE course_schedules ADD COLUMN periodStart INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE course_schedules ADD COLUMN periodEnd INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE course_schedules ADD COLUMN periodStartTimes TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile
        private var INSTANCE: CampusDatabase? = null

        fun getDatabase(context: Context): CampusDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CampusDatabase::class.java,
                    "campus_database"
                )
                .addMigrations(
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                )
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
