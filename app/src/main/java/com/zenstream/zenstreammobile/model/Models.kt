package com.zenstream.zenstreammobile.model

data class AuthSession(
    val serverUrl: String,
    val token: String,
    val userId: String,
    val username: String,
    val resourceTicket: String? = null,
)

data class MediaChapter(
    val startPositionTicks: Long,
    val name: String? = null,
)

data class MediaItem(
    val id: String,
    val name: String,
    val type: String? = null,
    val seriesName: String? = null,
    val seriesId: String? = null,
    val seasonId: String? = null,
    val parentId: String? = null,
    val libraryId: String? = null,
    val lastAddedAt: String? = null,
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
    val imageBlurHashes: Map<String, String> = emptyMap(),
    val backdropImageTags: List<String> = emptyList(),
    val seriesPrimaryImageTag: String? = null,
    val seriesPrimaryImageBlurHash: String? = null,
    val played: Boolean = false,
    val favorite: Boolean = false,
    val unplayedItemCount: Int? = null,
    val playedPercentage: Double? = null,
    val playbackPositionTicks: Long? = null,
    val chapters: List<MediaChapter> = emptyList(),
)

data class MediaPerson(
    val name: String,
    val role: String? = null,
    val type: String? = null,
    val primaryImageTag: String? = null,
    val id: String? = null,
    val creditType: String? = null,
    val imageBlurHash: String? = null,
)

data class Library(
    val id: String,
    val name: String,
    val collectionType: String?,
    val supportsLastAdded: Boolean = false,
)

data class MediaRow(
    val title: RowTitle,
    val libraryName: String? = null,
    val items: List<MediaItem>,
    val wide: Boolean = false,
    val stackEpisodes: Boolean = false,
    val label: String? = null,
    val key: String = "${title.name}:${libraryName.orEmpty()}:${label.orEmpty()}",
)

enum class RowTitle {
    ContinueWatching,
    NextUp,
    MyList,
    Genre,
    NewlyAdded,
}

data class HomeData(
    val featured: List<MediaItem> = emptyList(),
    val rows: List<MediaRow> = emptyList(),
)

data class DerivedHomeData(
    val myList: List<MediaItem> = emptyList(),
    val recentlyPlayed: List<MediaItem> = emptyList(),
    val genreRows: List<MediaRow> = emptyList(),
) {
    fun rows(): List<MediaRow> =
        listOfNotNull(
            myList.takeIf { it.isNotEmpty() }?.let { MediaRow(RowTitle.MyList, items = it) },
        ) + genreRows.filter { it.items.isNotEmpty() }
}

fun orderedHomeRows(rows: List<MediaRow>): List<MediaRow> = rows.sortedBy { row ->
    when {
        row.title == RowTitle.ContinueWatching -> 0
        row.title == RowTitle.NextUp -> 1
        row.libraryName != null -> 2
        row.title == RowTitle.MyList -> 3
        row.title == RowTitle.Genre -> 4
        else -> 5
    }
}

data class LibraryData(
    val library: Library,
    val rows: List<MediaRow>,
)

enum class LibrarySortBy(val apiValue: String) {
    Rating("rating"),
    Title("title"),
    Added("added"),
    LastAdded("lastAdded"),
    Release("release"),
    Runtime("runtime"),
}

enum class SortOrder(val apiValue: String) {
    Ascending("Ascending"),
    Descending("Descending"),
}

data class LibrarySort(
    val sortBy: LibrarySortBy = LibrarySortBy.LastAdded,
    val sortOrder: SortOrder = SortOrder.Descending,
)

data class PagedLibrary(
    val library: Library,
    val items: List<MediaItem>,
    val totalRecordCount: Int,
)

data class DetailData(
    val item: MediaItem,
    val parentSeries: MediaItem? = null,
    val seasons: List<MediaItem> = emptyList(),
    val episodes: List<MediaItem> = emptyList(),
    val similar: List<MediaItem> = emptyList(),
    val selectedSeasonId: String? = null,
)
