package com.zenstream.zenstreammobile.ui

import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.MediaSource
import com.zenstream.zenstreammobile.model.MediaStream
import com.zenstream.zenstreammobile.model.SyncplayGroup
import com.zenstream.zenstreammobile.model.SyncplayMember
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelTest {
    @Test
    fun onlyNonDirectPlaybackWithASessionIdNeedsServerLifecycleManagement() {
        assertTrue(requiresServerPlaybackSession("remux", "session-1"))
        assertTrue(requiresServerPlaybackSession("video-transcode", "session-2"))
        assertFalse(requiresServerPlaybackSession("direct", "session-3"))
        assertFalse(requiresServerPlaybackSession("remux", null))
        assertFalse(requiresServerPlaybackSession(null, ""))
    }

    @Test
    fun clearsWatchedStateWhenPlaybackStartsForWatchedItem() {
        assertTrue(
            shouldClearPlayedOnPlaybackStart(
                isPlaying = true,
                played = true,
                resetAlreadyRequested = false,
            )
        )
    }

    @Test
    fun doesNotClearUnwatchedOrAlreadyResetItems() {
        assertFalse(shouldClearPlayedOnPlaybackStart(true, false, false))
        assertFalse(shouldClearPlayedOnPlaybackStart(false, true, false))
        assertFalse(shouldClearPlayedOnPlaybackStart(true, true, true))
    }

    @Test
    fun defaultsToTheFirstSubtitleWhenTheServerDoesNotMarkOneDefault() {
        val subtitles =
            listOf(
                MediaStream(index = 4, type = "Subtitle"),
                MediaStream(index = 7, type = "Subtitle"),
            )

        assertEquals(4, selectSubtitleTrack(null, false, subtitles))
    }

    @Test
    fun preservesExplicitSubtitleOffAcrossPlaybackReloads() {
        assertNull(selectSubtitleTrack(null, true, listOf(MediaStream(4, "Subtitle"))))
    }

    @Test
    fun completionIsHandledOnlyOnceForTheActivePlaybackGeneration() {
        assertTrue(shouldHandlePlaybackCompletion(true, false, -1, 4))
        assertFalse(shouldHandlePlaybackCompletion(true, true, -1, 4))
        assertFalse(shouldHandlePlaybackCompletion(true, false, 4, 4))
        assertFalse(shouldHandlePlaybackCompletion(false, false, -1, 4))
        assertTrue(shouldHandlePlaybackCompletion(true, false, 4, 5))
    }

    @Test
    fun endedEpisodeWaitsForItsNextUpLookupBeforeClosingOrAdvancing() {
        assertTrue(shouldWaitForEpisodeNeighbors(false))
        assertFalse(shouldWaitForEpisodeNeighbors(true))
    }

    @Test
    fun nextUpFallbackSkipsTheEpisodeThatJustFinished() {
        val fallback =
            nextUpFallbackItem(
                listOf(
                    MediaItem("episode-2", "Episode 2", type = "Episode"),
                    MediaItem("episode-3", "Episode 3", type = "Episode"),
                ),
                "episode-2",
            )

        assertEquals("episode-3", fallback?.id)
    }

    @Test
    fun defaultsDetailSelectionAndKeepsSubtitleChoiceExplicit() {
        val selection =
            defaultTrackSelection(
                MediaSource(
                    id = "source-1",
                    mediaStreams =
                        listOf(
                            MediaStream(1, "Audio"),
                            MediaStream(2, "Audio", isDefault = true),
                            MediaStream(4, "Subtitle"),
                            MediaStream(6, "Subtitle", isDefault = true),
                        ),
                )
            )

        assertEquals(2, selection.audioStreamId)
        assertEquals(6, selection.subtitleStreamIndex)
        assertTrue(selection.hasSubtitleSelection)
    }

    @Test
    fun syncplayTimelineUsesTheAuthoritativeClockForPausedScheduledAndLatePlayback() {
        val paused = syncplayRoom(playbackState = "paused", anchorPosition = 12.0)
        val scheduled = syncplayRoom(playbackState = "playing", effectiveAt = 101.0)
        val late = syncplayRoom(playbackState = "playing", effectiveAt = 101.0)

        assertEquals(12.0, syncplayTimelineTarget(paused, 110.0).positionSeconds, 0.0)
        assertFalse(syncplayTimelineTarget(paused, 110.0).shouldPlay)

        val pending = syncplayTimelineTarget(scheduled, 100.0)
        assertEquals(10.0, pending.positionSeconds, 0.0)
        assertFalse(pending.shouldPlay)
        assertEquals(1_020L, pending.startDelayMillis)

        val current = syncplayTimelineTarget(late, 103.0)
        assertEquals(13.0, current.positionSeconds, 0.0)
        assertTrue(current.shouldPlay)
        assertNull(current.startDelayMillis)
    }

    @Test
    fun scheduledSyncplayStartReappliesOnlyAfterTheEffectiveTime() = runTest {
        var active = syncplayRoom(playbackState = "playing", effectiveAt = 1.0)
        val applied = mutableListOf<Pair<SyncplayGroup, Double>>()
        val scheduler =
            SyncplayTimelineScheduler(
                scope = this,
                serverNow = { testScheduler.currentTime / 1_000.0 },
                currentRoom = { active },
                apply = { room, now -> applied += room to now },
            )

        scheduler.apply(active)
        assertEquals(1, applied.size)
        assertFalse(
            syncplayTimelineTarget(applied.single().first, applied.single().second).shouldPlay
        )

        advanceTimeBy(1_019)
        runCurrent()
        assertEquals(1, applied.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, applied.size)
        assertTrue(syncplayTimelineTarget(applied.last().first, applied.last().second).shouldPlay)
        scheduler.cancel()
    }

    @Test
    fun replacingTheSyncplayTimelineCancelsItsPendingStart() = runTest {
        var active = syncplayRoom(playbackState = "playing", effectiveAt = 1.0)
        val applied = mutableListOf<SyncplayGroup>()
        val scheduler =
            SyncplayTimelineScheduler(
                scope = this,
                serverNow = { testScheduler.currentTime / 1_000.0 },
                currentRoom = { active },
                apply = { room, _ -> applied += room },
            )

        scheduler.apply(active)
        active = active.copy(playbackState = "paused", timelineRevision = 2)
        scheduler.apply(active)
        advanceUntilIdle()

        assertEquals(listOf("playing", "paused"), applied.map { it.playbackState })
    }

    @Test
    fun playingSyncplayTimelineIsReappliedEverySecond() = runTest {
        val active = syncplayRoom(playbackState = "playing", effectiveAt = 0.0)
        val applied = mutableListOf<Double>()
        val scheduler =
            SyncplayTimelineScheduler(
                scope = this,
                serverNow = { testScheduler.currentTime / 1_000.0 },
                currentRoom = { active },
                apply = { _, now -> applied += now },
            )

        scheduler.apply(active)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf(0.0, 1.0), applied)
        scheduler.cancel()
    }

    @Test
    fun syncplayAutoplayIsDisabledOnlyForTheCurrentOptedInMember() {
        val room =
            syncplayRoom(
                playbackState = "paused",
                members = listOf(syncplayMember(watchingTogether = true)),
            )

        assertFalse(syncplayShouldAutoplay(room, "device-1", "item-1"))
        assertTrue(syncplayShouldAutoplay(room.copy(itemId = "other"), "device-1", "item-1"))
        assertTrue(
            syncplayShouldAutoplay(
                room.copy(members = listOf(syncplayMember(watchingTogether = false))),
                "device-1",
                "item-1",
            )
        )
        assertTrue(syncplayShouldAutoplay(null, "device-1", "item-1"))
    }

    @Test
    fun syncplayReadinessBarrierMatchesTheWebRules() {
        val waiting =
            syncplayRoom(
                    playbackState = "paused",
                    members = listOf(syncplayMember(watchingTogether = true).copy(viewing = false)),
                )
                .copy(resumeWhenReady = true)

        assertFalse(syncplayWaitingForMembers(waiting, "item-1"))
        assertTrue(
            syncplayWaitingForMembers(
                waiting.copy(members = listOf(syncplayMember(true).copy(loading = true))),
                "item-1",
            )
        )
        assertTrue(
            syncplayWaitingForMembers(
                waiting.copy(members = listOf(syncplayMember(true).copy(readyGeneration = 0))),
                "item-1",
            )
        )
        assertFalse(
            syncplayWaitingForMembers(
                waiting.copy(members = listOf(syncplayMember(false))),
                "item-1",
            )
        )
        assertFalse(syncplayWaitingForMembers(waiting.copy(resumeWhenReady = false), "item-1"))
        assertFalse(syncplayWaitingForMembers(waiting.copy(members = emptyList()), "item-1"))
        assertFalse(syncplayWaitingForMembers(waiting, "other-item"))
    }

    private fun syncplayRoom(
        playbackState: String,
        effectiveAt: Double = 0.0,
        anchorPosition: Double = 10.0,
        members: List<SyncplayMember> = emptyList(),
    ) =
        SyncplayGroup(
            id = "room-1",
            name = "Room",
            hostUserId = "user-1",
            hostName = "User",
            allowViewerControls = true,
            itemId = "item-1",
            position = anchorPosition,
            playing = playbackState == "playing",
            resumeWhenReady = false,
            revision = 1,
            timelineRevision = 1,
            mediaGeneration = 1,
            anchorPosition = anchorPosition,
            anchorServerTime = 100.0,
            effectiveAt = effectiveAt,
            playbackState = playbackState,
            pauseReason = null,
            hostDisconnectedAt = null,
            updatedAt = 100.0,
            members = members,
        )

    private fun syncplayMember(watchingTogether: Boolean) =
        SyncplayMember(
            userId = "user-1",
            participantId = "device-1",
            username = "User",
            watchingTogether = watchingTogether,
            viewing = true,
            loading = false,
            readyGeneration = 1,
            role = "host",
        )
}
