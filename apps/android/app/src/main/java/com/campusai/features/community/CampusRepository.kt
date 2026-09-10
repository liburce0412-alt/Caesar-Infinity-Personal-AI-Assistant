package com.campusai.features.community

import com.campusai.core.network.SupabaseClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class UploadImage(val bytes: ByteArray, val contentType: String)

data class CommunityPost(
    val id: String,
    val authorId: String,
    val author: String,
    val avatarUrl: String,
    val body: String,
    val topic: String,
    val mediaUrl: String,
    val anonymous: Boolean,
    val likes: Int,
    val likedByMe: Boolean,
    val comments: Int,
    val createdAt: String,
    val isPublic: Boolean = false,
    val mediaPaths: List<String> = emptyList(),
)

data class CommunityComment(
    val id: String,
    val postId: String,
    val authorId: String,
    val author: String,
    val body: String,
    val moderationStatus: String,
    val createdAt: String,
)

data class CampusAnnouncement(
    val id: String,
    val title: String,
    val body: String,
    val publishAt: String,
)

data class MarketplaceListing(
    val id: String,
    val sellerId: String,
    val seller: String,
    val title: String,
    val description: String,
    val priceCents: Int?,
    val location: String,
    val mediaUrl: String,
    val status: String,
    val moderationStatus: String,
    val createdAt: String,
    val isPublic: Boolean = false,
    val mediaPaths: List<String> = emptyList(),
    val targetDate: String = "",
    val completedAt: String = "",
    val completionNote: String = "",
    val completionMediaUrl: String = "",
)

data class ConversationSummary(
    val id: String,
    val listingId: String,
    val listingTitle: String,
    val otherUserId: String,
    val otherName: String,
    val lastMessage: String,
    val lastMessageAt: String,
    val unreadCount: Int,
)

data class CampusMessage(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val body: String,
    val createdAt: String,
)

data class MarketplaceOrder(
    val id: String,
    val listingId: String,
    val listingTitle: String,
    val listingMediaUrl: String,
    val buyerId: String,
    val buyerName: String,
    val sellerId: String,
    val sellerName: String,
    val priceCents: Int,
    val status: String,
    val version: Int,
    val createdAt: String,
    val updatedAt: String,
)

fun allowedOrderTransitions(status: String, isBuyer: Boolean): List<String> = when (status) {
    "pending_payment" -> if (isBuyer) listOf("paid", "cancelled") else listOf("cancelled")
    "paid" -> if (isBuyer) listOf("disputed") else listOf("meeting", "disputed")
    "meeting" -> if (isBuyer) listOf("completed", "disputed") else listOf("disputed")
    else -> emptyList()
}

class CampusRepository {
    suspend fun loadAnnouncements(): Result<List<CampusAnnouncement>> = friendly(
        SupabaseClient.restGet(
            table = "announcements",
            parameters = mapOf(
                "select" to "id,title,body,publish_at,created_at",
                "status" to "eq.published",
                "order" to "publish_at.desc.nullslast,created_at.desc",
                "limit" to "3",
            ),
        ).map { rows ->
            List(rows.length()) { index ->
                rows.getJSONObject(index).let { item ->
                    CampusAnnouncement(
                        id = item.nullableString("id"),
                        title = item.nullableString("title"),
                        body = item.nullableString("body"),
                        publishAt = item.nullableString("publish_at").ifBlank { item.nullableString("created_at") },
                    )
                }
            }
        },
    )

    suspend fun loadPosts(userId: String = "", scope: CommunityFeedScope? = null): Result<List<CommunityPost>> {
        val scopeFilters = runCatching { communityScopeFilters(scope, "author_id", userId) }
            .getOrElse { return Result.failure(it) }
        val postsResult = SupabaseClient.restGet(
            table = "posts",
            parameters = mapOf(
                "select" to "id,author_id,body,topic,media_paths,is_anonymous,is_public,like_count,comment_count,created_at,author:profiles!posts_author_id_fkey(display_name,avatar_path)",
                "deleted_at" to "is.null",
                "order" to "created_at.desc",
                "limit" to "50",
            ) + scopeFilters,
        )
        if (postsResult.isFailure) return Result.failure(postsResult.exceptionOrNull()!!)
        val likedPostIds = if (userId.isBlank()) {
            emptySet()
        } else {
            SupabaseClient.restGet(
                table = "post_likes",
                parameters = mapOf(
                    "select" to "post_id",
                    "user_id" to "eq.$userId",
                    "limit" to "1000",
                ),
            ).getOrNull()?.let { rows ->
                buildSet {
                    repeat(rows.length()) { index ->
                        rows.optJSONObject(index)?.nullableString("post_id")?.let(::add)
                    }
                }
            }.orEmpty()
        }
        val rows = postsResult.getOrThrow()
        return Result.success(List(rows.length()) { index ->
            parsePost(rows.getJSONObject(index), likedPostIds)
        })
    }

