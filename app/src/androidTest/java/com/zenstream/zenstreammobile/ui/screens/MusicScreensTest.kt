package com.zenstream.zenstreammobile.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.MusicDataSource
import com.zenstream.zenstreammobile.model.AudioLyrics
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.MusicAlbumData
import com.zenstream.zenstreammobile.model.MusicArtistData
import com.zenstream.zenstreammobile.model.PlaylistData
import com.zenstream.zenstreammobile.model.PlaylistSummary
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MusicScreensTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun albumOverviewExpandsAndCollapses() {
        val album =
            MediaItem(
                id = "album",
                name = "Example Album",
                type = "MusicAlbum",
                overview = longOverview("Album"),
            )
        val session = AuthSession("https://example.com", "token", "user", "name")
        composeRule.setContent {
            ZenStreamTheme {
                MusicAlbumScreen(
                    repository = FakeMusicDataSource(album = MusicAlbumData(album = album)),
                    session = session,
                    albumId = album.id,
                    onBack = {},
                    onOpenArtist = {},
                    onOpenAlbum = {},
                    onPlayTracks = { _, _, _ -> },
                    onAddToQueue = {},
                )
            }
        }

        assertExpansionCycle()
    }

    @Test
    fun selectingTracksAcrossDiscsPositionsEachSelectedTrackAtTheTop() {
        val tracks = buildList {
            repeat(32) { index ->
                val number = index + 1
                add(
                    MediaItem(
                        id = "disc-1-track-$number",
                        name = "Disc 1 Track $number",
                        type = "Audio",
                        discNumber = 1,
                        trackNumber = number,
                    )
                )
            }
            repeat(32) { index ->
                val number = index + 1
                add(
                    MediaItem(
                        id = "disc-2-track-$number",
                        name = "Disc 2 Track $number",
                        type = "Audio",
                        discNumber = 2,
                        trackNumber = number,
                    )
                )
            }
        }
        val album = MediaItem(id = "album", name = "Example Album", type = "MusicAlbum")
        val repository = FakeMusicDataSource(album = MusicAlbumData(album = album, tracks = tracks))
        val session = AuthSession("https://example.com", "token", "user", "name")
        val selectedTrackId = mutableStateOf(tracks[19].id)
        val selections = listOf(tracks[19], tracks[44], tracks[7], tracks[36], tracks[19])
        val topLimitPx = with(composeRule.density) { 48.dp.toPx() }

        composeRule.setContent {
            ZenStreamTheme {
                MusicAlbumScreen(
                    repository = repository,
                    session = session,
                    albumId = album.id,
                    selectedTrackId = selectedTrackId.value,
                    onBack = {},
                    onOpenArtist = {},
                    onOpenAlbum = {},
                    onPlayTracks = { _, _, _ -> },
                    onAddToQueue = {},
                )
            }
        }

        selections.forEach { track ->
            composeRule.runOnIdle { selectedTrackId.value = track.id }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText(track.name).fetchSemanticsNodes().any { node ->
                    node.boundsInRoot.top <= topLimitPx
                }
            }
            composeRule.waitForIdle()
            val selectedNode =
                composeRule.onAllNodesWithText(track.name).fetchSemanticsNodes().first()
            assertTrue(
                "${track.name} should appear near the top of the album list",
                selectedNode.boundsInRoot.top <= topLimitPx,
            )
        }
    }

    @Test
    fun artistOverviewExpandsAndCollapses() {
        val artist =
            MediaItem(
                id = "artist",
                name = "Example Artist",
                type = "MusicArtist",
                overview = longOverview("Artist"),
            )
        val session = AuthSession("https://example.com", "token", "user", "name")
        composeRule.setContent {
            ZenStreamTheme {
                MusicArtistScreen(
                    repository = FakeMusicDataSource(artist = MusicArtistData(artist = artist)),
                    session = session,
                    artistId = artist.id,
                    onBack = {},
                    onOpenArtist = {},
                    onOpenAlbum = {},
                    onPlayTracks = { _, _, _ -> },
                    onAddToQueue = {},
                )
            }
        }

        assertExpansionCycle()
    }

    @Test
    fun creatingPlaylistFromAlbumPickerAddsTheSelectedAlbum() {
        val album = MediaItem(id = "album", name = "Example Album", type = "MusicAlbum")
        val track = MediaItem(id = "track", name = "Example Track", type = "Audio")
        val repository =
            FakeMusicDataSource(album = MusicAlbumData(album = album, tracks = listOf(track)))
        val session = AuthSession("https://example.com", "token", "user", "name")
        val context = composeRule.activity

        composeRule.setContent {
            ZenStreamTheme {
                MusicAlbumScreen(
                    repository = repository,
                    session = session,
                    albumId = album.id,
                    onBack = {},
                    onOpenArtist = {},
                    onOpenAlbum = {},
                    onPlayTracks = { _, _, _ -> },
                    onAddToQueue = {},
                )
            }
        }

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.show_more))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.add_to_playlist)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.create_playlist)).performClick()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("Road Trip")
        composeRule.onNodeWithText(context.getString(R.string.save)).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { repository.createdEntityId == album.id }
        assert(repository.createdPlaylistName == "Road Trip")
    }

    private fun assertExpansionCycle() {
        val showMore = composeRule.activity.getString(R.string.show_more)
        val showLess = composeRule.activity.getString(R.string.show_less)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(showMore).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(showMore).performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText(showLess).performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText(showMore).performScrollTo().assertIsDisplayed()
    }

    private fun longOverview(label: String): String =
        List(8) { line ->
                "$label line ${line + 1} contains enough detail to require the expandable preview."
            }
            .joinToString(" ")
}

private class FakeMusicDataSource(
    private val album: MusicAlbumData? = null,
    private val artist: MusicArtistData? = null,
) : MusicDataSource {
    var createdEntityId: String? = null
    var createdPlaylistName: String? = null

    override suspend fun clearSession() = Unit

    override suspend fun musicAlbum(session: AuthSession, albumId: String): MusicAlbumData =
        requireNotNull(album)

    override suspend fun musicArtist(session: AuthSession, artistId: String): MusicArtistData =
        requireNotNull(artist)

    override suspend fun musicArtistTracks(
        session: AuthSession,
        artistId: String,
    ): List<MediaItem> = artist?.tracks.orEmpty()

    override suspend fun audioLyrics(session: AuthSession, itemId: String): AudioLyrics? = null

    override suspend fun recordAudioPlayStart(
        session: AuthSession,
        itemId: String,
        playbackInstanceId: String,
    ) = Unit

    override suspend fun setFavorite(session: AuthSession, itemId: String, favorite: Boolean) = Unit

    override suspend fun playlists(
        session: AuthSession,
        membershipSourceId: String?,
    ): List<PlaylistSummary> = emptyList()

    override suspend fun createPlaylist(
        session: AuthSession,
        name: String,
        description: String?,
        isPrivate: Boolean,
        entityId: String?,
    ): PlaylistData {
        createdEntityId = entityId
        createdPlaylistName = name
        return PlaylistData(PlaylistSummary(id = "playlist", name = name, isPrivate = isPrivate))
    }
}
