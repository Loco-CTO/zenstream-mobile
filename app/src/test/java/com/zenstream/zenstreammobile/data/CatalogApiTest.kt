package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.Library
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.model.RowTitle
import com.zenstream.zenstreammobile.ui.components.authenticatedImageUrl
import com.zenstream.zenstreammobile.ui.components.resolveImageUrl
import com.zenstream.zenstreammobile.ui.components.stackNewlyAdded
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogApiTest {
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
    fun media3CapabilityProfileAllowsNativeMatroskaAndCommonAudioCodecs() {
        val capabilities = playbackCapabilities(PlayerEngine.MEDIA3)

        assertEquals("media3", capabilities.engine)
        assertTrue("mkv" in capabilities.containers)
        assertTrue("h265" in capabilities.videoCodecs)
        assertTrue("eac3" in capabilities.audioCodecs)
    }

    @Test
    fun mpvCapabilityProfileIdentifiesTheMpvEngine() {
        assertEquals("mpv", playbackCapabilities(PlayerEngine.MPV).engine)
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
