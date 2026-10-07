package com.campusai.features.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.*
import com.campusai.core.auth.AuthRepository
import com.campusai.core.database.CampusDatabase
import com.campusai.core.model.CourseSchedule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

internal class CourseReminderStore(context: Context) {
    private val prefs = context.getSharedPreferences("course_reminders_v1", Context.MODE_PRIVATE)
    fun read(owner: String): CourseReminderConfig = runCatching {
        val json = JSONObject(prefs.getString("config:$owner", "{}")!!)
        CourseReminderConfig(json.optBoolean("enabled"), json.optString("monday"),
            json.optInt("weeks", 20).coerceIn(1, 30), json.optInt("lead", 15).coerceIn(0, 60),
            json.optBoolean("live", false), json.optBoolean("xiaomi", false),
            CourseIslandAppearance.entries.firstOrNull { it.name == json.optString("islandAppearance") }
                ?: CourseIslandAppearance.AURORA)
    }.getOrDefault(CourseReminderConfig())

    fun save(owner: String, value: CourseReminderConfig) {
        check(prefs.edit().putString("config:$owner", JSONObject().put("enabled", value.enabled)
            .put("monday", value.semesterMonday).put("weeks", value.semesterWeeks)
            .put("lead", value.leadMinutes).put("live", value.live).put("xiaomi", value.xiaomi)
            .put("islandAppearance", value.islandAppearance.name).toString()).commit())
    }
    fun dismissed(owner: String): Set<String> = prefs.getStringSet("dismissed:$owner", emptySet())!!.toSet()
    fun dismiss(owner: String, key: String) {
        prefs.edit().putStringSet("dismissed:$owner", dismissed(owner) + key).commit()
    }
    fun prune(owner: String, valid: Set<String>) {
        prefs.edit().putStringSet("dismissed:$owner", dismissed(owner).intersect(valid)).apply()
    }
    fun demo(owner: String, now: Long, mode: String) {
        prefs.edit().putString("demoOwner", owner).putLong("demoStart", now + 30_000)
            .putLong("demoEnd", now + 90_000).putString("demoMode", mode).commit()
    }
    fun clearDemo() { prefs.edit().remove("demoOwner").remove("demoStart").remove("demoEnd").apply() }
    fun demoMode(): String = prefs.getString("demoMode", "auto")!!
    fun demoOccurrence(owner: String, now: Long): CourseOccurrence? {
        if (prefs.getString("demoOwner", null) != owner || prefs.getLong("demoEnd", 0) <= now) return null
        val start = prefs.getLong("demoStart", 0)
        val end = prefs.getLong("demoEnd", 0)
        val date = java.time.Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate()
        return CourseOccurrence(CourseSchedule(id = -1, name = "课程提醒演示", weekday = date.dayOfWeek.value,
            startMinute = 0, endMinute = 1, location = "示例教室 · 不影响真实课表", weeks = date.toString(), sourceHash = "demo"), date, start, end)
    }
}

