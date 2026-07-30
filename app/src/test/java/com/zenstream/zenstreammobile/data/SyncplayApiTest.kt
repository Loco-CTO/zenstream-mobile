package com.zenstream.zenstreammobile.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncplayApiTest {
    @Test
    fun parsesCanonicalGroupTimelineAndMembers() {
        val group = parseSyncplayGroup(
            JSONObject()
                .put("id", "room-1")
                .put("name", "Ada's group")
                .put("hostUserId", "user-1")
                .put("hostName", "Ada")
                .put("allowViewerControls", true)
                .put("itemId", "episode-7")
                .put("position", 12.5)
                .put("playing", true)
                .put("revision", 8)
                .put("timelineRevision", 6)
                .put("mediaGeneration", 3)
                .put("anchorPosition", 12.5)
                .put("anchorServerTime", 100.0)
                .put("effectiveAt", 101.0)
                .put("playbackState", "playing")
                .put("members", JSONArray().put(JSONObject()
                    .put("userId", "user-1")
                    .put("participantId", "device-1")
                    .put("username", "Ada")
                    .put("watchingTogether", true)
                    .put("viewing", true)
                    .put("loading", false)
                    .put("readyGeneration", 3)
                    .put("role", "host"))),
        )

        assertEquals("room-1", group.id)
        assertEquals("episode-7", group.itemId)
        assertTrue(group.allowViewerControls)
        assertEquals(6, group.timelineRevision)
        assertEquals("device-1", group.members.single().participantId)
        assertFalse(group.members.single().loading)
    }

    @Test
    fun groupCollectionParsesEmptyAndPopulatedPayloads() {
        assertTrue((null as JSONArray?).toGroups().isEmpty())
        assertEquals(
            listOf("room-1"),
            JSONArray().put(JSONObject().put("id", "room-1").put("members", JSONArray())).toGroups().map { it.id },
        )
    }

    @Test
    fun parsesJsonNullMediaIdAsNoMedia() {
        val nullGroup = parseSyncplayGroup(JSONObject().put("id", "room-1").put("itemId", JSONObject.NULL))
        val stringNullGroup = parseSyncplayGroup(JSONObject().put("id", "room-2").put("itemId", "null"))

        assertNull(nullGroup.itemId)
        assertNull(stringNullGroup.itemId)
    }

    @Test
    fun rejectsPresenceReportsFromSupersededSyncplayTimelines() {
        val current = parseSyncplayGroup(
            JSONObject()
                .put("id", "room-1")
                .put("itemId", "item-1")
                .put("mediaGeneration", 3)
                .put("timelineRevision", 7),
        )

        assertTrue(syncplayPresenceReportIsCurrent(current, current))
        assertFalse(syncplayPresenceReportIsCurrent(current.copy(timelineRevision = 6), current))
        assertFalse(syncplayPresenceReportIsCurrent(current.copy(mediaGeneration = 2), current))
        assertFalse(syncplayPresenceReportIsCurrent(current.copy(itemId = "item-2"), current))
    }

    @Test
    fun syncplaySocketDoesNotExpireDuringAnIdleRoom() {
        val client = syncplaySocketClient()

        assertEquals(0, client.readTimeoutMillis)
        assertEquals(25_000, client.pingIntervalMillis)
    }
}
