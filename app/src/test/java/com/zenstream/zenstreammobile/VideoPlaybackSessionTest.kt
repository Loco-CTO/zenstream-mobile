package com.zenstream.zenstreammobile

import androidx.lifecycle.ViewModel
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.ui.player.miniVideoBounds
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class VideoPlaybackSessionTest {
    @After
    fun cleanup() {
        VideoPlaybackSessions.close()
    }

    @Test
    fun minimizeAndRestoreRetainTheSameModelStore() {
        val args = PlaybackLaunchArgs("movie", "Movie", 2, null, true)
        val auth = AuthSession("http://localhost", "token", "user", "User")
        val initial = VideoPlaybackSessions.open(args, auth)
        initial.presentation = VideoPresentation.TO_MINI
        assertTrue(initial.handingOff)
        initial.presentation = VideoPresentation.MINI
        initial.presentation = VideoPresentation.TO_FULL
        val restored = VideoPlaybackSessions.open(args, auth)
        assertSame(initial, restored)
        assertSame(initial.viewModelStore, restored.viewModelStore)
        assertEquals(VideoPresentation.FULL, restored.presentation)
    }

    @Test
    fun replacingOrClosingPlaybackClearsThePreviousStore() {
        val auth = AuthSession("http://localhost", "token", "user", "User")
        val first = VideoPlaybackSessions.open(PlaybackLaunchArgs("one", "One"), auth)
        var cleared = false
        first.viewModelStore.put(
            "probe",
            object : ViewModel() {
                override fun onCleared() {
                    cleared = true
                }
            },
        )
        val next = VideoPlaybackSessions.open(PlaybackLaunchArgs("two", "Two"), auth)
        assertNotSame(first, next)
        assertTrue(cleared)
        VideoPlaybackSessions.close()
        assertNull(VideoPlaybackSessions.active)
    }

    @Test
    fun anotherAccountOrServerNeverReusesAPlaybackSession() {
        val args = PlaybackLaunchArgs("movie", "Movie")
        val original =
            VideoPlaybackSessions.open(args, AuthSession("http://one", "token", "user", "User"))
        assertFalse(original.matches(AuthSession("http://two", "token", "user", "User")))
        assertFalse(original.matches(AuthSession("http://one", "token", "other", "Other")))
    }

    @Test
    fun maximumSizeFitsEveryCornerAndRotationWithPadding() {
        for ((w, h) in listOf(360f to 720f, 720f to 360f, 800f to 1280f)) {
            for (right in listOf(false, true)) for (bottom in listOf(false, true)) {
                val bounds = miniVideoBounds(99999f, w, h, right, bottom, 12f)
                assertTrue(bounds.x >= 12f)
                assertTrue(bounds.y >= 12f)
                assertTrue(bounds.x + bounds.width <= w - 12f + .001f)
                assertTrue(bounds.y + bounds.height <= h - 12f + .001f)
                assertEquals(16f / 9f, bounds.width / bounds.height, .001f)
            }
        }
    }
}
