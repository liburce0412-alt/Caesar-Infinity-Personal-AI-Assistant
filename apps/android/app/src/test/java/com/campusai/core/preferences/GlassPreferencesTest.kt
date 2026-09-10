package com.campusai.core.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.campusai.core.designsystem.GlassEffects
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GlassPreferencesTest {
    @Test fun `individual switches survive a new repository without resetting other choices`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = UserPreferencesRepository(context)
        repository.setGlassDeformation(false)
        repository.setGlassRimLight(true)
        repository.setGlassAurora(false)
        repository.setGlassMeteors(true)
        assertEquals(GlassEffects(false, true, false, true), UserPreferencesRepository(context).preferences.first().glassEffects)
        repository.setGlassMeteors(false)
        assertEquals(GlassEffects(false, true, false, false), UserPreferencesRepository(context).preferences.first().glassEffects)
        repository.setGlassAurora(true)
        assertEquals(GlassEffects(false, true, true, false), UserPreferencesRepository(context).preferences.first().glassEffects)
    }
}
