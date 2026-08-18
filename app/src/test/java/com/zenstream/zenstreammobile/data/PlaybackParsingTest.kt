package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaSource
import com.zenstream.zenstreammobile.model.MediaStream
import com.zenstream.zenstreammobile.model.TrickplayManifest
import com.zenstream.zenstreammobile.model.TrickplaySheet
import com.zenstream.zenstreammobile.ui.player.InitialSeekController
import com.zenstream.zenstreammobile.ui.player.MpvEndFileGate
import com.zenstream.zenstreammobile.ui.player.subtitleBottomPadding
import com.zenstream.zenstreammobile.ui.player.subtitleOutlineOffsets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackParsingTest {
    @Test
    fun parsesSessionFreeSourceMetadataTracks() {
        val source =
            parseMediaSource(
                JSONObject(
                    """{"id":"source-1","streams":[{"index":2,"codec_type":"audio","tags":{"title":"English","language":"en"}},{"index":4,"codec_type":"subtitle","disposition":{"default":1},"tags":{"language":"ja"}}]}"""
                )
            )

        assertEquals("source-1", source.id)
        assertEquals(
            listOf(
                MediaStream(2, "Audio", "English", "en"),
                MediaStream(4, "Subtitle", language = "ja", isDefault = true),
            ),
            source.mediaStreams,
        )
    }

    @Test
    fun identifiesGatewayRewrittenTranscodingUrlAsHls() {
        assertEquals(
            "application/x-mpegURL",
            playbackMimeType(
                MediaSource(
                    id = "source-1",
                    url = "/api/playback/sessions/session-1/master.m3u8?access=lease-1",
                )
            ),
        )
    }

    @Test
    fun attachesTheCanonicalNegotiatedUrlToTheInventorySource() {
        val source =
            withNegotiatedPlaybackUrl(
                MediaSource(id = "source-1", container = "matroska"),
                "/api/playback/sessions/session-1/master.m3u8?access=lease-1",
            )

        assertEquals(
            "/api/playback/sessions/session-1/master.m3u8?access=lease-1",
            source.url,
        )
        assertEquals("application/x-mpegURL", playbackMimeType(source))
    }

    @Test
    fun identifiesDirectMp4SourceFromContainerMetadata() {
        assertEquals(
            "video/mp4",
            playbackMimeType(MediaSource(id = "source-1", container = "mp4")),
        )
    }

    @Test
    fun buildsTrickplayPreviewFromTheNativeManifestSheetAndTileCoordinates() {
        val preview =
            trickplayPreview(
                MediaSource(
                    id = "source-1",
                    trickplay =
                        TrickplayManifest(
                            state = "ready",
                            sourceId = "source-1",
                            frameWidth = 640,
                            frameHeight = 360,
                            intervalSeconds = 5.0,
                            columns = 2,
                            rows = 2,
                            frameCount = 10,
                            sheets =
                                listOf(
                                    TrickplaySheet(
                                        0,
                                        4,
                                        "https://orchestrator.example/sheet-0.webp",
                                    ),
                                    TrickplaySheet(
                                        1,
                                        4,
                                        "https://orchestrator.example/sheet-1.webp",
                                    ),
                                    TrickplaySheet(
                                        2,
                                        2,
                                        "https://orchestrator.example/sheet-2.webp",
                                    ),
                                ),
                        ),
                ),
                45.0,
            )

        assertEquals(2, preview?.tileIndex)
        assertEquals(1, preview?.cellX)
        assertEquals(0, preview?.cellY)
        assertEquals(640, preview?.width)
        assertEquals("https://orchestrator.example/sheet-2.webp", preview?.url)
    }

    @Test
    fun parsingNativeManifestResolvesSheetUrlsAndDerivesFrameCount() {
        val manifest =
            parseTrickplayManifest(
                JSONObject(
                    """{
                    "state":"ready", "sourceId":"source-1", "frameWidth":320, "frameHeight":180,
                    "intervalSeconds":10, "columns":10, "rows":10,
                    "sheets":[{"index":0,"frameCount":100,"url":"/api/playback/items/item-1/trickplay/generation/0.webp?access=ticket"}]
                }"""
                ),
                "https://orchestrator.example",
            )

        assertEquals(100, manifest?.frameCount)
        assertEquals(320, manifest?.frameWidth)
        assertEquals(180, manifest?.frameHeight)
        assertEquals(
            "https://orchestrator.example/api/playback/items/item-1/trickplay/generation/0.webp?access=ticket",
            manifest?.sheets?.single()?.url,
        )
    }

    @Test
    fun trickplayPreviewIsUnavailableUntilTheManifestIsReady() {
        val preview =
            trickplayPreview(
                MediaSource(
                    id = "source-1",
                    trickplay =
                        TrickplayManifest(
                            state = "generating",
                            sourceId = "source-1",
                            frameWidth = 320,
                            frameHeight = 180,
                            intervalSeconds = 10.0,
                            columns = 10,
                            rows = 10,
                            frameCount = 1,
                            sheets = emptyList(),
                        ),
                ),
                1.0,
            )

        assertEquals(null, preview)
    }

    @Test
    fun resolvesRelativeNegotiatedPlaybackUrlAgainstServer() {
        val session =
            com.zenstream.zenstreammobile.model.AuthSession(
                "https://orchestrator.example",
                "token",
                "user",
                "name",
            )
        val url =
            playbackUrl(
                session,
                "item-1",
                com.zenstream.zenstreammobile.model.MediaSource(
                    id = "source-1",
                    url = "/api/playback/items/item-1/stream?sourceId=source-1&access=lease-1",
                ),
            )
        assertTrue(url.startsWith("https://orchestrator.example/api/playback/items/item-1/stream"))
        assertTrue(url.contains("sourceId=source-1"))
    }

    @Test
    fun canonicalPlaybackUrlCarriesTheOrchestratorResourceTicket() {
        val session =
            com.zenstream.zenstreammobile.model.AuthSession(
                "https://orchestrator.example",
                "token",
                "user",
                "name",
                "resource-ticket",
            )
        val url =
            playbackUrl(
                session,
                "item-1",
                com.zenstream.zenstreammobile.model.MediaSource(
                    id = "source-1",
                    url =
                        "/api/playback/items/item-1/stream?sourceId=source-1&access=resource-ticket",
                ),
            )

        assertTrue(url.contains("access=resource-ticket"))
    }

    @Test
    fun readsNegotiatedStreamTimelineOriginFromStartTimeTicks() {
        val session =
            com.zenstream.zenstreammobile.model.AuthSession(
                "https://orchestrator.example",
                "token",
                "user",
                "name",
            )
        val origin =
            playbackStreamStartPositionSeconds(
                session,
                com.zenstream.zenstreammobile.model.MediaSource(
                    id = "source-1",
                    url = "/api/playback/sessions/session-1/master.m3u8?access=ticket",
                ),
                requestedStartSeconds = 125.0,
            )

        assertEquals(0.0, origin, 0.001)
    }

    @Test
    fun fallbackPlaybackUsesRequestedStartAsItsTimelineOrigin() {
        val session =
            com.zenstream.zenstreammobile.model.AuthSession(
                "https://orchestrator.example",
                "token",
                "user",
                "name",
            )

        assertEquals(
            0.0,
            playbackStreamStartPositionSeconds(
                session,
                com.zenstream.zenstreammobile.model.MediaSource(id = "source-1"),
                requestedStartSeconds = 42.0,
            ),
            0.001,
        )
    }

    @Test
    fun fullLengthNegotiatedPlaybackKeepsZeroAsTheTimelineOrigin() {
        val session =
            com.zenstream.zenstreammobile.model.AuthSession(
                "https://orchestrator.example",
                "token",
                "user",
                "name",
            )

        assertEquals(
            0.0,
            playbackStreamStartPositionSeconds(
                session,
                com.zenstream.zenstreammobile.model.MediaSource(
                    id = "source-1",
                    url = "/api/playback/sessions/session-1/master.m3u8?access=ticket",
                ),
                requestedStartSeconds = 125.0,
            ),
            0.001,
        )
    }

    @Test
    fun gatewayLeaseDoesNotCreateAHiddenTimelineOrigin() {
        val session =
            com.zenstream.zenstreammobile.model.AuthSession(
                "https://orchestrator.example",
                "token",
                "user",
                "name",
            )

        assertEquals(
            0.0,
            playbackStreamStartPositionSeconds(
                session,
                com.zenstream.zenstreammobile.model.MediaSource(
                    id = "source-1",
                    url = "/api/playback/sessions/session-1/master.m3u8?access=opaque",
                ),
                requestedStartSeconds = 125.0,
                streamStartsAtRequestedPosition = true,
            ),
            0.001,
        )
    }

    @Test
    fun reloadPositionRemainsOnTheNativeMediaTimeline() {
        assertEquals(140.0, playbackLocalPositionSeconds(140.0, 125.0), 0.001)
        assertEquals(100.0, playbackLocalPositionSeconds(100.0, 125.0), 0.001)
    }

    @Test
    fun subtitleRequestUsesAbsoluteTimestampsFromTheItemTimeline() {
        val query =
            subtitleWebVttQuery(
                com.zenstream.zenstreammobile.model.AuthSession(
                    "https://orchestrator.example",
                    "token",
                    "user",
                    "name",
                ),
                "item-1",
                "source-1",
            )

        assertEquals("false", query["copyTimestamps"])
        assertEquals("false", query["addVttTimeMap"])
        assertEquals("0", query["startPositionTicks"])
    }

    @Test
    fun subtitleRequestIncludesResourceTicketWhenAvailable() {
        val query =
            subtitleWebVttQuery(
                com.zenstream.zenstreammobile.model.AuthSession(
                    "https://orchestrator.example",
                    "token",
                    "user",
                    "name",
                    resourceTicket = "ticket",
                ),
                "item-1",
                "source-1",
            )

        assertEquals("ticket", query["access"])
    }

    @Test
    fun parsesWebVttCuesAndStripsMarkup() {
        val cues =
            parseWebVttCues("WEBVTT\n\n00:01.500 --> 00:03.000\n<00:01.500>Hello <b>world</b>!\n")
        assertEquals(1, cues.size)
        assertEquals(1.5, cues.first().startSeconds, 0.001)
        assertEquals("Hello world!", cues.first().text)
    }

    @Test
    fun parsesFlexibleWebVttTimingAndAuthoredTextMarkup() {
        val cues =
            parseWebVttCues(
                "\uFEFFWEBVTT\r\n\r\n" +
                    "00:00:01.000-->00:00:03.500 align:start\r\n" +
                    "{\\bord4}Hello<br>world &amp; friends\r\n"
            )

        assertEquals(1, cues.size)
        assertEquals(1.0, cues.first().startSeconds, 0.001)
        assertEquals(3.5, cues.first().endSeconds, 0.001)
        assertEquals("Hello\nworld & friends", cues.first().text)
    }

    @Test
    fun normalizesSubtitleStyleRangesAndFont() {
        val style =
            normalizeSubtitleStyle(
                com.zenstream.zenstreammobile.model.SubtitleStyle(
                    fontFamily = "unknown",
                    textScale = 400f,
                    bottomSpacing = 400f,
                    borderSize = -1f,
                    backgroundOpacity = 150f,
                )
            )
        assertEquals("sans", style.fontFamily)
        assertEquals(200f, style.textScale)
        assertEquals(300f, style.bottomSpacing)
        assertEquals(0f, style.borderSize)
        assertEquals(100f, style.backgroundOpacity)
    }

    @Test
    fun subtitleBottomPaddingUsesTheConfiguredValueWithoutControlClearance() {
        assertEquals(
            0f,
            subtitleBottomPadding(
                com.zenstream.zenstreammobile.model.SubtitleStyle(bottomSpacing = 0f)
            ).value,
        )
        assertEquals(
            217f,
            subtitleBottomPadding(
                com.zenstream.zenstreammobile.model.SubtitleStyle(bottomSpacing = 217f)
            ).value,
        )
        assertEquals(
            300f,
            subtitleBottomPadding(
                com.zenstream.zenstreammobile.model.SubtitleStyle(bottomSpacing = 300f)
            ).value,
        )
    }

    @Test
    fun selectsCuesFromTheFreshPlaybackPositionAtBoundaries() {
        val cues =
            listOf(
                com.zenstream.zenstreammobile.model.SubtitleCue(1.0, 2.0, "first"),
                com.zenstream.zenstreammobile.model.SubtitleCue(2.0, 3.0, "second"),
            )

        assertEquals("first", activeSubtitleCues(cues, 1.999).single().text)
        assertEquals("second", activeSubtitleCues(cues, 2.0).single().text)
        assertTrue(activeSubtitleCues(cues, 0.5).isEmpty())
    }

    @Test
    fun mapsLocalPlaybackPositionToTheAbsoluteSubtitleTimeline() {
        val cues =
            listOf(com.zenstream.zenstreammobile.model.SubtitleCue(125.0, 130.0, "resume cue"))

        assertEquals(
            "resume cue",
            activeSubtitleCues(cues, positionSeconds = 0.0, timelineOriginSeconds = 125.0)
                .single()
                .text,
        )
        assertEquals(
            "resume cue",
            activeSubtitleCues(cues, positionSeconds = 5.0, timelineOriginSeconds = 120.0)
                .single()
                .text,
        )
        assertTrue(
            activeSubtitleCues(cues, positionSeconds = 5.0, timelineOriginSeconds = 125.0).isEmpty()
        )
    }

    @Test
    fun discardsSubtitleResponsesFromOlderTrackOrPlaybackGenerations() {
        assertTrue(isCurrentSubtitleRequest(3L, 3L, 2, 2, "source-1", "source-1"))
        assertFalse(isCurrentSubtitleRequest(2L, 3L, 2, 2, "source-1", "source-1"))
        assertFalse(isCurrentSubtitleRequest(3L, 3L, 2, 3, "source-1", "source-1"))
        assertFalse(isCurrentSubtitleRequest(3L, 3L, 2, 2, "source-1", "source-2"))
    }

    @Test
    fun keepsSimultaneousSubtitleCuesAvailableForStackedRendering() {
        val cues =
            listOf(
                com.zenstream.zenstreammobile.model.SubtitleCue(1.0, 3.0, "top"),
                com.zenstream.zenstreammobile.model.SubtitleCue(1.5, 2.5, "bottom"),
            )

        assertEquals(listOf("top", "bottom"), activeSubtitleCues(cues, 2.0).map { it.text })
    }

    @Test
    fun subtitleOutlineOffsetsFollowTheConfiguredBorderSize() {
        assertTrue(subtitleOutlineOffsets(0f).isEmpty())
        assertEquals(8, subtitleOutlineOffsets(4f).size)
        assertTrue(subtitleOutlineOffsets(4f).contains(-4 to 4))
        assertTrue(subtitleOutlineOffsets(20f).all { (x, y) -> x in -8..8 && y in -8..8 })
    }

    @Test
    fun initialSeekIsConsumedOnlyOnceWhenTheEngineBecomesReady() {
        val seek = InitialSeekController()
        seek.schedule(42.0)

        assertEquals(42.0, requireNotNull(seek.consume()), 0.001)
        assertEquals(null, seek.consume())
    }

    @Test
    fun zeroInitialSeekDoesNotScheduleASeek() {
        val seek = InitialSeekController()
        seek.schedule(0.0)

        assertEquals(null, seek.consume())
    }

    @Test
    fun manualSeekCancelsThePendingInitialSeek() {
        val seek = InitialSeekController()
        seek.schedule(42.0)
        seek.cancel()

        assertEquals(null, seek.consume())
    }

    @Test
    fun replacementEndFileIsNotReportedAsEpisodeCompletion() {
        val gate = MpvEndFileGate()

        gate.onSourceLoading()
        assertTrue(gate.shouldReportEndFile())

        gate.onSourceLoading()
        assertFalse(gate.shouldReportEndFile())
        assertTrue(gate.shouldReportEndFile())
    }
}
