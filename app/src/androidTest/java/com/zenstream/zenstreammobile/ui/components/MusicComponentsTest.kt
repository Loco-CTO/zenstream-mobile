package com.zenstream.zenstreammobile.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.zenstream.zenstreammobile.model.ArtistCredit
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Rule
import org.junit.Test

class MusicComponentsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun albumCardShowsAllOrderedParticipatingArtists() {
        composeRule.setContent {
            ZenStreamTheme {
                AudioCard(
                    item =
                        MediaItem(
                            id = "album-1",
                            name = "Album",
                            type = "MusicAlbum",
                            albumArtist = "Main Artist",
                            artistCredits =
                                listOf(
                                    ArtistCredit(name = "Main Artist", joinPhrase = " feat. "),
                                    ArtistCredit(name = "Guest Artist", joinPhrase = " & "),
                                    ArtistCredit(name = "Third Artist"),
                                ),
                        ),
                    session = AuthSession("https://example.test", "token", "user", "Test"),
                    onClick = {},
                )
            }
        }

        composeRule
            .onNodeWithText("Main Artist feat. Guest Artist & Third Artist")
            .assertIsDisplayed()
    }
}