    suspend fun publishPost(userId: String, body: String, topic: String, anonymous: Boolean, image: UploadImage?, isPublic: Boolean = false): Result<CommunityPost> {
        val bucket = "post-private"
        val mediaPath = image?.let { uploadImage(bucket, userId, it).getOrElse { error -> return Result.failure(error) } }
        val result = SupabaseClient.restInsert(
            "posts",
            JSONObject()
                .put("author_id", userId)
                .put("body", body.trim())
                .put("topic", topic.trim().ifBlank { JSONObject.NULL })
                .put("is_anonymous", anonymous)
                .put("is_public", isPublic)
                .put("media_paths", if (mediaPath == null) JSONArray() else JSONArray().put(mediaPath)),
        ).map { parsePost(it) }
        if (result.isFailure && mediaPath != null) SupabaseClient.deleteObject(bucket, mediaPath)
        return result
    }

    suspend fun togglePostLike(postId: String): Result<Pair<Boolean, Int>> = SupabaseClient.rpc(
        "toggle_post_like",
        JSONObject().put("target_post", postId),
    ).map { it.optBoolean("liked") to it.optInt("count") }

    suspend fun togglePostBookmark(postId: String): Result<Boolean> = SupabaseClient.rpc(
        "toggle_post_bookmark",
        JSONObject().put("target_post", postId),
    ).map { it.optString("value").toBooleanStrictOrNull() ?: false }

    suspend fun loadComments(postId: String): Result<List<CommunityComment>> = friendly(
        SupabaseClient.restGet(
            table = "comments",
            parameters = mapOf(
                "select" to "id,post_id,author_id,body,moderation_status,created_at,author:profiles!comments_author_id_fkey(display_name)",
                "post_id" to "eq.$postId",
                "deleted_at" to "is.null",
                "order" to "created_at.asc",
                "limit" to "200",
            ),
        ).map { rows -> List(rows.length()) { index -> parseComment(rows.getJSONObject(index)) } },
    )

    suspend fun publishComment(postId: String, body: String): Result<CommunityComment> = friendly(
        SupabaseClient.rpc(
            "create_comment",
            JSONObject()
                .put("target_post", postId)
                .put("comment_body", body.trim())
                .put("parent_comment", JSONObject.NULL),
        ).map(::parseComment),
    )

    suspend fun submitReport(userId: String, targetType: String, targetId: String, reason: String, details: String): Result<Unit> = friendly(
        SupabaseClient.restInsert(
            "reports",
            JSONObject()
                .put("reporter_id", userId)
                .put("target_type", targetType)
                .put("target_id", targetId)
                .put("reason", reason.trim())
                .put("details", details.trim()),
        ).map { Unit },
    )

