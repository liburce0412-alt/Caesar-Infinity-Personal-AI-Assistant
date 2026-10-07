package com.campusai.debug

import android.app.Activity
import android.os.Bundle
import com.campusai.core.database.CampusDatabase
import com.campusai.features.schedule.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import androidx.room.withTransaction
import com.campusai.core.database.CourseScheduleEntity
import com.campusai.core.model.CourseSchedule

/** ADB-only diagnostics. No exported release endpoint and no synthetic database courses. */
class CourseReminderProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val report = JSONObject()
            try {
                val owner = CourseReminderRuntime.owner(app)
                val store = CourseReminderStore(app)
                val database = CampusDatabase.getDatabase(app)
                val dao = database.campusDao()
                when (intent.getStringExtra("operation")) {
                    "request-island-access" -> withContext(Dispatchers.Main) { CourseIslandAccess.requestPermission() }
                    "test-island-recovery" -> {
                        check(CourseIslandAccess.ready())
                        CourseIslandAccess.publish { acquired ->
                            check(acquired)
                            android.util.Log.i("CourseIsland", "Testing client death while native lease is held")
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }
                    }
                    "preview-island" -> {
                        val now = System.currentTimeMillis()
                        val secondary = intent.getBooleanExtra("secondary", false)
                        val active = intent.getBooleanExtra("active", true)
                        val start = if (active) now - 20 * 60_000 else now + 15 * 60_000
                        val item = CourseOccurrence(CourseSchedule(id = -1, name = if (secondary) "小岛显示测试" else "Java 程序设计", weekday = 3,
                            startMinute = 480, endMinute = 575, location = "数字经济实训楼 · S402",
                            periodStart = 1, periodEnd = 2, sourceHash = "preview"), java.time.LocalDate.now(), start, start + 45 * 60_000)
                        CourseReminderNotifications.ensureChannels(app)
                        val notification = CourseReminderNotifications.build(app, owner, item,
                            CourseReminderConfig(live = false, xiaomi = true,
                                islandAppearance = if (intent.getStringExtra("appearance") == "colorful")
                                    CourseIslandAppearance.COLORFUL else CourseIslandAppearance.AURORA), now)
                        val overrideFile = File(app.filesDir, "course-island-preview.json")
                        if (intent.getBooleanExtra("useTemplateFile", false) && overrideFile.exists()) {
                            notification.extras.putString("miui.focus.param", overrideFile.readText())
                        }
                        val params = JSONObject(notification.extras.getString("miui.focus.param")!!)
                        val body = params.getJSONObject("param_v2")
                        if (secondary) body.put("business", "campusai_course_secondary")
                        when (intent.getStringExtra("background")) {
                            "system" -> body.remove("bgInfo")
                            "white" -> body.put("bgInfo", JSONObject().put("type", 1).put("colorBg", "#FFFFFF"))
                            "image" -> {
                                val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                notification.extras.getBundle("miui.focus.pics")!!.putParcelable("miui.focus.pic_bg",
                                    android.graphics.drawable.Icon.createWithBitmap(bitmap))
                                body.put("bgInfo", JSONObject().put("type", 1)
                                    .put("picBg", "miui.focus.pic_bg").put("colorBg", "#FFFFFF"))
                            }
                        }
                        notification.extras.putString("miui.focus.param", params.toString())
                        File(app.getExternalFilesDir(null), "course-island-preview.json")
                            .writeText(notification.extras.getString("miui.focus.param")!!)
                        val preview = android.app.Notification.Builder.recoverBuilder(app, notification).apply {
                            if (android.os.Build.VERSION.SDK_INT >= 26) setTimeoutAfter(if (secondary) 60_000 else 5 * 60_000)
                        }.build()
                        report.put("binderReadyBeforePost", CourseIslandAccess.awaitReady())
                        CourseIslandAccess.publish { acquired ->
                            report.put("nativeLease", acquired)
                            app.getSystemService(android.app.NotificationManager::class.java)
                                .notify("course-style-preview", if (secondary) 741198 else 741199, preview)
                        }
                    }
                    "cancel-preview" -> app.getSystemService(android.app.NotificationManager::class.java).let {
                        it.cancel("course-style-preview", 741199)
                        it.cancel("course-style-preview", 741198)
                    }
                    "demo" -> CourseReminderRuntime.demo(app, intent.getStringExtra("mode") ?: "auto")
                    "cancel-demo" -> { store.clearDemo(); CourseReminderRuntime.refresh(app) }
                    "configure" -> CourseReminderRuntime.save(app, owner, store.read(owner).copy(
                        semesterMonday = requireNotNull(intent.getStringExtra("monday")),
                        enabled = intent.getBooleanExtra("enabled", false),
                        live = intent.getBooleanExtra("live", store.read(owner).live),
                        xiaomi = intent.getBooleanExtra("xiaomi", store.read(owner).xiaomi)))
                    "apply-reviewed-courses" -> {
                        val input = JSONObject(File(app.filesDir, "reviewed-course-updates.json").readText())
                        val changes = input.getJSONArray("updates")
                        val additions = input.getJSONArray("additions")
                        fun course(json: JSONObject, id: Int, hash: String) = CourseSchedule(id = id,
                            name = json.getString("name"), weekday = json.getInt("weekday"),
                            startMinute = json.getInt("startMinute"), endMinute = json.getInt("endMinute"),
                            weeks = json.getString("weeks"), teacher = json.getString("teacher"), location = json.getString("location"),
                            periodStart = json.getInt("periodStart"), periodEnd = json.getInt("periodEnd"),
                            periodStartTimes = json.getString("periodStartTimes"), sourceHash = hash)
                        database.withTransaction {
                            for (i in 0 until changes.length()) {
                                val change = changes.getJSONObject(i)
                                val existing = requireNotNull(dao.courseById(change.getInt("id")))
                                check(existing.sourceHash == change.getString("expectedSourceHash"))
                                check(existing.weeks == change.getString("expectedWeeks"))
                                check(CourseReminderRuntime.owner(app) == owner)
                                val value = course(change, existing.id, existing.sourceHash)
                                validateCourseEdit(value)
                                check(dao.editOwnedCourse(value, owner))
                            }
                            for (i in 0 until additions.length()) {
                                val item = additions.getJSONObject(i)
                                val value = course(item, 0, item.getString("sourceHash"))
                                validateCourseEdit(value)
                                dao.importCourseSchedules(listOf(CourseScheduleEntity.fromDomain(value, owner)))
                            }
                        }
                        CourseReminderRuntime.refresh(app)
                        report.put("updated", changes.length()).put("added", additions.length())
                    }
                }
                val courses = dao.getCourseSchedulesFlow(owner, owner != "local_user").first()
                report.put("protocol", CourseReminderNotifications.protocol(app))
                    .put("shizukuReady", CourseIslandAccess.ready())
                    .put("focusAllowed", CourseReminderNotifications.focusAllowed(app))
                    .put("exactAllowed", CourseReminderRuntime.canScheduleExactly(app))
                    .put("notificationAllowed", CourseReminderNotifications.enabled(app))
                    .put("courses", JSONArray().apply { courses.forEach { row ->
                        val c = row.toDomain()
                        put(JSONObject().put("id", c.id).put("name", c.name).put("weekday", c.weekday)
                            .put("startMinute", c.startMinute).put("endMinute", c.endMinute).put("weeks", c.weeks)
                            .put("periodStart", c.periodStart).put("periodEnd", c.periodEnd).put("location", c.location)
                            .put("teacher", c.teacher).put("sourceHash", c.sourceHash).put("periodStartTimes", c.periodStartTimes)
                            .put("issue", reminderIssue(c, store.read(owner))))
                    } })
            } catch (failure: Exception) { report.put("error", failure.stackTraceToString()) }
            File(app.getExternalFilesDir(null), "course-reminder-report.json").writeText(report.toString(2))
            withContext(Dispatchers.Main) { finish() }
        }
    }
}
