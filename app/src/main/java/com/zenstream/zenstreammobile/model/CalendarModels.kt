package com.zenstream.zenstreammobile.model

data class CalendarEvent(
    val id: String,
    val provider: String,
    val libraryId: String,
    val libraryName: String,
    val kind: String,
    val releaseType: String,
    val eventAt: String,
    val eventDate: String,
    val allDay: Boolean,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val hasFile: Boolean = false,
    val monitored: Boolean = false,
    val state: String = "future",
    val title: String? = null,
    val seriesTitle: String? = null,
    val catalogItemId: String? = null,
    val catalogSeriesId: String? = null,
    val metadataStatus: String = "future",
    val following: Boolean = false,
    val followAvailable: Boolean = false,
)

data class CalendarResponse(
    val start: String,
    val end: String,
    val events: List<CalendarEvent> = emptyList(),
)
