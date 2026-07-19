package com.zenstream.zenstreammobile.model

data class AuthSession(
    val serverUrl: String,
    val token: String,
    val userId: String,
    val username: String,
)

data class MediaItem(
    val id: String,
    val name: String,
    val type: String? = null,
    val seriesName: String? = null,
    val seriesId: String? = null,
    val seasonId: String? = null,
    val parentId: String? = null,
    val parentIndexNumber: Int? = null,
    val indexNumber: Int? = null,
    val overview: String? = null,
    val premiereDate: String? = null,
    val productionYear: Int? = null,
    val officialRating: String? = null,
    val communityRating: Double? = null,
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val people: List<MediaPerson> = emptyList(),
    val recursiveItemCount: Int? = null,
    val runtimeTicks: Long? = null,
    val imageTags: Map<String, String> = emptyMap(),
    val backdropImageTags: List<String> = emptyList(),
    val seriesPrimaryImageTag: String? = null,
    val played: Boolean = false,
    val favorite: Boolean = false,
    val unplayedItemCount: Int? = null,
    val playedPercentage: Double? = null,
    val playbackPositionTicks: Long? = null,
)

data class MediaPerson(
    val name: String,
    val role: String? = null,
    val type: String? = null,
    val primaryImageTag: String? = null,
)

data class Library(
    val id: String,
    val name: String,
    val collectionType: String?,
)

data class MediaRow(
    val title: RowTitle,
    val libraryName: String? = null,
    val items: List<MediaItem>,
    val wide: Boolean = false,
)

enum class RowTitle { ContinueWatching, NextUp, NewlyAdded, TopRated, NewReleases }

data class HomeData(
    val featured: List<MediaItem> = emptyList(),
    val rows: List<MediaRow> = emptyList(),
)

data class LibraryData(
    val library: Library,
    val rows: List<MediaRow>,
)

data class DetailData(
    val item: MediaItem,
    val parentSeries: MediaItem? = null,
    val seasons: List<MediaItem> = emptyList(),
    val episodes: List<MediaItem> = emptyList(),
    val similar: List<MediaItem> = emptyList(),
    val selectedSeasonId: String? = null,
)
