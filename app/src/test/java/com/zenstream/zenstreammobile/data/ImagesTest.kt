package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImagesTest {
    private val item = MediaItem(
        id = "movie-1",
        name = "Movie",
		imageTags = mapOf("Primary" to "/api/catalog/items/movie-1/images/Primary?language=en"),
		backdropImageTags = listOf("/api/catalog/items/movie-1/images/Backdrop?language=en"),
    )

    @Test
	fun landscapeUsesBackdropWithoutCrossCategoryFallback() {
		assertEquals("Backdrop", landscapeImageType(item))
        assertEquals(
			"https://server/api/catalog/items/movie-1/images/Backdrop?language=en",
			imageUrl("https://server", item, "Backdrop", 448, 252)
        )
		assertNull(landscapeImageType(item.copy(backdropImageTags = emptyList())))
		assertEquals("Primary", landscapeImageType(item.copy(type = "Episode", backdropImageTags = emptyList())))
    }

    @Test
    fun heroDoesNotFallBackToPoster() {
        val noBackdrop = item.copy(backdropImageTags = emptyList())
		assertNull(imageUrl("https://server", noBackdrop, "Backdrop", 1280, 720))
    }

    @Test
	fun episodePosterUsesTheSeriesPrimaryArtwork() {
        val episode = item.copy(
            id = "episode-1",
            type = "Episode",
            seriesId = "series-1",
            imageTags = emptyMap(),
            seriesPrimaryImageTag = "/api/catalog/items/series-1/images/Primary?language=en",
        )

        assertEquals("SeriesPrimary", posterImageType(episode))
        assertEquals(
            "https://server/api/catalog/items/series-1/images/Primary?language=en",
            imageUrl("https://server", episode, "SeriesPrimary", 280, 420),
        )
	}

    @Test
    fun resolvesTheArtworkBlurhashAlongsideItsUrl() {
        val artwork = item.copy(imageBlurHashes = mapOf("Backdrop" to "LEHV6nWB2yk8pyo0adR*.7kCMdnj"))

        assertEquals("LEHV6nWB2yk8pyo0adR*.7kCMdnj", imageBlurHash(artwork, "Backdrop"))
    }

    @Test
    fun seriesPosterPrefersTheSeriesArtworkOverAnEpisodeScreencap() {
        val episode = item.copy(
            id = "episode-1",
            type = "Episode",
            seriesId = "series-1",
            seriesPrimaryImageTag = "/api/catalog/items/series-1/images/Primary?language=en",
        )

        assertEquals("SeriesPrimary", seriesPosterImageType(episode))
        assertNull(seriesPosterImageType(episode.copy(seriesPrimaryImageTag = null)))
    }
}
