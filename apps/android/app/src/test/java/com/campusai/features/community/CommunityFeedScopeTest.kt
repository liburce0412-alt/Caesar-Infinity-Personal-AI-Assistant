package com.campusai.features.community

import org.junit.Assert.*
import org.junit.Test

class CommunityFeedScopeTest {
    @Test fun `public scope excludes private and unapproved rows before limiting results`() {
        val filters = communityScopeFilters(CommunityFeedScope.PUBLIC, "author_id", "self")
        val rows = listOf(
            mapOf("is_public" to "false", "moderation_status" to "approved"),
            mapOf("is_public" to "true", "moderation_status" to "pending"),
            mapOf("is_public" to "true", "moderation_status" to "approved"),
        )
        assertEquals(listOf(rows.last()), rows.filter { row -> filters.all { (key, value) -> row[key] == value.removePrefix("eq.") } })
    }

    @Test fun `my scope preserves private pending and completed content but excludes other owners`() {
        for (column in listOf("author_id", "seller_id")) {
            val filters = communityScopeFilters(CommunityFeedScope.MINE, column, "self")
            assertEquals(mapOf(column to "eq.self"), filters)
            assertFalse(filters.containsKey("is_public"))
            assertFalse(filters.containsKey("moderation_status"))
        }
    }

    @Test fun `my content cannot fall back to an unscoped read when signed out`() {
        assertTrue(runCatching { communityScopeFilters(CommunityFeedScope.MINE, "author_id", "") }.isFailure)
    }
}