    suspend fun loadListings(userId: String = "", scope: CommunityFeedScope? = null): Result<List<MarketplaceListing>> {
        val scopeFilters = runCatching { communityScopeFilters(scope, "seller_id", userId) }
            .getOrElse { return Result.failure(it) }
        val select = "id,seller_id,title,description,price_cents,location,media_paths,is_public,status,moderation_status,created_at,target_date,completed_at,completion_note,completion_media_paths,seller:profiles!listings_seller_id_fkey(display_name)"
        if (scope != null) {
            return SupabaseClient.restGet(
                table = "listings",
                parameters = mapOf(
                    "select" to select,
                    "status" to if (scope == CommunityFeedScope.PUBLIC) "eq.active" else "neq.removed",
                    "order" to "created_at.desc",
                    "limit" to "50",
                ) + scopeFilters,
                callTimeoutSeconds = 20,
            ).mapCatching { rows -> List(rows.length()) { index -> parseListing(rows.getJSONObject(index)) } }
        }
        val published = SupabaseClient.restGet(
            table = "listings",
            parameters = mapOf(
                "select" to select,
                "status" to "eq.active",
                "is_public" to "eq.true",
                "moderation_status" to "eq.approved",
                "order" to "created_at.desc",
                "limit" to "50",
            ),
            callTimeoutSeconds = 20,
        ).mapCatching { rows -> List(rows.length()) { index -> parseListing(rows.getJSONObject(index)) } }
            .getOrElse { return Result.failure(it) }
        if (userId.isBlank()) return Result.success(published)

        // The public wall stays active+approved, while the owner also sees their own review queue
        // and completed cards. This matches the composer promise without exposing pending cards to others.
        val owned = SupabaseClient.restGet(
            table = "listings",
            parameters = mapOf(
                "select" to select,
                "seller_id" to "eq.$userId",
                "status" to "neq.removed",
                "order" to "created_at.desc",
                "limit" to "50",
            ),
            callTimeoutSeconds = 20,
        ).mapCatching { rows -> List(rows.length()) { index -> parseListing(rows.getJSONObject(index)) } }
            .getOrElse { return Result.failure(it) }
        return Result.success((owned + published).distinctBy(MarketplaceListing::id).sortedByDescending(MarketplaceListing::createdAt))
    }

    suspend fun publishListing(
        userId: String,
        title: String,
        description: String,
        priceCents: Int?,
        location: String,
        image: UploadImage?,
        isPublic: Boolean = false,
        targetDate: String = "",
    ): Result<MarketplaceListing> {
        if (priceCents != null && priceCents < 0) return Result.failure(IllegalArgumentException("价格不能为负数。"))
        val bucket = "listing-private"
        val mediaPath = image?.let { uploadImage(bucket, userId, it).getOrElse { error -> return Result.failure(error) } }
        val result = SupabaseClient.restInsert(
        "listings",
        JSONObject()
            .put("seller_id", userId)
            .put("title", title.trim())
            .put("description", description.trim())
            .put("price_cents", priceCents ?: JSONObject.NULL)
            .put("is_public", isPublic)
            .put("target_date", targetDate.ifBlank { null } ?: JSONObject.NULL)
            .put("category", "其他")
            .put("condition", "良好")
            .put("location", location.trim())
            .put("media_paths", if (mediaPath == null) JSONArray() else JSONArray().put(mediaPath)),
    ).map { parseListing(it) }
        if (result.isFailure && mediaPath != null) SupabaseClient.deleteObject(bucket, mediaPath)
        return result
    }

    suspend fun updatePost(post: CommunityPost, body: String, topic: String, anonymous: Boolean, image: UploadImage?, removeImage: Boolean, isPublic: Boolean = post.isPublic): Result<Unit> {
        val bucket = "post-private"
        val path = image?.let { uploadImage(bucket, post.authorId, it).getOrElse { error -> return Result.failure(error) } }
        val result = friendly(SupabaseClient.rpc("edit_community_post_visibility", JSONObject().put("next_public", isPublic)
            .put("target_post", post.id).put("post_body", body.trim()).put("post_topic", topic.trim())
            .put("anonymous", anonymous).put("next_media", when { path != null -> JSONArray().put(path); removeImage -> JSONArray(); else -> JSONObject.NULL })
        ).map { Unit })
        if (result.isFailure && path != null) SupabaseClient.deleteObject(bucket, path)
        return result
    }

    suspend fun updateListing(listing: MarketplaceListing, title: String, description: String, priceCents: Int?, location: String, image: UploadImage?, removeImage: Boolean, targetDate: String, isPublic: Boolean = listing.isPublic): Result<Unit> {
        val bucket = "listing-private"
        val path = image?.let { uploadImage(bucket, listing.sellerId, it).getOrElse { error -> return Result.failure(error) } }
        val result = friendly(SupabaseClient.rpc("edit_wish_visibility", JSONObject().put("next_public", isPublic)
            .put("target_listing", listing.id).put("wish_title", title.trim()).put("wish_description", description.trim())
            .put("wish_price", priceCents ?: JSONObject.NULL).put("wish_location", location.trim())
            .put("wish_date", targetDate.ifBlank { null } ?: JSONObject.NULL)
            .put("next_media", when { path != null -> JSONArray().put(path); removeImage -> JSONArray(); else -> JSONObject.NULL })
        ).map { Unit })
        if (result.isFailure && path != null) SupabaseClient.deleteObject(bucket, path)
        return result
    }

