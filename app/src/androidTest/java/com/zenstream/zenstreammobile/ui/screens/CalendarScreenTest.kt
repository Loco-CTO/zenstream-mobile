package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.model.CalendarEvent
import com.zenstream.zenstreammobile.ui.CalendarUiState
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CalendarScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private val weekStart = LocalDate.of(2026, 8, 16)
    private val event =
        CalendarEvent(
            id = "event-1",
            provider = "sonarr",
            libraryId = "shows",
            libraryName = "Shows",
            kind = "episode",
            releaseType = "episode",
            eventAt = "2026-08-21T18:00:00Z",
            eventDate = "2026-08-21",
            allDay = false,
            seasonNumber = 2,
            episodeNumber = 3,
            hasFile = true,
            state = "existing",
            title = "The Return",
            seriesTitle = "Example Show",
            catalogItemId = "catalog-episode-1",
            following = false,
            followAvailable = true,
        )

    @Test
    fun agendaRendersWeekStripEventSheetAndCatalogAction() {
        var openedItem: String? = null
        composeRule.setContent {
            ZenStreamTheme {
                CalendarContent(
                    state =
                        CalendarUiState(
                            weekStart = weekStart,
                            selectedDate = LocalDate.of(2026, 8, 21),
                            events = listOf(event),
                            loading = false,
                        ),
                    locale = Locale.US,
                    zone = ZoneId.of("UTC"),
                    onOpenItem = { openedItem = it },
                )
            }
        }

        composeRule.onNodeWithText("Example Show").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("The Return").performClick()
        composeRule.onNodeWithText("Example Show · The Return").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.info)
            )
            .performClick()
        composeRule.runOnIdle { assertEquals("catalog-episode-1", openedItem) }
    }

    @Test
    fun selectingAnotherDayUpdatesTheAgendaAndEmptyState() {
        var state by
            mutableStateOf(
                CalendarUiState(
                    weekStart = weekStart,
                    selectedDate = LocalDate.of(2026, 8, 21),
                    events = listOf(event),
                    loading = false,
                )
            )
        composeRule.setContent {
            ZenStreamTheme {
                CalendarContent(
                    state = state,
                    locale = Locale.US,
                    zone = ZoneId.of("UTC"),
                    onSelectDate = { state = state.copy(selectedDate = it) },
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("Saturday, August 22, 2026")
            .performClick()
        composeRule.onRoot().printToLog("CALENDAR_TEST")
        composeRule.onNodeWithText("No releases for Aug 22, 2026").assertIsDisplayed()
    }

    @Test
    fun initialErrorShowsRetryAction() {
        var retried = false
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            ZenStreamTheme {
                CalendarContent(
                    state = CalendarUiState(weekStart = weekStart, loading = false, error = true),
                    locale = Locale.US,
                    zone = ZoneId.of("UTC"),
                    onRefresh = { retried = true },
                )
            }
        }

        composeRule
            .onNodeWithText(context.getString(R.string.calendar_load_failed))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.retry)).performClick()
        composeRule.runOnIdle { assertEquals(true, retried) }
    }
}
