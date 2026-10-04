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
class DatabaseMigrationRobolectricTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CampusDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `v9 to v10 retains courses and time records and adds unknown period defaults`() {
        val name = "optional-course-migration"
        helper.createDatabase(name, 9).apply {
            execSQL("INSERT INTO course_schedules(name,weekday,startMinute,endMinute,location,teacher,weeks,sourceHash,userId,clientId,version,syncState,updatedAt) VALUES('Math',1,480,540,'','','','hash','alice','a',1,'synced',1)")
            execSQL("INSERT INTO time_records(title,category,startTime,endTime,durationMinutes,remark,userId,clientId,version,syncState,updatedAt) VALUES('Study','learning',1,60001,1,'','alice','t',1,'synced',1)")
            close()
        }
        helper.runMigrationsAndValidate(name, 10, true, CampusDatabase.MIGRATION_9_10).use { db ->
            db.query("SELECT name,periodStart,periodEnd,periodStartTimes FROM course_schedules").use {
                assertEquals(true, it.moveToFirst())
                assertEquals("Math", it.getString(0))
                assertEquals(0, it.getInt(1)); assertEquals(0, it.getInt(2)); assertEquals("", it.getString(3))
            }
            db.query("SELECT COUNT(*) FROM time_records").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        }
    }

    @Test
    fun `v8 to v9 preserves courses and isolates same hashes by account`() {
        val name = "course-owner-migration"
        helper.createDatabase(name, 8).apply {
            execSQL("INSERT INTO course_schedules(name,weekday,startMinute,endMinute,location,teacher,weeks,sourceHash,userId,clientId,version,syncState,updatedAt) VALUES('Math',1,480,540,'','','','hash','alice','a',1,'synced',1)")
            close()
        }
        helper.runMigrationsAndValidate(name, 9, true, CampusDatabase.MIGRATION_8_9).use { db ->
            db.execSQL("INSERT INTO course_schedules(name,weekday,startMinute,endMinute,location,teacher,weeks,sourceHash,userId,clientId,version,syncState,updatedAt) VALUES('Math',1,480,540,'','','','hash','bob','b',1,'pending',1)")
            db.query("SELECT COUNT(DISTINCT userId) FROM course_schedules WHERE sourceHash='hash'").use {
                it.moveToFirst()
                assertEquals(2, it.getInt(0))
            }
        }
    }

    @Test
    fun `v3 through v6 preserves user rows and creates agent governance tables`() {
        val databaseName = "caesar-robolectric-migration-3-6"
        helper.createDatabase(databaseName, 3).apply {
            execSQL("INSERT INTO time_records(title,category,startTime,endTime,durationMinutes,remark,userId) VALUES('复习','学习',1000,61000,1,'旧记录','local_user')")
            execSQL("INSERT INTO course_schedules(name,weekday,startMinute,endMinute,location,teacher,weeks,sourceHash) VALUES('数据结构',1,480,580,'教学楼','','每周','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')")
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            6,
            true,
            CampusDatabase.MIGRATION_3_4,
            CampusDatabase.MIGRATION_4_5,
            CampusDatabase.MIGRATION_5_6,
        ).use { database ->
            database.query("SELECT title,clientId,syncState,deletedAt FROM time_records").use { cursor ->
                cursor.moveToFirst()
                assertEquals("复习", cursor.getString(0))
                assertEquals(36, cursor.getString(1).length)
                assertEquals("pending", cursor.getString(2))
                assertEquals(true, cursor.isNull(3))
            }
            database.query("SELECT name,clientId,syncState FROM course_schedules").use { cursor ->
                cursor.moveToFirst()
                assertEquals("数据结构", cursor.getString(0))
                assertEquals(36, cursor.getString(1).length)
                assertEquals("pending", cursor.getString(2))
            }
            listOf("agent_memories", "agent_traces", "agent_actions", "health_summary_cache").forEach { table ->
                database.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals(0, cursor.getInt(0))
                }
            }
        }
    }

    @Test
    fun `v5 to v6 preserves conversation model metadata`() {
        val databaseName = "caesar-robolectric-migration-5-6"
        helper.createDatabase(databaseName, 5).apply {
            execSQL("INSERT INTO ai_reports(id,provider,mode,model,title,summary,messagesJson,createdAt,updatedAt) VALUES('session-6','LOCAL','FAST','Qwen3.5-2B','会话','摘要','[]',100,200)")
            close()
        }

        helper.runMigrationsAndValidate(databaseName, 6, true, CampusDatabase.MIGRATION_5_6).use { database ->
            database.query("SELECT provider,mode,model,createdAt,updatedAt FROM ai_reports WHERE id='session-6'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("LOCAL", cursor.getString(0))
                assertEquals("FAST", cursor.getString(1))
                assertEquals("Qwen3.5-2B", cursor.getString(2))
                assertEquals(100L, cursor.getLong(3))
                assertEquals(200L, cursor.getLong(4))
            }
        }
    }

    @Test
    fun `v6 to v7 backfills stable daily targets for active completed records`() {
        val databaseName = "caesar-robolectric-migration-6-7"
        helper.createDatabase(databaseName, 6).apply {
            execSQL(
                """
                INSERT INTO time_records
                (title,category,startTime,endTime,durationMinutes,remark,userId,clientId,remoteId,version,syncState,updatedAt,deletedAt)
                VALUES('跨日专注','学习',1777000000000,1777003600000,60,'','local_user','active-record',NULL,1,'synced',1777003600000,NULL)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO time_records
                (title,category,startTime,endTime,durationMinutes,remark,userId,clientId,remoteId,version,syncState,updatedAt,deletedAt)
                VALUES('已删除','学习',1777100000000,1777103600000,60,'','local_user','deleted-record',NULL,1,'synced',1777103600000,1777103600000)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(databaseName, 7, true, CampusDatabase.MIGRATION_6_7).use { database ->
            database.query("SELECT userId,localDate,targetMinutes,createdAt FROM daily_goal_snapshots").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("local_user", cursor.getString(0))
                assertEquals(10, cursor.getString(1).length)
                assertEquals(240L, cursor.getLong(2))
                assertEquals(1777003600000L, cursor.getLong(3))
                assertEquals(false, cursor.moveToNext())
            }
        }
    }

    @Test
    fun `v7 to v8 backfills resolved execution identity`() {
        val databaseName = "caesar-robolectric-migration-7-8"
        helper.createDatabase(databaseName, 7).apply {
            execSQL("INSERT INTO ai_reports(id,provider,mode,model,title,summary,messagesJson,createdAt,updatedAt) VALUES('local-session','LOCAL','FAST','Caesar local','会话','摘要','[]',100,200)")
            execSQL("INSERT INTO ai_reports(id,provider,mode,model,title,summary,messagesJson,createdAt,updatedAt) VALUES('cloud-session','GOOGLE_GEMINI','FAST','gemini-test','会话','摘要','[]',100,200)")
            close()
        }

        helper.runMigrationsAndValidate(databaseName, 8, true, CampusDatabase.MIGRATION_7_8).use { database ->
            database.query("SELECT id,executionEngine,requestId FROM ai_reports ORDER BY id").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("cloud-session", cursor.getString(0))
                assertEquals("CLOUD_OPENAI_COMPATIBLE", cursor.getString(1))
                assertEquals("legacy:cloud-session", cursor.getString(2))
                assertEquals(true, cursor.moveToNext())
                assertEquals("local-session", cursor.getString(0))
                assertEquals("LOCAL_MNN", cursor.getString(1))
                assertEquals("legacy:local-session", cursor.getString(2))
            }
        }
    }
}