internal object CourseReminderRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var observer: Job? = null
    const val ACTION_DISMISS = "com.campusai.course.DISMISS"
    const val ACTION_TICK = "com.campusai.course.TICK"

    fun owner(context: Context): String = AuthRepository.getInstance(context).state.value.let {
        it.userId.takeIf { _ -> it.signedIn && it.userId.isNotBlank() } ?: "local_user"
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Synchronized fun start(context: Context) {
        if (observer?.isActive == true) return
        val app = context.applicationContext
        observer = scope.launch {
            AuthRepository.getInstance(app).state.map { it.userId.takeIf { _ -> it.signedIn } ?: "local_user" }
                .distinctUntilChanged().flatMapLatest { id ->
                    CampusDatabase.getDatabase(app).campusDao().getCourseSchedulesFlow(id, id != "local_user")
                }.collect { refreshSafely(app) }
        }
    }

    fun requestRefresh(context: Context) { scope.launch { refreshSafely(context.applicationContext) } }

    private suspend fun refreshSafely(context: Context) {
        try { refresh(context) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { android.util.Log.e("CourseReminder", "Reminder refresh failed", failure) }
    }

    suspend fun save(context: Context, expectedOwner: String, config: CourseReminderConfig) = mutex.withLock {
        check(owner(context) == expectedOwner) { "账号已切换，请重新打开设置" }
        CourseReminderStore(context).save(expectedOwner, config)
        refreshLocked(context)
    }

    suspend fun demo(context: Context, mode: String = "auto") = mutex.withLock {
        CourseReminderStore(context).demo(owner(context), System.currentTimeMillis(), mode)
        refreshLocked(context)
    }

    suspend fun dismiss(context: Context, expectedOwner: String, key: String, demo: Boolean) = mutex.withLock {
        if (owner(context) != expectedOwner) return@withLock
        val store = CourseReminderStore(context)
        if (demo) store.clearDemo() else store.dismiss(expectedOwner, key)
        refreshLocked(context)
    }

    suspend fun refresh(context: Context) = mutex.withLock { refreshLocked(context) }

    private suspend fun refreshLocked(context: Context) {
        val app = context.applicationContext
        val user = owner(app)
        val store = CourseReminderStore(app)
        val config = store.read(user)
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val date = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val rows = if (config.enabled) CampusDatabase.getDatabase(app).campusDao()
            .getCourseSchedulesFlow(user, user != "local_user").first().map { it.toDomain() } else emptyList()
        if (owner(app) != user) {
            CourseReminderNotifications.cancel(app, false)
            CourseReminderNotifications.cancel(app, true)
            return
        }
        val all = courseOccurrences(rows, config, date, zone).filter { it.end > now }
        store.prune(user, all.map { it.key }.toSet())
        val valid = all.filter { it.key !in store.dismissed(user) }
        val active = valid.firstOrNull { now >= it.start && now < it.end }
            ?: valid.firstOrNull { now >= it.start - config.leadMinutes * 60_000L && now < it.start }
        val demo = store.demoOccurrence(user, now)

        val boundaries = valid.flatMap { listOf(it.start - config.leadMinutes * 60_000L, it.start, it.end) } +
            listOfNotNull(demo?.start, demo?.end)
        val tomorrow = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val next = (boundaries + tomorrow).filter { it > now }.minOrNull()!!
        val alarm = app.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(app, 741001,
            Intent(app, CourseReminderReceiver::class.java).setAction(ACTION_TICK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        if (config.enabled || demo != null) {
            try {
                if (canScheduleExactly(app)) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
                else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
            } catch (_: SecurityException) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
            }
        }
        // Cosmetic progress refreshes never wake a sleeping phone. System timers tick without app work.
        val progressPending = PendingIntent.getBroadcast(app, 741002,
            Intent(app, CourseReminderReceiver::class.java).setAction(ACTION_TICK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(progressPending)
        if (config.xiaomi && active != null && now >= active.start) {
            val progressAt = (now / 300_000 + 1) * 300_000
            if (progressAt < active.end) alarm.set(AlarmManager.RTC, progressAt, progressPending)
        }
        // Recovery only. Course transition timing belongs to AlarmManager, not periodic work.
        if (config.enabled) WorkManager.getInstance(app).enqueueUniquePeriodicWork("course-reminder-recovery",
            ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<CourseReminderWorker>(12, TimeUnit.HOURS).build())
        else WorkManager.getInstance(app).cancelUniqueWork("course-reminder-recovery")

        // Arm the next boundary before optional system integrations can fail or lose their binder.
        if (active != null) CourseReminderNotifications.publish(app, user, active, config, now)
        else CourseReminderNotifications.cancel(app, false)
        if (demo != null) {
            val mode = store.demoMode()
            CourseReminderNotifications.publish(app, user, demo,
                config.copy(live = mode != "normal" && mode != "xiaomi", xiaomi = mode != "normal" && mode != "live"), now)
        } else CourseReminderNotifications.cancel(app, true)
    }

    fun canScheduleExactly(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun receive(context: Context, intent: Intent, finish: () -> Unit) {
        scope.launch {
            try {
                if (intent.action == ACTION_DISMISS) dismiss(context, intent.getStringExtra("owner").orEmpty(),
                    intent.getStringExtra("key").orEmpty(), intent.getBooleanExtra("demo", false))
                else refresh(context)
            } catch (failure: Exception) {
                android.util.Log.e("CourseReminder", "Reminder refresh failed", failure)
            } finally { finish() }
        }
    }
}

class CourseReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(CourseReminderRuntime.ACTION_TICK, CourseReminderRuntime.ACTION_DISMISS,
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        val pending = goAsync()
        CourseReminderRuntime.receive(context.applicationContext, intent, pending::finish)
    }
}

class CourseReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        CourseReminderRuntime.refresh(applicationContext)
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }
}
