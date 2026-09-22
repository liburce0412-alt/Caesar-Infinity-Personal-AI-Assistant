package com.campusai.core.model

import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class TimeRecord(
    val id: Int = 0,
    val title: String,
    val category: String, // Learning, Reading, Workout, Project, Course, Others
    val startTime: Long,
    val endTime: Long,
    val durationMinutes: Long,
    val remark: String,
    val userId: String = "local_user"
) : Serializable

enum class ContributionLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    GOAL,
}

data class DailyContribution(
    val date: LocalDate,
    val completedCount: Int,
    val durationMinutes: Long,
    val targetMinutes: Long,
    val level: ContributionLevel,
)

/**
 * Builds a calendar-stable contribution series from completed, active time records.
 *
 * A time record is complete once it has a positive duration and a valid end time. Soft-deleted
 * records never reach this layer because the active-record query filters them out. The
 * completion date is deliberately derived from endTime rather than startTime so a record that
 * crosses midnight belongs to the day on which it was completed.
 */
object DailyContributionCalculator {
    const val DEFAULT_TARGET_MINUTES = 240L

    fun calculate(
        year: Int,
        records: Iterable<TimeRecord>,
        targetSnapshots: Map<LocalDate, Long> = emptyMap(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zoneId),
        defaultTargetMinutes: Long = DEFAULT_TARGET_MINUTES,
    ): List<DailyContribution> {
        require(defaultTargetMinutes > 0L) { "每日目标必须大于 0。" }
        val firstDay = LocalDate.of(year, 1, 1)
        val lastDay = LocalDate.of(year, 12, 31)
        val byDate = records.asSequence()
            .mapNotNull { record -> TimeRecordCalendar.completionDate(record, zoneId)?.let { it to record } }
            .filter { (date, _) -> date.year == year && !date.isAfter(today) }
            .groupBy({ it.first }, { it.second })

        return generateSequence(firstDay) { current ->
            current.plusDays(1).takeUnless { it.isAfter(lastDay) }
        }.map { date ->
            val dayRecords = byDate[date].orEmpty()
            val minutes = dayRecords.fold(0L) { total, record ->
                if (Long.MAX_VALUE - total < record.durationMinutes) Long.MAX_VALUE
                else total + record.durationMinutes
            }
            val target = targetSnapshots[date]?.takeIf { it > 0L } ?: defaultTargetMinutes
            DailyContribution(
                date = date,
                completedCount = dayRecords.size,
                durationMinutes = minutes,
                targetMinutes = target,
                level = contributionLevel(minutes, target),
            )
        }.toList()
    }

    fun contributionLevel(durationMinutes: Long, targetMinutes: Long): ContributionLevel {
        require(targetMinutes > 0L) { "每日目标必须大于 0。" }
        if (durationMinutes <= 0L) return ContributionLevel.NONE
        val ratio = durationMinutes.toDouble() / targetMinutes.toDouble()
        return when {
            ratio < .25 -> ContributionLevel.LOW
            ratio < .50 -> ContributionLevel.MEDIUM
            ratio < 1.0 -> ContributionLevel.HIGH
            else -> ContributionLevel.GOAL
        }
    }
}

data class Goods(
    val id: Int = 0,
    val title: String,
    val description: String,
    val imageUrl: String,
    val price: Double,
    val sellerName: String,
    val sellerId: String = "local_user",
    val createdAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false
) : Serializable

data class Friend(
    val id: String,
    val nickname: String,
    val avatarUrl: String,
    val bio: String,
    val status: String // "pending", "friend"
) : Serializable

data class ChatMessage(
    val id: Int = 0,
    val friendId: String,
    val senderId: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = false
) : Serializable

data class UserMessage(
    val id: Int = 0,
    val profileUserId: String,
    val authorName: String,
    val authorAvatar: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isApproved: Boolean = true // For Admin Review Filter
) : Serializable

data class Achievement(
    val id: String,
    val name: String,
    val description: String,
    val iconName: String, // Material Icon name or descriptor
    val isUnlocked: Boolean = false,
    val unlockedAt: Long? = null,
    val criteriaDescription: String
) : Serializable

data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val updateLog: String,
    val apkUrl: String,
    val isForceUpdate: Boolean,
    val isGrayUpdate: Boolean = false
) : Serializable

data class CourseSchedule(
    val id: Int = 0,
    val name: String,
    val weekday: Int,
    val startMinute: Int,
    val endMinute: Int,
    val location: String = "",
    val teacher: String = "",
    val weeks: String = "",
    val sourceHash: String,
) : Serializable
