package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.CalendarEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarTest {
    @Test
    fun parsesCalendarEventsAndDropsDuplicateIds() {
        val response =
            parseCalendarResponse(
                JSONObject()
                    .put("start", "2026-08-16T00:00:00Z")
                    .put("end", "2026-08-23T00:00:00Z")
                    .put(
                        "events",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("id", "episode-1")
                                    .put("provider", "sonarr")
                                    .put("libraryId", "shows")
                                    .put("libraryName", "Shows")
                                    .put("kind", "episode")
                                    .put("releaseType", "premiere")
                                    .put("eventAt", "2026-08-21T18:00:00+00:00")
                                    .put("eventDate", "2026-08-21")
                                    .put("seasonNumber", 2)
                                    .put("episodeNumber", 3)
                                    .put("hasFile", true)
                                    .put("state", "existing")
                                    .put("title", "The Return")
                                    .put("seriesTitle", "Example Show")
                                    .put("catalogItemId", "catalog-episode-1")
                                    .put("catalogSeriesId", "catalog-series-1")
                                    .put("metadataStatus", "catalog")
                                    .put("following", true)
                                    .put("followAvailable", true)
                            )
                            .put(JSONObject().put("id", "episode-1"))
                            .put(
                                JSONObject()
                                    .put("id", "movie-1")
                                    .put("kind", "movie")
                                    .put("allDay", true)
                                    .put("eventDate", "2026-08-22")
                            ),
                    )
            )

        assertEquals("2026-08-16T00:00:00Z", response.start)
        assertEquals(listOf("episode-1", "movie-1"), response.events.map { it.id })
        assertEquals(2, response.events.first().seasonNumber)
        assertEquals(3, response.events.first().episodeNumber)
        assertEquals("Example Show", response.events.first().seriesTitle)
        assertTrue(response.events.first().following)
        assertNull(response.events.last().title)
        assertFalse(response.events.last().followAvailable)
    }

    @Test
    fun calendarWeeksStartOnSundayAndClampToSupportedWindow() {
        val today = LocalDate.of(2026, 8, 21)
        val currentWeek = LocalDate.of(2026, 8, 16)

        assertEquals(currentWeek, startOfCalendarWeek(today))
        assertEquals(LocalDate.of(2026, 8, 9), calendarBounds(today).minimumWeek)
        assertEquals(LocalDate.of(2026, 12, 6), calendarBounds(today).maximumWeek)
        assertEquals(
            LocalDate.of(2026, 8, 9),
            clampCalendarWeek(LocalDate.of(2025, 1, 1), today),
        )
        assertEquals(
            LocalDate.of(2026, 12, 6),
            moveCalendarWeek(currentWeek, 30, today),
        )
    }

    @Test
    fun eventDatesUseLocalTimeForTimedEventsAndEventDateForAllDayEvents() {
        val zone = ZoneId.of("Europe/London")
        val timed =
            CalendarEvent(
                id = "timed",
                provider = "sonarr",
                libraryId = "shows",
                libraryName = "Shows",
                kind = "episode",
                releaseType = "episode",
                eventAt = "2026-08-21T23:30:00Z",
                eventDate = "2026-08-21",
                allDay = false,
                episodeNumber = 3,
            )
        val allDay = timed.copy(id = "all-day", allDay = true, eventDate = "2026-08-21")

        assertEquals(LocalDate.of(2026, 8, 22), calendarEventDate(timed, zone))
        assertEquals(LocalDate.of(2026, 8, 21), calendarEventDate(allDay, zone))
        assertEquals("S02E03", calendarEpisodePosition(timed.copy(seasonNumber = 2)))
        assertEquals(Instant.parse("2026-08-21T23:30:00Z"), parseCalendarInstant(timed.eventAt))
    }
}
