package com.campusai.app

import com.campusai.core.model.TimeRecord
import org.junit.Assert.*
import org.junit.Test

class AchievementsTest {
    private fun record(minutes: Long, day: Int = 1, category: String = "专注") = TimeRecord(
        title = "学习", category = category, startTime = day * 86_400_000L,
        endTime = day * 86_400_000L + minutes * 60_000L, durationMinutes = minutes, remark = "",
    )
    @Test fun `short focus and long unrelated activity do not unlock focus badge`() {
        val items = buildAchievements(listOf(record(2), record(60, category = "运动")), 0)
        assertEquals(16, items.size)
        assertEquals(16, items.map { it.icon.name }.distinct().size)
        assertFalse(items.first { it.name == "专注起航" }.unlocked)
        assertTrue(buildAchievements(listOf(record(25)), 0).first { it.name == "专注起航" }.unlocked)
    }
    @Test fun `past seven day streak remains earned without today's record`() {
        assertTrue(buildAchievements((1..7).map { record(25, it) }, 0).first { it.name == "稳定节奏" }.unlocked)
    }
    @Test fun `empty invalid and future records do not award first badge`() {
        assertFalse(buildAchievements(emptyList(), 0).first().unlocked)
        assertFalse(buildAchievements(listOf(record(0), record(25).copy(endTime = Long.MAX_VALUE)), 0).first().unlocked)
    }
}