    suspend fun deletePost(postId: String): Result<Unit> = friendly(
        SupabaseClient.rpc("delete_community_post", JSONObject().put("target_post", postId)).map { Unit },
    )

    suspend fun completeWish(listing: MarketplaceListing, note: String, image: UploadImage?): Result<Unit> {
        val bucket = "listing-private"
        val path = image?.let { uploadImage(bucket, listing.sellerId, it).getOrElse { error -> return Result.failure(error) } }
        val result = friendly(SupabaseClient.rpc("complete_wish", JSONObject().put("target_listing", listing.id)
            .put("memory_note", note.trim()).put("memory_media", path?.let { JSONArray().put(it) } ?: JSONObject.NULL)).map { Unit })
        if (result.isFailure && path != null) SupabaseClient.deleteObject(bucket, path)
        return result
    }

    suspend fun deleteListing(listingId: String): Result<Unit> = friendly(
        SupabaseClient.rpc("delete_wish", JSONObject().put("target_listing", listingId)).map { Unit },
    )

    suspend fun loadWishComments(listingId: String): Result<List<CommunityComment>> = friendly(
        SupabaseClient.restGet("listing_comments", mapOf(
            "select" to "id,listing_id,author_id,body,moderation_status,created_at,author:profiles!listing_comments_author_id_fkey(display_name)",
            "listing_id" to "eq.$listingId", "deleted_at" to "is.null", "order" to "created_at.asc", "limit" to "200",
        )).map { rows -> List(rows.length()) { index -> parseComment(rows.getJSONObject(index)) } },
    )

    suspend fun publishWishComment(listingId: String, body: String): Result<Unit> = friendly(
        SupabaseClient.rpc("create_wish_comment", JSONObject().put("target_listing", listingId).put("comment_body", body.trim())).map { Unit },
    )

    suspend fun toggleFavorite(listingId: String): Result<Boolean> = SupabaseClient.rpc(
        "toggle_favorite",
        JSONObject().put("target_listing", listingId),
    ).map { it.optString("value").toBooleanStrictOrNull() ?: false }

    suspend fun openConversation(otherUserId: String, listingId: String?): Result<String> = friendly(
        SupabaseClient.rpc(
            "open_conversation",
            JSONObject()
                .put("other_user", otherUserId)
                .put("related_listing", listingId ?: JSONObject.NULL),
        ).mapCatching { response ->
            response.optString("value").ifBlank { response.optString("open_conversation") }
                .ifBlank { error("消息会话没有成功创建。") }
        },
    )

    suspend fun loadConversations(): Result<List<ConversationSummary>> = friendly(
        SupabaseClient.rpcArray("list_conversation_summaries").map { rows ->
            List(rows.length()) { index ->
                rows.getJSONObject(index).let { item ->
                    ConversationSummary(
                        id = item.optString("id"),
                        listingId = item.optString("listing_id"),
                        listingTitle = item.optString("listing_title"),
                        otherUserId = item.optString("other_user_id"),
                        otherName = item.optString("other_name").ifBlank { "Caesar 用户" },
                        lastMessage = item.optString("last_message"),
                        lastMessageAt = item.optString("last_message_at"),
                        unreadCount = item.optInt("unread_count"),
                    )
                }
            }
        },
    )

    suspend fun loadMessages(conversationId: String): Result<List<CampusMessage>> = friendly(
        SupabaseClient.restGet(
            table = "messages",
            parameters = mapOf(
                "select" to "id,conversation_id,sender_id,body,created_at",
                "conversation_id" to "eq.$conversationId",
                "deleted_at" to "is.null",
                "order" to "created_at.asc",
                "limit" to "200",
            ),
        ).map { rows -> List(rows.length()) { index -> parseMessage(rows.getJSONObject(index)) } },
    )

