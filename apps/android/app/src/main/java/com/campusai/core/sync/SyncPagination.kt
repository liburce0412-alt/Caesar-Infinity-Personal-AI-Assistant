package com.campusai.core.sync

import org.json.JSONObject

internal data class SyncCursor(val updatedAt: String, val id: String)

internal fun syncPageParameters(userId: String, cursor: SyncCursor?): Map<String, String> = buildMap {
    put("select", "*")
    put("user_id", "eq.$userId")
    put("order", "updated_at.asc,id.asc")
    put("limit", "500")
    cursor?.let { put("or", "(updated_at.gt.${it.updatedAt},and(updated_at.eq.${it.updatedAt},id.gt.${it.id}))") }
}

// Start from the beginning on retries. Applied rows are idempotent, so an interrupted
// page never advances a durable watermark past unapplied records or tombstones.
internal suspend fun walkSyncPages(
    fetch: suspend (SyncCursor?) -> List<JSONObject>,
    apply: suspend (JSONObject) -> Unit,
) {
    var cursor: SyncCursor? = null
    while (true) {
        val rows = fetch(cursor)
        if (rows.isEmpty()) return
        for (row in rows) apply(row)
        val next = rows.last().let { SyncCursor(it.getString("updated_at"), it.getString("id")) }
        check(next != cursor) { "同步分页游标未前进" }
        cursor = next
        // Continue even for a short page: a server may enforce a smaller row limit.
    }
}
