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
    val parentIndexNumber: Int? = null,
    val indexNumber: Int? = null,
    val overview: String? = null,
    val productionYear: Int? = null,
    val officialRating: String? = null,
    val communityRating: Double? = null,
    val runtimeTicks: Long? = null,
    val imageTags: Map<String, String> = emptyMap(),
    val backdropImageTags: List<String> = emptyList(),
    val seriesPrimaryImageTag: String? = null,
    val played: Boolean = false,
    val unplayedItemCount: Int? = null,
    val playedPercentage: Double? = null,
    val playbackPositionTicks: Long? = null,
)

data class Library(
    val id: String,
    val name: String,
    val collectionType: String?,
)

data class MediaRow(
    val title: String,
    val items: List<MediaItem>,
    val wide: Boolean = false,
)

data class HomeData(
    val featured: List<MediaItem> = emptyList(),
    val rows: List<MediaRow> = emptyList(),
)

data class LibraryData(
    val library: Library,
    val rows: List<MediaRow>,
)
