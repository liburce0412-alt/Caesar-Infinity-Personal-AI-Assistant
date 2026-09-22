package com.campusai.features.community

import java.time.OffsetDateTime
import java.util.UUID

internal fun messagePageParameters(conversationId: String, before: CampusMessage?): Map<String, String> = buildMap {
    put("select", "id,conversation_id,sender_id,body,created_at")
    put("conversation_id", "eq.$conversationId")
    put("deleted_at", "is.null")
    put("order", "created_at.desc,id.desc")
    put("limit", "200")
    before?.let {
        val timestamp = OffsetDateTime.parse(it.createdAt).toString()
        val id = UUID.fromString(it.id).toString()
        put("or", "(created_at.lt.$timestamp,and(created_at.eq.$timestamp,id.lt.$id))")
    }
}
