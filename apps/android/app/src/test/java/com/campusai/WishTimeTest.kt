package com.campusai

import com.campusai.features.community.*
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class WishTimeTest {
    private val today = LocalDate.of(2026, 9, 6)
    @Test fun `optional date is gentle around the target day`() {
        assertNull(wishDateMessage("", today))
        assertNull(wishDateMessage("2026-02-30", today))
        assertEquals("还有 12 天，慢慢靠近它", wishDateMessage("2026-09-18", today))
        assertEquals("今天，给这个愿望一点时间", wishDateMessage("2026-09-06", today))
        assertEquals("慢慢来，它还在这里", wishDateMessage("2026-09-05", today))
    }
    @Test fun `recording time is displayed in local time`() {
        val local = today.atTime(9, 30).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
        assertEquals("今天记下", wishRecordedTime(local, today))
        assertEquals("昨天记下", wishRecordedTime(local, today.plusDays(1)))
        assertTrue(wishRecordedTime(local, today, full = true).contains("2026年9月6日 09:30"))
    }
    @Test fun `revisiting uses only the owners genuinely older wishes`() {
        fun wish(id: String, owner: String, time: String) = MarketplaceListing(id, owner, "我", "旧心愿", "", null, "", "", "active", "approved", time)
        val own = wish("old", "self", "2026-08-01T00:00:00Z")
        assertEquals(own, wishToRevisit(listOf(own, wish("other", "someone", "2025-01-01T00:00:00Z"), wish("new", "self", "2026-09-05T00:00:00Z")), "self", today))
        assertNull(wishToRevisit(listOf(wish("new", "self", "2026-09-05T00:00:00Z")), "self", today))
    }
}
