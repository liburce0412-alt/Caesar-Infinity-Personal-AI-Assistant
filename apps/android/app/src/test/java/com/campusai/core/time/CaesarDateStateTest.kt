package com.campusai.core.time

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaesarDateStateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `explicit clock stays isolated from the unchanged system default`() {
        val before = CaesarTime.today()
        val fixed = Clock.fixed(Instant.parse("1999-12-31T16:00:00Z"), ZoneOffset.UTC)
        var injectedDate: LocalDate? = null
        var systemDate: LocalDate? = null
        compose.setContent {
            val injected by rememberCaesarDate(fixed)
            val system by rememberCaesarDate()
            injectedDate = injected
            systemDate = system
        }
        compose.runOnIdle {
            assertEquals(LocalDate.of(2000, 1, 1), injectedDate)
            assertTrue(systemDate in setOf(before, CaesarTime.today()))
        }
    }
}
