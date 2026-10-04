package com.campusai.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.campusai.core.designsystem.CampusTheme
import com.campusai.core.model.DailyContributionCalculator
import com.campusai.core.model.ThemeMode
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xxhdpi")
class ContributionGridUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `visible weeks stay bounded while all dates remain reachable and selectable`() {
        val today = LocalDate.of(2026, 10, 5)
        val start = LocalDate.of(2025, 12, 29)
        val days = DailyContributionCalculator.calculate(2026, emptyList(), today = today).associateBy { it.date }
        val weeks = List(53) { week -> List(7) { day -> days[start.plusDays(week * 7L + day)] } }
        var selected: LocalDate? = null
        compose.setContent {
            CampusTheme(ThemeMode.LIGHT) {
                Box(Modifier.fillMaxWidth()) { ContributionGrid(2026, start, weeks, today, null) { selected = it.date } }
            }
        }
        assertTrue(compose.onAllNodes(hasContentDescription("分钟", substring = true)).fetchSemanticsNodes().size < 240)
        compose.onNodeWithContentDescription("10月5日，0分钟，0条记录").performClick()
        compose.runOnIdle { assertEquals(today, selected) }
        compose.onNodeWithTag("contribution-weeks").performScrollToIndex(0)
        compose.onNodeWithContentDescription("1月1日，0分钟，0条记录").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(LocalDate.of(2026, 1, 1), selected) }
        compose.onNodeWithTag("contribution-weeks").performScrollToIndex(52)
        compose.onNodeWithContentDescription("12月31日，未来日期").assertIsDisplayed().assertHasNoClickAction()
    }
}