    suspend fun sendMessage(conversationId: String, body: String): Result<CampusMessage> = friendly(
        SupabaseClient.rpc(
            "send_message",
            JSONObject()
                .put("target_conversation", conversationId)
                .put("client_message", UUID.randomUUID().toString())
                .put("message_body", body.trim()),
        ).map(::parseMessage),
    )

    suspend fun markConversationRead(conversationId: String): Result<Unit> = friendly(
        SupabaseClient.rpc("mark_conversation_read", JSONObject().put("target_conversation", conversationId)).map { Unit },
    )

    suspend fun loadOrders(): Result<List<MarketplaceOrder>> = friendly(
        SupabaseClient.rpcArray("list_my_orders").map { rows ->
            List(rows.length()) { index -> parseOrder(rows.getJSONObject(index)) }
        },
    )

    suspend fun createOrder(listingId: String): Result<String> = friendly(
        SupabaseClient.rpc("create_order", JSONObject().put("target_listing", listingId)).mapCatching { response ->
            response.optString("value").ifBlank { response.optString("create_order") }
                .ifBlank { error("订单没有成功创建。") }
        },
    )

    suspend fun transitionOrder(orderId: String, version: Int, nextStatus: String): Result<MarketplaceOrder> = friendly(
        SupabaseClient.rpc(
            "transition_order",
            JSONObject()
                .put("target_order", orderId)
                .put("expected_version", version)
                .put("next_status", nextStatus),
        ).map { parseOrder(it) },
    )

    private suspend fun parsePost(item: JSONObject, likedPostIds: Set<String> = emptySet()): CommunityPost {
        val media = item.optJSONArray("media_paths") ?: JSONArray()
        val authorObject = item.optJSONObject("author")
        val author = authorObject?.nullableString("display_name").orEmpty()
        val anonymous = item.optBoolean("is_anonymous")
        val postId = item.nullableString("id")
        val avatarPath = authorObject?.nullableString("avatar_path").orEmpty()
        return CommunityPost(
            id = postId,
            authorId = item.nullableString("author_id"),
            author = if (anonymous) "匿名访客" else author.ifBlank { "Caesar 用户" },
            avatarUrl = if (anonymous || avatarPath.isBlank()) "" else SupabaseClient.publicMediaUrl("avatars", avatarPath),
            body = item.nullableString("body"),
            topic = item.nullableString("topic"),
            mediaUrl = media.optString(0).takeIf { it.isNotBlank() }?.let { communityMediaUrl("post", it, item.optBoolean("is_public")) }.orEmpty(),
            anonymous = anonymous,
            isPublic = item.optBoolean("is_public"),
            mediaPaths = List(media.length()) { media.getString(it) },
            likes = item.optInt("like_count"),
            likedByMe = postId in likedPostIds,
            comments = item.optInt("comment_count"),
            createdAt = item.nullableString("created_at"),
        )
    }

    private fun parseComment(item: JSONObject) = CommunityComment(
        id = item.optString("id"),
        postId = item.nullableString("post_id").ifBlank { item.nullableString("listing_id") },
        authorId = item.optString("author_id"),
        author = item.optJSONObject("author")?.optString("display_name").orEmpty().ifBlank { "Caesar 用户" },
        body = item.optString("body"),
        moderationStatus = item.optString("moderation_status"),
        createdAt = item.optString("created_at"),
    )

    private suspend fun parseListing(item: JSONObject): MarketplaceListing {
        val media = item.optJSONArray("media_paths") ?: JSONArray()
        return MarketplaceListing(
            id = item.optString("id"),
            sellerId = item.optString("seller_id"),
            seller = item.optJSONObject("seller")?.optString("display_name").orEmpty().ifBlank { "Caesar 用户" },
            title = item.optString("title"),
            description = item.optString("description"),
            priceCents = if (item.isNull("price_cents")) null else item.getInt("price_cents"),
            isPublic = item.optBoolean("is_public"),
            mediaPaths = List(media.length()) { media.getString(it) },
            targetDate = item.nullableString("target_date"),
            completedAt = item.nullableString("completed_at"),
            completionNote = item.nullableString("completion_note"),
            completionMediaUrl = item.optJSONArray("completion_media_paths")?.optString(0)?.takeIf { it.isNotBlank() }
                ?.let { communityMediaUrl("listing", it, item.optBoolean("is_public")) }.orEmpty(),
            location = item.optString("location"),
            mediaUrl = media.optString(0).takeIf { it.isNotBlank() }?.let { communityMediaUrl("listing", it, item.optBoolean("is_public")) }.orEmpty(),
            status = item.optString("status"),
            moderationStatus = item.optString("moderation_status"),
            createdAt = item.optString("created_at"),
        )
    }

