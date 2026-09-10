package com.zenstream.zenstreammobile.model

data class AuthSession(
    val serverUrl: String,
    val token: String,
    val userId: String,
    val username: String,
    val resourceTicket: String? = null,
    val avatarVersion: String? = null,
    val artworkTicket: String? = null,
)

data class MediaChapter(
    val startPositionTicks: Long,
    val name: String? = null,
)

data class ArtistCredit(
    val id: String? = null,
    val name: String,
    val joinPhrase: String? = null,
)

data class MediaItem(
    val id: String,
    val name: String,
    val type: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val albumType: String? = null,
    val albumSecondaryTypes: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val contributingArtists: List<String> = emptyList(),
    val artistCredits: List<ArtistCredit> = emptyList(),
    val label: String? = null,
    val tags: List<String> = emptyList(),
    val releaseDate: String? = null,
    val show: String? = null,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val durationSeconds: Double? = null,
    val playCount: Int? = null,
    val lastPlayedAt: String? = null,
    val dateCreated: String? = null,
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
    val collectionYearRange: String? = null,
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
    /** Follow is populated for movies, series, and music artists only. */
    val following: Boolean? = null,
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
    val variant: RowVariant = RowVariant.Poster,
    val key: String = "${title.name}:${libraryName.orEmpty()}:${label.orEmpty()}",
)

enum class RowVariant {
    Poster,
    Square,
}

enum class RowTitle {
    ContinueWatching,
    NextUp,
    MyList,
    Genre,
    NewlyAdded,
    TopRated,
    FavoriteMusic,
}

data class HomeData(
    val featured: List<MediaItem> = emptyList(),
    val rows: List<MediaRow> = emptyList(),
)

data class DerivedHomeData(
    val myList: List<MediaItem> = emptyList(),
    val favoriteMusic: List<MediaItem> = emptyList(),
    val recentlyPlayed: List<MediaItem> = emptyList(),
    val genreRows: List<MediaRow> = emptyList(),
) {
    fun rows(): List<MediaRow> =
        listOfNotNull(
            myList.takeIf { it.isNotEmpty() }?.let { MediaRow(RowTitle.MyList, items = it) }
        ) +
            listOfNotNull(
                favoriteMusic
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        MediaRow(
                            RowTitle.FavoriteMusic,
                            items = it,
                            variant = RowVariant.Square,
                        )
                    }
            ) +
            genreRows.filter { it.items.isNotEmpty() }
}

fun orderedHomeRows(rows: List<MediaRow>): List<MediaRow> = rows.sortedBy { row ->
    when {
        row.title == RowTitle.ContinueWatching -> 0
        row.title == RowTitle.NextUp -> 1
        row.title == RowTitle.NewlyAdded -> 2
        row.title == RowTitle.TopRated -> 3
        row.title == RowTitle.MyList -> 4
        row.title == RowTitle.FavoriteMusic -> 5
        row.title == RowTitle.Genre -> 6
        else -> 6
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
    Year("year"),
}

enum class SortOrder(val apiValue: String) {
    Ascending("Ascending"),
    Descending("Descending"),
}

data class LibrarySort(
    val sortBy: LibrarySortBy = LibrarySortBy.LastAdded,
    val sortOrder: SortOrder = SortOrder.Descending,
)

enum class FavoriteSortBy(val apiValue: String) {
    Title("title"),
    DateAdded("dateAdded"),
}

data class FavoriteSort(
    val sortBy: FavoriteSortBy = FavoriteSortBy.Title,
    val sortOrder: SortOrder = SortOrder.Ascending,
)

data class PagedFavorites(
    val items: List<MediaItem>,
    val totalRecordCount: Int,
)

data class PagedLibrary(
    val library: Library,
    val items: List<MediaItem>,
    val totalRecordCount: Int,
)

data class PagedSearch(
    val items: List<MediaItem>,
    val totalRecordCount: Int,
)

data class DetailData(
    val item: MediaItem,
    val parentSeries: MediaItem? = null,
    val seasons: List<MediaItem> = emptyList(),
    val episodes: List<MediaItem> = emptyList(),
    val collectionItems: List<MediaItem> = emptyList(),
    val similar: List<MediaItem> = emptyList(),
    val selectedSeasonId: String? = null,
)

data class MusicAlbumData(
    val album: MediaItem,
    val artist: MediaItem? = null,
    val tracks: List<MediaItem> = emptyList(),
    val relatedAlbums: List<MediaItem> = emptyList(),
    val catalogGeneration: Long? = null,
)

data class MusicArtistData(
    val artist: MediaItem,
    val albums: List<MediaItem> = emptyList(),
    val tracks: List<MediaItem> = emptyList(),
    val trackCount: Int = tracks.size,
    val appearsIn: List<MediaItem> = emptyList(),
    val relatedArtists: List<MediaItem> = emptyList(),
    val catalogGeneration: Long? = null,
)

data class LyricLine(
    val text: String,
    val startSeconds: Double? = null,
    val endSeconds: Double? = null,
)

data class AudioLyrics(
    val source: String,
    val timed: Boolean,
    val language: String? = null,
    val lines: List<LyricLine> = emptyList(),
)

enum class AudioRepeatMode {
    Off,
    Queue,
    Track;

    fun next(): AudioRepeatMode =
        when (this) {
            Off -> Queue
            Queue -> Track
            Track -> Off
        }
}

data class AudioQueueEntry(
    val entryId: String,
    val track: MediaItem,
    val playbackInstanceId: String? = null,
)

data class AudioQueueSnapshot(
    val schemaVersion: Int = 1,
    val serverUrl: String,
    val userId: String,
    val entries: List<AudioQueueEntry> = emptyList(),
    val currentIndex: Int = 0,
    val positionSeconds: Double = 0.0,
    val shuffle: Boolean = false,
    val repeatMode: AudioRepeatMode = AudioRepeatMode.Off,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class AudioPlayerState(
    val queue: List<AudioQueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    val positionSeconds: Long = 0L,
    val durationSeconds: Long = 0L,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: AudioRepeatMode = AudioRepeatMode.Off,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val error: String? = null,
) {
    val currentEntry: AudioQueueEntry?
        get() = queue.getOrNull(currentIndex)
}

data class NotificationItem(
    val id: String,
    val kind: String,
    val title: String,
    val subtitle: String? = null,
    val itemId: String? = null,
    val seriesId: String? = null,
    val artistId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val createdAt: String,
    val readAt: String? = null,
    val navigationTarget: String? = null,
    val thumbnailUrl: String? = null,
    val thumbnailBlurHash: String? = null,
)

data class NotificationPage(
    val items: List<NotificationItem> = emptyList(),
    val unreadCount: Int = 0,
    val nextCursor: String? = null,
)
