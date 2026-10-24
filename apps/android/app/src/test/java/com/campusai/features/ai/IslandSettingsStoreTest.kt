package com.campusai.features.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.campusai.features.schedule.CourseReminderConfig
import com.campusai.features.schedule.CourseReminderStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IslandSettingsStoreTest {
    @Test fun `existing opt in is inherited and each type and owner can then be controlled independently`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val courses = CourseReminderStore(context)
        courses.save("owner", CourseReminderConfig(xiaomi = true))
        val store = IslandSettingsStore(context)
        assertEquals(IslandSettings(true, true), store.read("owner"))
        store.save("owner", IslandSettings(ai = false, healthAutomation = true))
        courses.save("owner", CourseReminderConfig(xiaomi = false))
        assertEquals(IslandSettings(false, true), store.read("owner"))
        assertEquals(IslandSettings(), store.read("another-owner"))
        assertFalse(courses.read("owner").xiaomi)
        store.save("owner", store.read("owner").copy(healthContentPreview = false))
        assertFalse(store.read("owner").healthContentPreview)
        assertTrue(store.read("another-owner").healthContentPreview)
    }
}
