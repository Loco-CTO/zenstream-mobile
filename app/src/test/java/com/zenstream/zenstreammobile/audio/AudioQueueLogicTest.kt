package com.zenstream.zenstreammobile.audio

import com.zenstream.zenstreammobile.model.AudioPlayerState
import com.zenstream.zenstreammobile.model.AudioQueueEntry
import com.zenstream.zenstreammobile.model.AudioRepeatMode
import com.zenstream.zenstreammobile.model.MediaItem
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioQueueLogicTest {
    private val queue =
        listOf(
            AudioQueueEntry("a", MediaItem("track-a", "A", type = "Audio")),
            AudioQueueEntry("b", MediaItem("track-b", "B", type = "Audio")),
            AudioQueueEntry("c", MediaItem("track-c", "C", type = "Audio")),
        )

    @Test
    fun nextHonorsTrackQueueAndEndRepeatModes() {
        assertEquals(1, nextQueueIndex(3, 0, AudioRepeatMode.Off, false))
        assertEquals(0, nextQueueIndex(3, 2, AudioRepeatMode.Queue, true))
        assertEquals(2, nextQueueIndex(3, 2, AudioRepeatMode.Track, true))
        assertNull(nextQueueIndex(3, 2, AudioRepeatMode.Off, true))
    }

    @Test
    fun selectingQueueEntryUsesItsStableIdAndResetsPlaybackState() {
        val state =
            AudioPlayerState(
                queue = queue,
                currentIndex = 0,
                positionSeconds = 15,
                durationSeconds = 180,
                isPlaying = true,
                shuffle = true,
                repeatMode = AudioRepeatMode.Track,
            )

        val selected = selectQueueEntryForPlayback(state, "c")

        assertEquals(2, selected?.currentIndex)
        assertEquals(0L, selected?.positionSeconds)
        assertEquals(0L, selected?.durationSeconds)
        assertFalse(selected?.isPlaying ?: true)
        assertTrue(selected?.isLoading ?: false)
        assertTrue(selected?.shuffle ?: false)
        assertEquals(AudioRepeatMode.Track, selected?.repeatMode)
        assertNull(selectQueueEntryForPlayback(state, "missing"))
    }

    @Test
    fun repeatModeCyclesOffQueueTrackAndBackToOff() {
        assertEquals(AudioRepeatMode.Queue, AudioRepeatMode.Off.next())
        assertEquals(AudioRepeatMode.Track, AudioRepeatMode.Queue.next())
        assertEquals(AudioRepeatMode.Off, AudioRepeatMode.Track.next())
    }

    @Test
    fun shuffleSelectsAnUnplayedEntryAndStopsAfterThePassWhenRepeatIsOff() {
        val state =
            AudioPlayerState(
                queue = queue,
                currentIndex = 0,
                shuffle = true,
                repeatMode = AudioRepeatMode.Off,
                playedEntryIds = setOf("a", "b"),
            )

        val selection = nextQueueSelection(state, force = true, random = Random(7))

        assertEquals(2, selection?.index)
        assertEquals(setOf("a", "b"), selection?.playedEntryIds)
        assertNull(
            nextQueueSelection(
                state.copy(playedEntryIds = setOf("a", "b", "c")),
                force = true,
                random = Random(7),
            )
        )
    }

    @Test
    fun queueRepeatResetsPlayedEntriesBeforeStartingTheNextShufflePass() {
        val state =
            AudioPlayerState(
                queue = queue,
                currentIndex = 2,
                shuffle = true,
                repeatMode = AudioRepeatMode.Queue,
                playedEntryIds = setOf("a", "b", "c"),
            )

        val selection = nextQueueSelection(state, force = true, random = Random(7))

        assertNotNull(selection)
        assertTrue(selection?.resetPlayed ?: false)
        assertEquals(emptySet<String>(), selection?.playedEntryIds)
        assertTrue(selection?.index != 2)
    }

    @Test
    fun markingAnEntryRemovesStaleIdsAndKeepsTheEntryPlayed() {
        val state = AudioPlayerState(queue = queue, playedEntryIds = setOf("a", "missing"))

        val marked = markQueueEntryPlayed(state, "b")

        assertEquals(setOf("a", "b"), marked?.playedEntryIds)
        assertNull(markQueueEntryPlayed(state, "missing"))
    }

    @Test
    fun removingCurrentEntrySelectsItsReplacementAndRemovingBeforeAdjustsIndex() {
        val current = AudioPlayerState(queue = queue, currentIndex = 1, isPlaying = true)

        val removedCurrent = removeQueueEntry(current, "b")
        assertEquals(listOf("a", "c"), removedCurrent?.entries?.map { it.entryId })
        assertEquals(1, removedCurrent?.currentIndex)
        assertEquals(true, removedCurrent?.removedCurrent)

        val removedBefore = removeQueueEntry(current, "a")
        assertEquals(0, removedBefore?.currentIndex)
        assertEquals(false, removedBefore?.removedCurrent)
    }

    @Test
    fun reorderingKeepsTheActiveEntryActive() {
        val state = AudioPlayerState(queue = queue, currentIndex = 1)

        val reordered = reorderQueue(state, 2, 0)

        assertEquals(listOf("c", "a", "b"), reordered?.queue?.map { it.entryId })
        assertEquals(2, reordered?.currentIndex)
    }

    @Test
    fun shufflingKeepsTheCurrentEntryAtTheFront() {
        val state = AudioPlayerState(queue = queue, currentIndex = 1)

        val shuffled = shuffleQueueKeepingCurrent(state, Random(7))

        assertEquals("b", shuffled.currentEntry?.entryId)
        assertEquals("b", shuffled.queue.first().entryId)
        assertEquals(setOf("a", "b", "c"), shuffled.queue.map { it.entryId }.toSet())
        assertEquals(true, shuffled.shuffle)
    }

    @Test
    fun newShufflePlaybackStartsWithTheRandomizedFirstEntry() {
        val playbackOrder =
            queuePlaybackOrder(queue, selectedIndex = 0, shuffle = true, random = Random(7))

        assertEquals(0, playbackOrder.currentIndex)
        assertNotEquals("a", playbackOrder.entries.first().entryId)
        assertEquals(
            setOf("a", "b", "c"),
            playbackOrder.entries.map { it.entryId }.toSet(),
        )
    }
}