    private fun parseMessage(item: JSONObject) = CampusMessage(
        id = item.optString("id"),
        conversationId = item.optString("conversation_id"),
        senderId = item.optString("sender_id"),
        body = item.optString("body"),
        createdAt = item.optString("created_at"),
    )

    private suspend fun parseOrder(item: JSONObject): MarketplaceOrder {
        val media = item.optJSONArray("listing_media_paths") ?: JSONArray()
        return MarketplaceOrder(
            id = item.optString("id"),
            listingId = item.optString("listing_id"),
            listingTitle = item.optString("listing_title").ifBlank { "心愿墙对话" },
            listingMediaUrl = media.optString(0).takeIf { it.isNotBlank() }?.let { communityMediaUrl("listing", it, true) }.orEmpty(),
            buyerId = item.optString("buyer_id"),
            buyerName = item.optString("buyer_name").ifBlank { "买家" },
            sellerId = item.optString("seller_id"),
            sellerName = item.optString("seller_name").ifBlank { "卖家" },
            priceCents = item.optInt("price_cents"),
            status = item.optString("status"),
            version = item.optInt("version", 1),
            createdAt = item.optString("created_at"),
            updatedAt = item.optString("updated_at"),
        )
    }

    private fun <T> friendly(result: Result<T>): Result<T> = result.fold(
        onSuccess = { Result.success(it) },
        onFailure = { error -> Result.failure(IllegalStateException(remoteErrorMessage(error.message), error)) },
    )

    private fun remoteErrorMessage(raw: String?): String = when {
        raw.isNullOrBlank() -> "服务没有返回可用信息，请稍后重试。"
        "cannot_buy_own_listing" in raw -> "不能购买自己发布的商品。"
        "listing_unavailable" in raw || "listing_not_available" in raw -> "这条心愿暂时不可访问，请刷新后重试。"
        "edit_community_post_visibility" in raw || "edit_wish_visibility" in raw -> "服务端编辑功能尚未更新，请稍后重试。"
        "content_not_owned" in raw -> "只能修改或删除自己的内容。"
        "content_invalid" in raw -> "请检查填写的内容后重试。"
        "order_conflict" in raw -> "订单刚刚发生了变化，请刷新后再操作。"
        "invalid_order_transition" in raw -> "当前订单阶段不能执行这个操作。"
        "conversation_not_available" in raw || "forbidden" in raw -> "你已无法访问这段会话。"
        "message_body_invalid" in raw -> "消息需为 1–4000 个字符。"
        "comment_body_invalid" in raw -> "评论需为 1–2000 个字符。"
        "post_not_available" in raw -> "帖子已被移除或仍在审核，请刷新后重试。"
        else -> raw
    }

    private suspend fun communityMediaUrl(kind: String, path: String, isPublic: Boolean): String {
        val first = if (isPublic) "$kind-media" else "$kind-private"
        val second = if (isPublic) "$kind-private" else "$kind-media"
        return SupabaseClient.signedMediaUrl(first, path, expiresInSeconds = 120)
            .getOrElse { SupabaseClient.signedMediaUrl(second, path, expiresInSeconds = 120).getOrDefault("") }
    }

    private suspend fun uploadImage(bucket: String, userId: String, image: UploadImage): Result<String> {
        if (image.bytes.size > 15 * 1024 * 1024) return Result.failure(IllegalArgumentException("图片不能超过 15MB。"))
        val extension = when (image.contentType.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> return Result.failure(IllegalArgumentException("只支持 JPEG、PNG 或 WebP 图片。"))
        }
        val path = "$userId/${UUID.randomUUID()}.$extension"
        return SupabaseClient.uploadObject(bucket, path, image.bytes, image.contentType).map { path }
    }
}

internal fun JSONObject.nullableString(name: String): String =
    normalizeNullableString(isNull(name), optString(name, ""))

internal fun normalizeNullableString(isNull: Boolean, value: String?): String =
    if (isNull) "" else value.takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()
