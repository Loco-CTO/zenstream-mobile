package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.Library
import com.zenstream.zenstreammobile.model.LibrarySort
import com.zenstream.zenstreammobile.model.LibrarySortBy
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.model.PlaybackOptions
import com.zenstream.zenstreammobile.model.PlaylistEntry
import com.zenstream.zenstreammobile.model.PlaylistData
import com.zenstream.zenstreammobile.model.PlaylistSummary
import com.zenstream.zenstreammobile.model.appendPlaylistPage
import com.zenstream.zenstreammobile.model.playlistStartIndex
import com.zenstream.zenstreammobile.model.RowTitle
import com.zenstream.zenstreammobile.model.RowVariant
import com.zenstream.zenstreammobile.model.SearchFilter
import com.zenstream.zenstreammobile.model.SortOrder
import com.zenstream.zenstreammobile.ui.components.authenticatedImageUrl
import com.zenstream.zenstreammobile.ui.components.resolveImageUrl
import com.zenstream.zenstreammobile.ui.components.stackNewlyAdded
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogApiTest {
    private val api = CatalogApi()

    @Test
    fun parsesPagedPlaylistAndSourceMembership() {
        val payload = JSONObject()
            .put("id", "playlist-1")
            .put("name", "Road Mix")
            .put("itemCount", 21)
            .put("isMember", true)
            .put("page", 1)
            .put("pageSize", 20)
            .put("hasMore", true)
            .put("items", JSONArray().put(
                JSONObject()
                    .put("entryId", "entry-1")
                    .put("position", 0)
                    .put("item", JSONObject().put("id", "track-1").put("type", "track"))
            ))
        val parsed = parsePlaylist(payload)
        assertEquals(21, parsed.summary.itemCount)
        assertEquals(true, parsed.summary.isMember)
        assertEquals(1, parsed.page)
        assertEquals(20, parsed.pageSize)
        assertTrue(parsed.hasMore)
        assertEquals(listOf("entry-1"), parsed.items.map { it.entryId })
    }

    @Test
    fun playlistPlaybackSelectsEntryFromCompleteQueue() {
        val tracks = (0..20).map { index ->
            PlaylistEntry("entry-$index", index, "", MediaItem(id = "track-$index", name = "Track $index", type = "Audio"))
        }
        assertEquals(0, playlistStartIndex(tracks, null))
        assertEquals(20, playlistStartIndex(tracks, "entry-20"))
        assertEquals(0, playlistStartIndex(tracks, "missing"))
    }

    @Test
    fun playlistPageAppendDeduplicatesAndRejectsCrossDeviceChanges() {
        val entries = (0..20).map { index ->
            PlaylistEntry("entry-$index", index, "", MediaItem("track-$index", "Track $index", "Audio"))
        }
        val summary = PlaylistSummary(id = "playlist-1", name = "Road Mix", updatedAt = "revision-1", itemCount = 21)
        val first = PlaylistData(summary, entries.take(20), page = 1, pageSize = 20, hasMore = true)
        val second = PlaylistData(summary, listOf(entries[19], entries[20]), page = 2, pageSize = 20)
        val combined = appendPlaylistPage(first, second)
        assertEquals(21, combined?.items?.size)
        assertEquals("entry-20", combined?.items?.last()?.entryId)
        assertNull(appendPlaylistPage(first, second.copy(summary = summary.copy(updatedAt = "revision-2"))))
        assertNull(appendPlaylistPage(first, second.copy(page = 3)))
    }

    @Test
    fun includesGrantedMusicLibrariesAlongsideVideoLibraries() {
        val libraries =
            parseLibraries(
                JSONObject()
                    .put(
                        "libraries",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("id", "shows")
                                    .put("name", "Shows")
                                    .put("type", "tv_series")
                            )
                            .put(
                                JSONObject()
                                    .put("id", "music")
                                    .put("name", "Music")
                                    .put("type", "music")
                            )
                            .put(JSONObject().put("id", "unknown").put("type", "unsupported")),
                    )
            )

        assertEquals(listOf("tvshows", "music"), libraries.map { it.collectionType })
        assertEquals("Music", libraries[1].name)
    }

    @Test
    fun mapsMusicEntitiesAndRetainsCreditsArtworkAndUserState() {
        val item =
            catalogMediaItem(
                JSONObject()
                    .put("id", "track-1")
                    .put("type", "track")
                    .put("albumId", "album-1")
                    .put("artistId", "artist-1")
                    .put("discNumber", 2)
                    .put("trackNumber", 4)
                    .put(
                        "metadata",
                        JSONObject()
                            .put("title", "Song")
                            .put("album", "Release")
                            .put("albumArtist", "Artist A")
                            .put("albumType", "album")
                            .put("label", "Label")
                            .put("date", "2026-04-01")
                            .put("durationSeconds", 201.5)
                            .put(
                                "artists",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("id", "artist-1")
                                            .put("name", "Artist A")
                                            .put("joinPhrase", " & ")
                                    )
                                    .put(
                                        JSONObject().put("id", "artist-2").put("name", "Artist B")
                                    ),
                            )
                            .put("tags", JSONArray().put("Ambient"))
                            .put(
                                "images",
                                JSONObject()
                                    .put(
                                        "Primary",
                                        JSONObject()
                                            .put("url", "/art/track-1")
                                            .put("blurHash", "hash"),
                                    ),
                            ),
                    )
                    .put(
                        "userState",
                        JSONObject()
                            .put("favorite", true)
                            .put("following", true)
                            .put("playCount", 7),
                    )
            )

        assertEquals("Audio", item.type)
        assertEquals("album-1", item.albumId)
        assertEquals("artist-1", item.artistId)
        assertEquals(listOf("artist-1", "artist-2"), item.artistCredits.map { it.id })
        assertEquals(" & ", item.artistCredits.first().joinPhrase)
        assertEquals("Label", item.label)
        assertEquals("2026-04-01", item.releaseDate)
        assertEquals(2, item.discNumber)
        assertEquals(4, item.trackNumber)
        assertEquals(201.5, item.durationSeconds ?: -1.0, 0.0)
        assertEquals(7, item.playCount)
        assertTrue(item.favorite)
        assertNull(item.following)
        assertEquals("hash", item.imageBlurHashes["Primary"])
    }

    @Test
    fun mapsFollowStateOnlyForMusicArtists() {
        val artist =
            catalogMediaItem(
                JSONObject()
                    .put("id", "artist-1")
                    .put("type", "artist")
                    .put("metadata", JSONObject().put("title", "Artist A"))
                    .put("userState", JSONObject().put("following", true))
            )

        assertEquals("MusicArtist", artist.type)
        assertTrue(artist.following == true)
    }

    @Test
    fun parsesFavoriteMusicAsASquareHomeRowAndDeduplicatesIds() {
        val track = catalogItem("track-1", "Track").put("type", "track")
        val home =
            parseHomeData(JSONObject().put("favoriteMusic", JSONArray().put(track).put(track)))

        assertEquals(RowTitle.FavoriteMusic, home.rows.single().title)
        assertEquals(RowVariant.Square, home.rows.single().variant)
        assertEquals(listOf("track-1"), home.rows.single().items.map { it.id })
    }

    @Test
    fun exposesCanonicalMusicPathsAndQuerySemantics() {
        assertEquals(
            "/api/catalog/music/albums/album%2F1",
            musicAlbumPath("album/1"),
        )
        assertEquals(
            "/api/catalog/music/artists/artist%2F1/tracks",
            musicArtistTracksPath("artist/1"),
        )
        assertEquals(
            "/api/playback/items/track%2F1/lyrics",
            audioLyricsPath("track/1"),
        )
        assertEquals(
            "/api/catalog/items/track%2F1/play-start",
            audioPlayStartPath("track/1"),
        )
        assertEquals(
            "/api/catalog/items/track%2F1/progress",
            catalogProgressPath("track/1"),
        )
        assertEquals(
            mapOf(
                "libraryId" to "music",
                "page" to "2",
                "pageSize" to "50",
                "sortBy" to "year",
                "sortOrder" to "descending",
            ),
            musicAlbumsQuery(
                "music",
                2,
                50,
                LibrarySort(LibrarySortBy.Year, SortOrder.Descending),
            ),
        )
    }

    @Test
    fun searchQueryOmitsAllTypeAndIncludesSelectedType() {
        assertFalse(api.searchQuery("user", "dune").containsKey("type"))
        assertEquals(
            "series",
            api.searchQuery("user", "dune", SearchFilter.Series, page = 3)["type"],
        )
        assertEquals(
            "3",
            api.searchQuery("user", "dune", SearchFilter.Series, page = 3)["page"],
        )
    }

    @Test
    fun parsesSearchFacetsWithSafeDefaults() {
        val facets =
            api.parseSearchFacets(
                JSONObject()
                    .put(
                        "facets",
                        JSONObject()
                            .put("all", 12)
                            .put("movie", 3)
                            .put("series", 4)
                            .put("collection", 1)
                            .put("release", 2)
                            .put("artist", 5)
                            .put("track", 6),
                    )
            )

        assertEquals(12, facets.all)
        assertEquals(3, facets.movie)
        assertEquals(4, facets.series)
        assertEquals(1, facets.collection)
        assertEquals(2, facets.release)
        assertEquals(5, facets.artist)
        assertEquals(6, facets.track)
        assertEquals(0, api.parseSearchFacets(JSONObject()).all)
    }

    @Test
    fun parsesLyricsAndReleaseNotificationContext() {
        val lyrics =
            parseAudioLyrics(
                JSONObject()
                    .put("source", "embedded")
                    .put("timed", true)
                    .put(
                        "lines",
                        JSONArray()
                            .put(JSONObject().put("text", "First line").put("startSeconds", 1.5)),
                    )
            )
        val notification =
            parseNotificationPage(
                JSONObject()
                    .put(
                        "items",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("id", "notification-1")
                                    .put("kind", "new_release")
                                    .put("itemId", "album-1")
                                    .put("artistId", "artist-1")
                                    .put("createdAt", "2026-08-21T00:00:00Z")
                            ),
                    )
            )

        assertEquals("embedded", lyrics?.source)
        assertEquals(1.5, lyrics?.lines?.single()?.startSeconds)
        assertEquals("artist-1", notification.items.single().artistId)
    }

    @Test
    fun parsesNotificationThumbnailArtwork() {
        val page =
            parseNotificationPage(
                JSONObject()
                    .put(
                        "items",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("id", "notification-1")
                                    .put("createdAt", "2026-08-21T00:00:00Z")
                                    .put(
                                        "thumbnail",
                                        JSONObject()
                                            .put(
                                                "url",
                                                "/api/catalog/items/episode-1/images/Primary?language=en",
                                            )
                                            .put("blurHash", "LEHV6nWB2yk8pyo0adR*.7kCMdnj"),
                                    )
                            ),
                    )
            )

        assertEquals(
            "/api/catalog/items/episode-1/images/Primary?language=en",
            page.items.single().thumbnailUrl,
        )
        assertEquals("LEHV6nWB2yk8pyo0adR*.7kCMdnj", page.items.single().thumbnailBlurHash)
    }

    @Test
    fun authenticatedImageUrlAddsTheResourceTicketWithoutDroppingExistingQuery() {
        assertEquals(
            "https://server/api/catalog/items/movie-1/images/Primary?language=en&access=ticket-1",
            authenticatedImageUrl(
                resolveImageUrl(
                    "https://server",
                    "/api/catalog/items/movie-1/images/Primary?language=en",
                ),
                "ticket-1",
            ),
        )
    }

    @Test
    fun parsesNullCreditLabelsAsMissingValues() {
        val item =
            catalogMediaItem(
                JSONObject()
                    .put("id", "movie-1")
                    .put(
                        "metadata",
                        JSONObject()
                            .put(
                                "credits",
                                JSONObject()
                                    .put(
                                        "cast",
                                        org.json
                                            .JSONArray()
                                            .put(
                                                JSONObject()
                                                    .put("id", "person-1")
                                                    .put("name", "Actor")
                                                    .put("character", JSONObject.NULL)
                                                    .put(
                                                        "image",
                                                        JSONObject()
                                                            .put("url", "/api/person-image"),
                                                    )
                                            ),
                                    )
                                    .put(
                                        "crew",
                                        org.json
                                            .JSONArray()
                                            .put(
                                                JSONObject()
                                                    .put("id", "person-2")
                                                    .put("name", "Crew")
                                                    .put("job", "null")
                                                    .put("department", JSONObject.NULL)
                                            ),
                                    ),
                            ),
                    )
            )

        assertEquals(null, item.people[0].role)
        assertEquals("/api/person-image", item.people[0].primaryImageTag)
        assertEquals(null, item.people[1].role)
        assertEquals(null, item.people[1].type)
    }

    @Test
    fun media3CapabilityProfileReflectsAvailablePlatformAndExtensionDecoders() {
        val capabilities =
            media3PlaybackCapabilities(
                decoderMimeTypes =
                    setOf(
                        "video/avc",
                        "video/hevc",
                        "video/x-vnd.on2.vp8",
                        "video/x-vnd.on2.vp9",
                        "audio/mp4a-latm",
                        "audio/mpeg",
                        "audio/opus",
                        "audio/vorbis",
                        "audio/flac",
                    ),
                extensionCodecs = setOf("av1", "ac3", "eac3", "alac", "dts", "dts_hd", "truehd"),
            )

        assertEquals("media3", capabilities.engine)
        assertEquals(listOf("h264", "hevc", "vp8", "vp9", "av1"), capabilities.videoCodecs)
        assertTrue("aac" in capabilities.audioCodecs)
        assertTrue("mp3" in capabilities.audioCodecs)
        assertTrue("opus" in capabilities.audioCodecs)
        assertTrue("vorbis" in capabilities.audioCodecs)
        assertTrue("flac" in capabilities.audioCodecs)
        assertTrue("ac3" in capabilities.audioCodecs)
        assertTrue("eac3" in capabilities.audioCodecs)
        assertTrue("alac" in capabilities.audioCodecs)
        assertTrue("dts" in capabilities.audioCodecs)
        assertTrue("dts_hd" in capabilities.audioCodecs)
        assertTrue("truehd" in capabilities.audioCodecs)
        assertTrue("pcm_s16le" in capabilities.audioCodecs)
        assertFalse("mpeg2video" in capabilities.videoCodecs)
        assertFalse("mpeg4" in capabilities.videoCodecs)
        assertFalse("xvid" in capabilities.videoCodecs)
    }

    @Test
    fun media3CapabilitiesKeepContainerSupportSeparateAndHandleNoDecoders() {
        val capabilities = media3PlaybackCapabilities(decoderMimeTypes = emptySet())

        assertTrue("mkv" in capabilities.containers)
        assertTrue("wav" in capabilities.containers)
        assertFalse("aiff" in capabilities.containers)
        assertTrue(capabilities.videoCodecs.isEmpty())
        assertTrue(capabilities.audioCodecs.all { it.startsWith("pcm_") })
        assertFalse("ac3" in capabilities.audioCodecs)
        assertFalse("mpeg2video" in capabilities.videoCodecs)
        assertFalse("mpeg4" in capabilities.videoCodecs)
    }

    @Test
    fun playbackNegotiationBodyPreservesExistingFieldsAndEmptyCodecLists() {
        val capabilities =
            PlaybackCapabilities(
                engine = "media3",
                containers = listOf("mp4", "mkv"),
                videoCodecs = emptyList(),
                audioCodecs = emptyList(),
                maxAudioChannels = 2,
            )
        val body =
            playbackNegotiationBody(
                capabilities = capabilities,
                device = JSONObject().put("model", "test"),
                options =
                    PlaybackOptions(
                        engine = PlayerEngine.MEDIA3,
                        sourceId = "source-1",
                        requestedMode = "audio-transcode",
                        audioStreamId = 4,
                        maxStreamingBitrate = 2_000_000,
                        startPositionSeconds = 12.5,
                    ),
            )

        val bodyKeys = mutableSetOf<String>()
        val keyIterator = body.keys()
        while (keyIterator.hasNext()) bodyKeys += keyIterator.next()

        assertEquals(
            setOf(
                "engine",
                "device",
                "sourceId",
                "requestedMode",
                "forceTranscoding",
                "containers",
                "videoCodecs",
                "audioCodecs",
                "maxAudioChannels",
                "maxStreamingBitrate",
                "startPositionSeconds",
                "audioStreamId",
            ),
            bodyKeys,
        )
        assertEquals("media3", body.getString("engine"))
        assertEquals("source-1", body.getString("sourceId"))
        assertEquals("audio-transcode", body.getString("requestedMode"))
        assertEquals(4, body.getInt("audioStreamId"))
        assertEquals(12.5, body.getDouble("startPositionSeconds"), 0.0)
        assertEquals(0, body.getJSONArray("videoCodecs").length())
        assertEquals(0, body.getJSONArray("audioCodecs").length())
    }

    @Test
    fun mpvCapabilityProfileIdentifiesTheMpvEngine() {
        val capabilities = playbackCapabilities(PlayerEngine.MPV)

        assertEquals("mpv", capabilities.engine)
        assertTrue("mpeg2video" in capabilities.videoCodecs)
        assertTrue("xvid" in capabilities.videoCodecs)
        assertTrue("dts_hd" in capabilities.audioCodecs)
    }

    @Test
    fun parsesAllHomeSectionsFromTheCatalogHomePayload() {
        val home =
            parseHomeData(
                JSONObject()
                    .put(
                        "latestItems",
                        JSONArray().put(catalogItem("featured", "Featured", backdrop = true)),
                    )
                    .put("continueWatching", JSONArray().put(catalogItem("continue", "Continue")))
                    .put("nextUp", JSONArray().put(catalogItem("next", "Next")))
                    .put("myList", JSONArray().put(catalogItem("favorite", "Favorite")))
                    .put("recentlyPlayed", JSONArray().put(catalogItem("recent", "Recent")))
                    .put(
                        "genreRows",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("genre", "Drama")
                                    .put(
                                        "items",
                                        JSONArray().put(catalogItem("drama", "Drama item")),
                                    )
                            ),
                    )
                    .put(
                        "libraryRows",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("libraryName", "Shows")
                                    .put("titleKey", "newlyAddedOn")
                                    .put("stackEpisodes", true)
                                    .put(
                                        "items",
                                        JSONArray().put(catalogItem("episode", "Episode")),
                                    )
                            )
                            .put(
                                JSONObject()
                                    .put("libraryName", "Movies")
                                    .put("titleKey", "topRated")
                                    .put("items", JSONArray().put(catalogItem("rated", "Rated")))
                            )
                            .put(
                                JSONObject()
                                    .put("libraryName", "Shows")
                                    .put("titleKey", "newReleases")
                                    .put(
                                        "items",
                                        JSONArray().put(catalogItem("released", "Released")),
                                    )
                            ),
                    )
            )

        assertEquals(listOf("Featured"), home.featured.map { it.name })
        assertEquals(
            listOf(
                RowTitle.ContinueWatching,
                RowTitle.NextUp,
                RowTitle.NewlyAdded,
                RowTitle.TopRated,
                RowTitle.MyList,
                RowTitle.Genre,
            ),
            home.rows.map { it.title },
        )
        assertEquals(
            listOf(null, null, "Shows", "Movies", null, null),
            home.rows.map { it.libraryName },
        )
        assertEquals("Drama", home.rows[5].label)
        assertEquals("genre:drama", home.rows[5].key)
        assertTrue(home.rows[2].stackEpisodes)
    }

    @Test
    fun randomlySelectsAtMostFiveUniqueFeaturedItems() {
        val items =
            (0 until 10).map { index ->
                MediaItem(
                    "hero-$index",
                    "Hero $index",
                    backdropImageTags = listOf("backdrop-$index"),
                )
            }

        val selected = selectRandomFeaturedItems(items, Random(0))

        assertEquals(5, selected.size)
        assertEquals(5, selected.map { it.id }.toSet().size)
        assertFalse(selected.map { it.id } == items.take(5).map { it.id })
    }

    @Test
    fun filtersNonVisualFeaturedItemsAndKeepsAllItemsBelowTheCap() {
        val items =
            listOf(
                MediaItem("no-backdrop", "No Backdrop"),
                MediaItem("hero-1", "Hero 1", backdropImageTags = listOf("backdrop-1")),
                MediaItem("hero-2", "Hero 2", backdropImageTags = listOf("backdrop-2")),
                MediaItem("hero-3", "Hero 3", backdropImageTags = listOf("backdrop-3")),
            )

        val selected = selectRandomFeaturedItems(items, Random(0))

        assertEquals(listOf("hero-1", "hero-2", "hero-3").toSet(), selected.map { it.id }.toSet())
        assertEquals(3, selected.size)
    }

    @Test
    fun featuredHomeSectionRequestsTheFullFeaturedList() {
        assertEquals(
            "/api/catalog/home?section=featured&limit=25",
            homeSectionPath("featured", HOME_FEATURED_LIST_LIMIT, encode = { it }),
        )
    }

    @Test
    fun aggregateHomeParsingCapsFeaturedItemsAfterLoadingTheFullList() {
        val payload =
            JSONObject()
                .put(
                    "latestItems",
                    JSONArray().apply {
                        repeat(10) { index ->
                            put(catalogItem("hero-$index", "Hero $index", backdrop = true))
                        }
                    },
                )

        val home = parseHomeData(payload)

        assertEquals(5, home.featured.size)
        assertEquals(5, home.featured.map { it.id }.toSet().size)
    }

    @Test
    fun parsesPerLibraryHomeSectionWithEpisodeStacking() {
        val library = Library("shows", "Shows", "tvshows")
        val data =
            parseHomeLibraryData(
                JSONObject()
                    .put(
                        "libraryRows",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("titleKey", "newlyAddedOn")
                                    .put("stackEpisodes", true)
                                    .put(
                                        "items",
                                        JSONArray().put(catalogItem("episode", "Episode")),
                                    )
                            )
                            .put(
                                JSONObject()
                                    .put("titleKey", "topRated")
                                    .put(
                                        "items",
                                        JSONArray().put(catalogItem("rated", "Rated")),
                                    )
                            ),
                    ),
                library,
            )
        assertEquals(
            listOf(RowTitle.NewlyAdded, RowTitle.TopRated),
            data.rows.map { it.title },
        )
        assertTrue(data.rows.first().stackEpisodes)
        assertEquals("episode", data.rows.first().items.single().id)
        assertEquals("rated", data.rows.last().items.single().id)
    }

    @Test
    fun parsesLegacyNewlyAddedHomeRowsWhenDedicatedSectionIsUnavailable() {
        val library = Library("movies", "Movies", "movies")
        val data =
            parseHomeLibraryData(
                JSONObject()
                    .put(
                        "newlyAdded",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("libraryId", "shows")
                                    .put("libraryName", "Shows")
                                    .put(
                                        "items",
                                        JSONArray().put(catalogItem("episode", "Episode")),
                                    )
                            )
                            .put(
                                JSONObject()
                                    .put("libraryId", "movies")
                                    .put("libraryName", "Movies")
                                    .put("items", JSONArray().put(catalogItem("movie", "Movie")))
                            ),
                    ),
                library,
            )
        assertEquals("movie", data.rows.single().items.single().id)
        assertEquals(RowTitle.NewlyAdded, data.rows.single().title)
    }

    @Test
    fun parsesCatalogItemWithCanonicalArtworkAndState() {
        val item =
            catalogMediaItem(
                JSONObject()
                    .put("id", "movie-1")
                    .put("libraryId", "movies")
                    .put("type", "movie")
                    .put("name", "Fallback")
                    .put(
                        "metadata",
                        JSONObject()
                            .put("title", "Dune")
                            .put("runtimeMinutes", 155)
                            .put("officialRating", "PG-13")
                            .put(
                                "studios",
                                JSONArray().put(JSONObject().put("name", "Warner Bros.")),
                            )
                            .put(
                                "images",
                                JSONObject()
                                    .put(
                                        "Primary",
                                        JSONObject()
                                            .put(
                                                "url",
                                                "/api/catalog/items/movie-1/images/Primary?language=en",
                                            )
                                            .put("blurHash", "LEHV6nWB2yk8pyo0adR*.7kCMdnj"),
                                    )
                                    .put(
                                        "Backdrop",
                                        JSONObject()
                                            .put(
                                                "url",
                                                "/api/catalog/items/movie-1/images/Backdrop?language=en",
                                            ),
                                    ),
                            ),
                    )
                    .put("recursiveItemCount", 12)
                    .put(
                        "userState",
                        JSONObject()
                            .put("favorite", true)
                            .put("played", false)
                            .put("unplayedItemCount", 4)
                            .put("positionSeconds", 42.0),
                    )
            )

        assertEquals("movie-1", item.id)
        assertEquals("movies", item.libraryId)
        assertEquals("Dune", item.name)
        assertEquals("Movie", item.type)
        assertTrue(item.favorite)
        assertFalse(item.played)
        assertEquals("PG-13", item.officialRating)
        assertEquals(listOf("Warner Bros."), item.studios)
        assertEquals(12, item.recursiveItemCount)
        assertEquals(4, item.unplayedItemCount)
        assertEquals(42_000_0000L, item.playbackPositionTicks)
        assertEquals(setOf("Primary"), item.imageTags.keys)
        assertEquals("LEHV6nWB2yk8pyo0adR*.7kCMdnj", item.imageBlurHashes["Primary"])
        assertEquals(1, item.backdropImageTags.size)
        assertNull(item.imageTags["Thumb"])
    }

    @Test
    fun retainsServerResolvedFallbackMetadataAndArtwork() {
        val item =
            catalogMediaItem(
                JSONObject()
                    .put("id", "movie-1")
                    .put("type", "movie")
                    .put("name", "Filesystem fallback")
                    .put(
                        "metadata",
                        JSONObject()
                            .put("title", "Original-language title")
                            .put("overview", "English fallback overview")
                            .put("genres", JSONArray().put("Drama"))
                            .put(
                                "images",
                                JSONObject()
                                    .put(
                                        "Primary",
                                        JSONObject()
                                            .put(
                                                "url",
                                                "/api/catalog/items/movie-1/images/Primary?language=fr",
                                            ),
                                    )
                                    .put(
                                        "Backdrop",
                                        JSONObject()
                                            .put(
                                                "url",
                                                "/api/catalog/items/movie-1/images/Backdrop?language=fr",
                                            ),
                                    )
                                    .put(
                                        "Logo",
                                        JSONObject()
                                            .put(
                                                "url",
                                                "/api/catalog/items/movie-1/images/Logo?language=fr",
                                            ),
                                    )
                                    .put(
                                        "Banner",
                                        JSONObject()
                                            .put(
                                                "url",
                                                "/api/catalog/items/movie-1/images/Banner?language=fr",
                                            ),
                                    ),
                            ),
                    )
            )

        assertEquals("Original-language title", item.name)
        assertEquals("English fallback overview", item.overview)
        assertEquals(listOf("Drama"), item.genres)
        assertEquals(
            setOf("Primary", "Logo", "Banner"),
            item.imageTags.keys,
        )
        assertEquals(
            listOf("/api/catalog/items/movie-1/images/Backdrop?language=fr"),
            item.backdropImageTags,
        )
    }

    @Test
    fun parsesCanonicalEpisodeSeriesName() {
        val item =
            catalogMediaItem(
                JSONObject()
                    .put("id", "episode-1")
                    .put("type", "episode")
                    .put("seriesId", "series-1")
                    .put("seriesName", "Example Series")
                    .put(
                        "seriesPrimaryImage",
                        JSONObject()
                            .put("url", "/api/catalog/items/series-1/images/Primary?language=en"),
                    )
                    .put("metadata", JSONObject().put("title", "Episode title"))
            )

        assertEquals("Example Series", item.seriesName)
        assertEquals(
            "/api/catalog/items/series-1/images/Primary?language=en",
            item.seriesPrimaryImageTag,
        )
    }

    @Test
    fun parsesCatalogResultArray() {
        val root =
            JSONObject()
                .put(
                    "items",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("id", "a")
                                .put("type", "series")
                                .put("metadata", JSONObject().put("title", "A"))
                        )
                        .put(
                            JSONObject()
                                .put("id", "b")
                                .put("type", "collection")
                                .put("metadata", JSONObject().put("title", "B"))
                        ),
                )

        assertEquals(listOf("a", "b"), catalogItems(root).map { it.id })
        assertEquals(listOf("Series", "BoxSet"), catalogItems(root).map { it.type })
    }

    @Test
    fun parsesCollectionItemsFromDetailPayload() {
        val payload =
            JSONObject()
                .put(
                    "item",
                    JSONObject()
                        .put("id", "collection")
                        .put("type", "collection")
                        .put("collectionYearRange", "2007-2019")
                        .put("metadata", JSONObject().put("title", "Collection")),
                )
                .put(
                    "collectionItems",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("id", "series-1")
                                .put("type", "series")
                                .put("metadata", JSONObject().put("title", "Series One"))
                        )
                        .put(
                            JSONObject()
                                .put("id", "movie-1")
                                .put("type", "movie")
                                .put("metadata", JSONObject().put("title", "Movie One"))
                        ),
                )

        val data = parseDetailData(payload)

        assertEquals("BoxSet", data.item.type)
        assertEquals("2007-2019", data.item.collectionYearRange)
        assertEquals(listOf("series-1", "movie-1"), data.collectionItems.map { it.id })
    }

    @Test
    fun removesDuplicateCatalogItemsBeforeComposeListsRenderThem() {
        val item =
            JSONObject()
                .put("id", "duplicate")
                .put("type", "movie")
                .put("metadata", JSONObject().put("title", "Duplicate"))
        val root = JSONObject().put("items", JSONArray().put(item).put(item))

        assertEquals(listOf("duplicate"), catalogItems(root).map { it.id })
    }

    @Test
    fun groupsSequentialEpisodesAddedWithinOneHour() {
        val stacks =
            stackNewlyAdded(
                listOf(
                    MediaItem(
                        "episode-2",
                        "Episode 2",
                        "Episode",
                        seriesId = "series",
                        seasonId = "season-1",
                        parentIndexNumber = 1,
                        indexNumber = 2,
                        lastAddedAt = "2026-01-01T12:00:00Z",
                    ),
                    MediaItem(
                        "episode-1",
                        "Episode 1",
                        "Episode",
                        seriesId = "series",
                        seasonId = "season-1",
                        parentIndexNumber = 1,
                        indexNumber = 1,
                        lastAddedAt = "2026-01-01T11:15:00Z",
                    ),
                    MediaItem(
                        "episode-0",
                        "Episode 0",
                        "Episode",
                        seriesId = "series",
                        seasonId = "season-1",
                        parentIndexNumber = 1,
                        indexNumber = 0,
                        lastAddedAt = "2026-01-01T10:00:00Z",
                    ),
                )
            )

        assertEquals(
            listOf(listOf("episode-2", "episode-1"), listOf("episode-0")),
            stacks.map { stack -> stack.items.map { it.id } },
        )
    }

    @Test
    fun prefersSeasonOneOverSpecialsWhenOpeningASeries() {
        val seasons =
            listOf(
                catalogMediaItem(
                    JSONObject()
                        .put("id", "specials")
                        .put("type", "season")
                        .put("seasonNumber", 0)
                        .put("metadata", JSONObject().put("title", "Specials"))
                ),
                catalogMediaItem(
                    JSONObject()
                        .put("id", "season-1")
                        .put("type", "season")
                        .put("seasonNumber", 1)
                        .put("metadata", JSONObject().put("title", "Season 1"))
                ),
            )

        assertEquals(
            "season-1",
            selectInitialSeason(MediaItem("series", "Example", type = "Series"), seasons)?.id,
        )
    }

    private fun catalogItem(id: String, title: String, backdrop: Boolean = false): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "movie")
            .put(
                "metadata",
                JSONObject()
                    .put("title", title)
                    .put(
                        "images",
                        if (backdrop)
                            JSONObject().put("Backdrop", JSONObject().put("url", "backdrop"))
                        else JSONObject(),
                    ),
            )
}
