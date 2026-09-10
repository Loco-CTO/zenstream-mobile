package com.zenstream.zenstreammobile.audio

import com.zenstream.zenstreammobile.model.AudioPlayerState
import com.zenstream.zenstreammobile.model.AudioQueueEntry
import com.zenstream.zenstreammobile.model.AudioRepeatMode
import com.zenstream.zenstreammobile.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

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
}
