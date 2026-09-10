package com.campusai.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.campusai.core.model.AiProvider
import com.campusai.core.model.MotionMode
import com.campusai.core.model.RenderQuality
import com.campusai.core.model.SpectraEnvironment
import com.campusai.core.model.ThemeMode
import com.campusai.core.designsystem.SpectraVisualStyle
import com.campusai.core.designsystem.GlassEffects
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.campusPreferences by preferencesDataStore("campusai_user_preferences")

data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val motionMode: MotionMode = MotionMode.ON,
    val renderQuality: RenderQuality = RenderQuality.AUTO,
    val environment: SpectraEnvironment = SpectraEnvironment.ORIGINAL,
    val soundEnabled: Boolean = true,
    val aiProvider: AiProvider = AiProvider.AUTO,
    val localModelWifiOnly: Boolean = true,
    /** Selects the complete Caesar visual system, not only the renderer background. */
    val visualStyle: SpectraVisualStyle = SpectraVisualStyle.CLASSIC,
    val glassEffects: GlassEffects = GlassEffects(),
    val collapsedComponents: Set<String> = emptySet(),
    val onboardingCompleted: Boolean = false,
)

class UserPreferencesRepository(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme_mode")
        val motion = stringPreferencesKey("motion_mode")
        val quality = stringPreferencesKey("render_quality")
        val environment = stringPreferencesKey("spectra_environment")
        val sound = booleanPreferencesKey("sound_enabled")
        val aiProvider = stringPreferencesKey("ai_provider")
        val localModelWifiOnly = booleanPreferencesKey("local_model_wifi_only")
        val visualStyle = stringPreferencesKey("spectra_visual_style")
        val glassDeformation = booleanPreferencesKey("glass_deformation")
        val glassRimLight = booleanPreferencesKey("glass_rim_light")
        val glassAurora = booleanPreferencesKey("glass_aurora")
        val glassMeteors = booleanPreferencesKey("glass_meteors")
        val collapsedComponents = stringSetPreferencesKey("collapsed_components")
        val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
    }

    val preferences: Flow<UserPreferences> = context.campusPreferences.data.map { values ->
        UserPreferences(
            collapsedComponents = values[Keys.collapsedComponents].orEmpty(),
            onboardingCompleted = values[Keys.onboardingCompleted] ?: false,
            themeMode = values[Keys.theme].toEnumOr(ThemeMode.SYSTEM),
            motionMode = values[Keys.motion].toEnumOr(MotionMode.ON),
            renderQuality = values[Keys.quality].toEnumOr(RenderQuality.AUTO),
            environment = values[Keys.environment].toEnumOr(SpectraEnvironment.ORIGINAL),
            soundEnabled = values[Keys.sound] ?: true,
            aiProvider = values[Keys.aiProvider].toEnumOr(AiProvider.AUTO),
            localModelWifiOnly = values[Keys.localModelWifiOnly] ?: true,
            visualStyle = values[Keys.visualStyle].toEnumOr(SpectraVisualStyle.CLASSIC),
            glassEffects = GlassEffects(
                deformation = values[Keys.glassDeformation] ?: true,
                rimLight = values[Keys.glassRimLight] ?: true,
                aurora = values[Keys.glassAurora] ?: true,
                meteors = values[Keys.glassMeteors] ?: true,
            ),
        )
    }

    suspend fun setTheme(value: ThemeMode) = context.campusPreferences.edit { it[Keys.theme] = value.name }
    suspend fun setComponentCollapsed(id: String, collapsed: Boolean) = context.campusPreferences.edit {
        val current = it[Keys.collapsedComponents].orEmpty()
        it[Keys.collapsedComponents] = if (collapsed) current + id else current - id
    }
    suspend fun completeOnboarding() = context.campusPreferences.edit { it[Keys.onboardingCompleted] = true }
    suspend fun setMotion(value: MotionMode) = context.campusPreferences.edit { it[Keys.motion] = value.name }
    suspend fun setQuality(value: RenderQuality) = context.campusPreferences.edit { it[Keys.quality] = value.name }
    suspend fun setEnvironment(value: SpectraEnvironment) = context.campusPreferences.edit { it[Keys.environment] = value.name }
    suspend fun setSound(value: Boolean) = context.campusPreferences.edit { it[Keys.sound] = value }
    suspend fun setAiProvider(value: AiProvider) = context.campusPreferences.edit { it[Keys.aiProvider] = value.name }
    suspend fun setLocalModelWifiOnly(value: Boolean) = context.campusPreferences.edit { it[Keys.localModelWifiOnly] = value }
    suspend fun setVisualStyle(value: SpectraVisualStyle) = context.campusPreferences.edit { it[Keys.visualStyle] = value.name }
    suspend fun setGlassDeformation(value: Boolean) = context.campusPreferences.edit { it[Keys.glassDeformation] = value }
    suspend fun setGlassRimLight(value: Boolean) = context.campusPreferences.edit { it[Keys.glassRimLight] = value }
    suspend fun setGlassAurora(value: Boolean) = context.campusPreferences.edit { it[Keys.glassAurora] = value }
    suspend fun setGlassMeteors(value: Boolean) = context.campusPreferences.edit { it[Keys.glassMeteors] = value }
}

private inline fun <reified T : Enum<T>> String?.toEnumOr(fallback: T): T =
    this?.let { raw -> enumValues<T>().firstOrNull { it.name == raw } } ?: fallback
