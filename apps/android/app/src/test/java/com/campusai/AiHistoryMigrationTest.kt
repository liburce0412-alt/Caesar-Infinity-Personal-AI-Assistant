package com.campusai

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.campusai.core.database.CampusDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiHistoryMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), CampusDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `v10 to v11 preserves legacy history and dates while bounding only diagnostics`() {
        val name = "ai-history-ownership-migration"
        helper.createDatabase(name, 10).apply {
            execSQL("""
                INSERT INTO ai_reports(id,provider,mode,model,executionEngine,requestId,title,summary,messagesJson,createdAt,updatedAt)
                VALUES('legacy','LOCAL','FAST','Qwen','LOCAL_MNN','request','title','summary','[{"content":"preserved"}]',100,200)
            """.trimIndent())
            execSQL("""
                INSERT INTO agent_memories(id,type,content,source,confidence,createdAt,confirmedAt,expiresAt)
                VALUES('memory','preference','preserve','user',1.0,1,2,NULL)
            """.trimIndent())
            execSQL("""
                INSERT INTO agent_actions(idempotencyKey,toolName,argumentsHash,status,resultJson,createdAt,updatedAt)
                VALUES('action','time.create','hash','completed','{}',1,2)
            """.trimIndent())
            execSQL("""
                INSERT INTO daily_goal_snapshots(userId,localDate,targetMinutes,createdAt)
                VALUES('alice','2026-10-12',240,100)
            """.trimIndent())
            repeat(205) { index ->
                execSQL("""
                    INSERT INTO agent_traces(id,sessionId,kind,name,durationMs,success,errorCode,createdAt)
                    VALUES('trace-${index.toString().padStart(4, '0')}','session','tool','read',1,1,NULL,100)
                """.trimIndent())
            }
            close()
        }
        helper.runMigrationsAndValidate(name, 11, true, CampusDatabase.MIGRATION_10_11).use { db ->
            db.query("SELECT ownerUserId,messagesJson,createdAt,updatedAt FROM ai_reports").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("local_user", cursor.getString(0))
                assertEquals("[{\"content\":\"preserved\"}]", cursor.getString(1))
                assertEquals(100L, cursor.getLong(2))
                assertEquals(200L, cursor.getLong(3))
                assertEquals(false, cursor.moveToNext())
            }
            db.query("SELECT COUNT(*),MIN(id),MIN(ownerUserId) FROM agent_traces").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(200, cursor.getInt(0))
                assertEquals("trace-0005", cursor.getString(1))
                assertEquals("local_user", cursor.getString(2))
            }
            listOf("agent_memories", "agent_actions").forEach { table ->
                db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    assertEquals(true, cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
            }
            db.query("SELECT ownerUserId,content,confirmedAt FROM agent_memories").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("local_user", cursor.getString(0))
                assertEquals("preserve", cursor.getString(1))
                assertEquals(2L, cursor.getLong(2))
            }
            db.query("SELECT localDate,targetMinutes FROM daily_goal_snapshots").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("2026-10-12", cursor.getString(0))
                assertEquals(240, cursor.getInt(1))
            }
        }
    }
}
