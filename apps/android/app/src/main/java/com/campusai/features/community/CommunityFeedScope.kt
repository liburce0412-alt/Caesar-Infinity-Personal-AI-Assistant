package com.campusai.features.community

/** Browsing scope is independent of the visibility chosen when publishing. */
enum class CommunityFeedScope { PUBLIC, MINE }

internal fun communityScopeFilters(scope: CommunityFeedScope?, ownerColumn: String, userId: String): Map<String, String> =
    when (scope) {
        CommunityFeedScope.PUBLIC -> mapOf("is_public" to "eq.true", "moderation_status" to "eq.approved")
        CommunityFeedScope.MINE -> {
            require(userId.isNotBlank()) { "登录后可查看自己发表的内容。" }
            mapOf(ownerColumn to "eq.$userId")
        }
        null -> emptyMap()
    }
