package com.campusai.core.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExperiencePreferencesTest {
    @Test fun `component choices and completed guide persist independently`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = UserPreferencesRepository(context)
        assertFalse(repository.preferences.first().onboardingCompleted)
        repository.setComponentCollapsed("HEALTH", true)
        repository.setComponentCollapsed("INSIGHTS", true)
        repository.completeOnboarding()
        repository.setComponentCollapsed("HEALTH", false)
        val restored = UserPreferencesRepository(context).preferences.first()
        assertTrue(restored.onboardingCompleted)
        assertEquals(setOf("INSIGHTS"), restored.collapsedComponents)
    }
}
