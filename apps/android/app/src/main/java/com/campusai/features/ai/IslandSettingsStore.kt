package com.campusai.features.ai

import android.content.Context
import android.content.SharedPreferences
import com.campusai.features.schedule.CourseReminderStore

data class IslandSettings(
    val ai: Boolean = false,
    val healthAutomation: Boolean = false,
    val healthContentPreview: Boolean = true,
)

/** Per-account switches; absent values inherit the previously shared course opt-in. */
class IslandSettingsStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("island_settings_v1", Context.MODE_PRIVATE)

    fun read(owner: String): IslandSettings {
        val previous = CourseReminderStore(app).read(owner).xiaomi
        return IslandSettings(prefs.getBoolean("ai:$owner", previous), prefs.getBoolean("health:$owner", previous),
            prefs.getBoolean("health_preview:$owner", true))
    }

    fun save(owner: String, settings: IslandSettings) {
        check(prefs.edit().putBoolean("ai:$owner", settings.ai)
            .putBoolean("health:$owner", settings.healthAutomation)
            .putBoolean("health_preview:$owner", settings.healthContentPreview).commit())
    }

    fun observe(owner: String, onChanged: (IslandSettings) -> Unit): AutoCloseable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "ai:$owner" || key == "health:$owner" || key == "health_preview:$owner") onChanged(read(owner))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        val legacy = CourseReminderStore(app).observe(owner) { onChanged(read(owner)) }
        onChanged(read(owner))
        return AutoCloseable { prefs.unregisterOnSharedPreferenceChangeListener(listener); legacy.close() }
    }
}
