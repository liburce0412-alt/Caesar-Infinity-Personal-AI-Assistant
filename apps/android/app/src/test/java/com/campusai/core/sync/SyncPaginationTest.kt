package com.campusai.core.sync

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncPaginationTest {
    @Test fun `1001 rows with identical timestamps and server capped pages all apply`() = runBlocking {
        val rows = (1..1001).map { JSONObject().put("id", "%04d".format(it))
            .put("updated_at", "2026-09-22T00:00:00Z").put("deleted_at", if (it == 1001) "2026-09-22" else JSONObject.NULL) }
        val applied = mutableListOf<String>()
        walkSyncPages(fetch = { cursor -> rows.filter { cursor == null || it.getString("id") > cursor.id }.take(137) },
            apply = { applied += it.getString("id") })
        assertEquals(1001, applied.distinct().size)
        assertEquals("1001", applied.last())
        assertTrue(syncPageParameters("alice", SyncCursor("same", "0500")).getValue("or").contains("id.gt.0500"))
    }

    @Test fun `a failed page is not reported as successful`() = runBlocking {
        var count = 0
        val result = runCatching { walkSyncPages(fetch = { if (count > 0) error("offline") else listOf(JSONObject().put("id", "1").put("updated_at", "date")) },
            apply = { count++ }) }
        assertTrue(result.isFailure)
        assertEquals(1, count)
    }
}
