package com.zenstream.zenstreammobile.data

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.util.Log
import com.zenstream.zenstreammobile.BuildConfig
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.DerivedHomeData
import com.zenstream.zenstreammobile.model.DetailData
import com.zenstream.zenstreammobile.model.FavoriteSort
import com.zenstream.zenstreammobile.model.HomeData
import com.zenstream.zenstreammobile.model.Library
import com.zenstream.zenstreammobile.model.LibraryData
import com.zenstream.zenstreammobile.model.LibrarySort
import com.zenstream.zenstreammobile.model.LibrarySortBy
import com.zenstream.zenstreammobile.model.MediaChapter
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.MediaPerson
import com.zenstream.zenstreammobile.model.MediaRow
import com.zenstream.zenstreammobile.model.MediaSource
import com.zenstream.zenstreammobile.model.MediaStream
import com.zenstream.zenstreammobile.model.NotificationItem
import com.zenstream.zenstreammobile.model.NotificationPage
import com.zenstream.zenstreammobile.model.PagedFavorites
import com.zenstream.zenstreammobile.model.PagedLibrary
import com.zenstream.zenstreammobile.model.PlaybackData
import com.zenstream.zenstreammobile.model.PlaybackOptions
import com.zenstream.zenstreammobile.model.PlaybackSegment
import com.zenstream.zenstreammobile.model.PlaybackSegmentType
import com.zenstream.zenstreammobile.model.PlaybackSessionStatus
import com.zenstream.zenstreammobile.model.RowTitle
import com.zenstream.zenstreammobile.model.TrickplayManifest
import com.zenstream.zenstreammobile.model.TrickplaySheet
import com.zenstream.zenstreammobile.model.ViewerCommand
import com.zenstream.zenstreammobile.model.ViewerCommandAck
import com.zenstream.zenstreammobile.model.ViewerEnd
import com.zenstream.zenstreammobile.model.ViewerHeartbeat
import com.zenstream.zenstreammobile.model.orderedHomeRows
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

data class EpisodeNeighbors(
    val previous: MediaItem? = null,
    val next: MediaItem? = null,
)

class CatalogApi(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private var deviceId: String = UUID.randomUUID().toString(),
) {
    fun setDeviceId(value: String) {
        if (value.isNotBlank()) deviceId = value
    }

    private fun deviceMetadata(): JSONObject =
        JSONObject()
            .put("deviceId", deviceId)
            .put("deviceType", "mobile")
            .put("operatingSystem", "Android ${Build.VERSION.RELEASE}")
            .put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("clientName", "ZenStream Android")
            .put("clientVersion", BuildConfig.ZENSTREAM_VERSION)

    suspend fun authenticate(
        serverUrl: String,
        username: String,
        password: String,
        persistedDeviceId: String? = null,
    ): AuthSession =
        withContext(Dispatchers.IO) {
            persistedDeviceId?.let(::setDeviceId)
            val server = normalizeServerUrl(serverUrl)
            val body =
                JSONObject()
                    .put("username", username.trim())
                    .put("password", password)
                    .put("device", deviceMetadata())
                    .toString()
            val json =
                requestJson(
                    server = server,
                    path = "/api/auth/login",
                    token = null,
                    method = "POST",
                    body = body,
                )
            val user = json.optJSONObject("user")
            val token =
                json.optString("token").takeIf { it.isNotBlank() }
                    ?: error("Server did not return an access token")
            val userId =
                user?.optString("id").orEmpty().takeIf { it.isNotBlank() }
                    ?: error("Server did not return a user ID")
            val ticket =
                requestJson(server, "/api/auth/resource-ticket", token = token)
                    .optString("ticket")
                    .takeIf { it.isNotBlank() }
            AuthSession(
                server,
                token,
                userId,
                user?.optString("username").orEmpty().ifBlank { username.trim() },
                ticket,
                user?.optNullableString("avatarVersion"),
            )
        }

    suspend fun refreshAccount(session: AuthSession): AuthSession =
        withContext(Dispatchers.IO) {
            val user =
                requestJson(session, "/api/auth/me").optJSONObject("user")
                    ?: error("Server did not return the authenticated user")
            val userId =
                user.optString("id").takeIf { it.isNotBlank() }
                    ?: error("Server did not return a user ID")
            check(userId == session.userId) { "Server returned a different authenticated user" }
            session.copy(
                username = user.optString("username").ifBlank { session.username },
                avatarVersion = user.optNullableString("avatarVersion"),
            )
        }

    suspend fun uploadAvatar(
        session: AuthSession,
        resolver: ContentResolver,
        uri: Uri,
        crop: AvatarCrop,
    ): String =
        withContext(Dispatchers.IO) {
            val source = resolver.avatarSourceInfo(uri)
            val body =
                AvatarUriRequestBody(
                    resolver = resolver,
                    uri = uri,
                    mimeType = source.mimeType.toMediaType(),
                    declaredSize = source.sizeBytes,
                )
            uploadAvatar(session, body, source.mimeType, crop)
        }

    internal suspend fun uploadAvatar(
        session: AuthSession,
        body: RequestBody,
        contentType: String,
        crop: AvatarCrop,
    ): String =
        withContext(Dispatchers.IO) {
            if (contentType.lowercase() !in AVATAR_MIME_TYPES) {
                throw AvatarUnsupportedFormatException()
            }
            val url =
                session.serverUrl
                    .toHttpUrl()
                    .newBuilder()
                    .addPathSegments("api/account/avatar")
                    .addQueryParameter("cropX", crop.cropX.toString())
                    .addQueryParameter("cropY", crop.cropY.toString())
                    .addQueryParameter("cropSize", crop.cropSize.toString())
                    .addQueryParameter("rotation", crop.rotation.toString())
                    .build()
            val request =
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("Authorization", authorizationHeader(session.token))
                    .header("Content-Type", contentType)
                    .post(body)
                    .build()
            val response = httpClient.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) {
                    throw CatalogException(it.code, "Avatar upload failed with ${it.code}")
                }
                JSONObject(it.body?.string().orEmpty().ifBlank { "{}" })
                    .optNullableString("avatarVersion")
                    ?: error("Server did not return an avatar version")
            }
        }

    suspend fun deleteAvatar(session: AuthSession) =
        withContext(Dispatchers.IO) {
            requestJson(session, "/api/account/avatar", method = "DELETE")
        }

    suspend fun changePassword(
        session: AuthSession,
        currentPassword: String,
        newPassword: String,
        confirmNewPassword: String,
    ) =
        withContext(Dispatchers.IO) {
            requestJson(
                session,
                "/api/account/password",
                method = "POST",
                body =
                    JSONObject()
                        .put("currentPassword", currentPassword)
                        .put("newPassword", newPassword)
                        .put("confirmNewPassword", confirmNewPassword)
                        .toString(),
            )
        }

    suspend fun logout(session: AuthSession) =
        withContext(Dispatchers.IO) {
            requestJson(session, "/api/auth/logout", method = "POST")
        }

    suspend fun playback(
        session: AuthSession,
        itemId: String,
        options: PlaybackOptions = PlaybackOptions(),
    ): PlaybackData =
        withContext(Dispatchers.IO) {
            val item = getItem(session, itemId)
            val resumePositionSeconds =
                item.playbackPositionTicks?.div(10_000_000.0)?.takeIf { it.isFinite() && it > 0.0 }
                    ?: 0.0
            val negotiatedOptions =
                options.copy(
                    startPositionSeconds =
                        options.startPositionSeconds.takeIf { it > 0.0 } ?: resumePositionSeconds
                )
            val capabilities = playbackCapabilities(negotiatedOptions.engine)
            val json =
                requestJson(
                    session,
                    "/api/playback/items/$itemId/negotiate",
                    method = "POST",
                    body =
                        JSONObject()
                            .put("engine", capabilities.engine)
                            .put("device", deviceMetadata())
                            .put("sourceId", negotiatedOptions.sourceId)
                            .put("requestedMode", negotiatedOptions.requestedMode)
                            .put("forceTranscoding", negotiatedOptions.forceTranscoding)
                            .put("containers", JSONArray(capabilities.containers))
                            .put("videoCodecs", JSONArray(capabilities.videoCodecs))
                            .put("audioCodecs", JSONArray(capabilities.audioCodecs))
                            .put("maxAudioChannels", capabilities.maxAudioChannels)
                            .put("maxStreamingBitrate", negotiatedOptions.maxStreamingBitrate)
                            .put("startPositionSeconds", negotiatedOptions.startPositionSeconds)
                            .put("audioStreamId", negotiatedOptions.audioStreamId)
                            .toString(),
                )
            val sourcePayload =
                json.optJSONObject("source") ?: error("Server did not return a media source")
            // The negotiated stream URL belongs to the canonical response, rather
            // than the media-source inventory record. Preserve it on the source
            // consumed by the player so direct and HLS playback use the same path.
            val source =
                withNegotiatedPlaybackUrl(
                    parseMediaSource(sourcePayload),
                    json.optString("url"),
                )
            val sessionId = json.optString("sessionId").ifBlank { null }
            val sessionState = json.optString("sessionState").ifBlank { null }
            Log.i(
                PLAYBACK_TAG,
                "negotiated item=$itemId mode=${json.optString("mode")} sessionId=${sessionId ?: "none"} state=${sessionState ?: "none"} url=${redactPlaybackUrl(json.optString("url"))}",
            )
            if (sessionId != null && sessionState == "starting") {
                val status = awaitPlaybackReady(session, sessionId)
                json.put("sessionState", status.sessionState)
                Log.i(
                    PLAYBACK_TAG,
                    "session ready sessionId=$sessionId state=${status.sessionState} playlistReady=${status.playlistReady} segments=${status.segmentCount}",
                )
            }
            PlaybackData(
                item = item,
                source = source,
                audioTracks = source.mediaStreams.filter { it.type.equals("audio", true) },
                subtitles = source.mediaStreams.filter { it.type == "Subtitle" },
                segments = source.id?.let { playbackSegments(session, itemId, it) } ?: emptyList(),
                mode = json.optString("mode").ifBlank { null },
                sessionState = json.optString("sessionState").ifBlank { null },
                sessionId = sessionId,
                viewerSessionId = json.optString("viewerSessionId").ifBlank { null },
                url = json.optString("url").ifBlank { null },
                durationSeconds = json.optDoubleOrNull("durationSeconds"),
                startPositionSeconds = json.optDoubleOrNull("startPositionSeconds") ?: 0.0,
                expiresAt = json.optString("expiresAt").ifBlank { null },
                errorCode = json.optString("errorCode").ifBlank { null },
                errorDetail = json.optString("errorDetail").ifBlank { null },
            )
        }

    suspend fun playbackSource(session: AuthSession, itemId: String): MediaSource =
        withContext(Dispatchers.IO) {
            parseMediaSource(
                requestJson(
                    session,
                    "/api/playback/items/${android.net.Uri.encode(itemId)}/source",
                )
            )
        }

    private suspend fun playbackSegments(
        session: AuthSession,
        itemId: String,
        sourceId: String,
    ): List<PlaybackSegment> =
        runCatching {
                val item =
                    requestJson(
                        session,
                        "/api/playback/items/${android.net.Uri.encode(itemId)}/segments?sourceId=${android.net.Uri.encode(sourceId)}",
                    )
                val values = item.optJSONArray("segments") ?: return@runCatching emptyList()
                List(values.length()) { index -> values.optJSONObject(index) ?: JSONObject() }
                    .mapNotNull { segment ->
                        val type =
                            when (segment.optString("type").lowercase()) {
                                "intro" -> PlaybackSegmentType.INTRO
                                "outro" -> PlaybackSegmentType.OUTRO
                                else -> return@mapNotNull null
                            }
                        marker(
                            type,
                            segment.optDouble("startSeconds", Double.NaN),
                            segment.optDouble("endSeconds", Double.NaN),
                        )
                    }
            }
            .getOrDefault(emptyList())

    suspend fun playbackStatus(
        session: AuthSession,
        sessionId: String,
    ): PlaybackSessionStatus =
        withContext(Dispatchers.IO) {
            parsePlaybackSessionStatus(
                sessionId,
                requestJson(session, "/api/playback/sessions/$sessionId"),
            )
        }

    suspend fun cancelPlaybackSession(session: AuthSession, sessionId: String) =
        withContext(Dispatchers.IO) {
            requestJson(
                session,
                "/api/playback/sessions/${android.net.Uri.encode(sessionId)}",
                method = "DELETE",
            )
        }

    suspend fun heartbeatPlaybackViewer(
        session: AuthSession,
        viewerSessionId: String,
        positionSeconds: Double,
        durationSeconds: Double,
        paused: Boolean,
        workerSessionId: String?,
        commandAcks: List<ViewerCommandAck> = emptyList(),
    ): ViewerHeartbeat =
        withContext(Dispatchers.IO) {
            val acks =
                JSONArray().apply {
                    commandAcks.take(32).forEach { ack ->
                        put(
                            JSONObject()
                                .put("id", ack.id)
                                .put("success", ack.success)
                                .put("error", ack.error)
                        )
                    }
                }
            val body =
                JSONObject()
                    .put("positionSeconds", positionSeconds.coerceAtLeast(0.0))
                    .put("paused", paused)
                    .put("commandAcks", acks)
            durationSeconds
                .takeIf { it.isFinite() && it > 0 }
                ?.let { body.put("durationSeconds", it) }
            workerSessionId?.let { body.put("workerSessionId", it) }
            val json =
                requestJson(
                    session,
                    "/api/playback/viewers/${android.net.Uri.encode(viewerSessionId)}/heartbeat",
                    method = "POST",
                    body = body.toString(),
                )
            val commands = json.optJSONArray("commands")
            ViewerHeartbeat(
                commands =
                    if (commands == null) {
                        emptyList()
                    } else {
                        List(commands.length()) { index ->
                                commands.optJSONObject(index)
                            }
                            .mapNotNull { command ->
                                command
                                    ?.optString("id")
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let {
                                        ViewerCommand(
                                            id = it,
                                            action = command.optString("action"),
                                            issuedAt =
                                                command.optString("issuedAt").ifBlank { null },
                                        )
                                    }
                            }
                    }
            )
        }

    suspend fun endPlaybackViewer(
        session: AuthSession,
        viewerSessionId: String,
    ): ViewerEnd =
        withContext(Dispatchers.IO) {
            val json =
                requestJson(
                    session,
                    "/api/playback/viewers/${android.net.Uri.encode(viewerSessionId)}",
                    method = "DELETE",
                )
            ViewerEnd(
                workerSessionId = json.optString("workerSessionId").ifBlank { null },
                stopWorker = json.optBoolean("stopWorker", false),
            )
        }

    private suspend fun awaitPlaybackReady(
        session: AuthSession,
        sessionId: String,
    ): PlaybackSessionStatus {
        val deadline = android.os.SystemClock.elapsedRealtime() + PLAYBACK_READY_TIMEOUT_MILLIS
        var latest: PlaybackSessionStatus? = null
        while (android.os.SystemClock.elapsedRealtime() <= deadline) {
            latest = playbackStatus(session, sessionId)
            Log.i(
                PLAYBACK_TAG,
                "session status sessionId=$sessionId state=${latest.sessionState} playlistReady=${latest.playlistReady} segments=${latest.segmentCount} processAlive=${latest.processAlive}",
            )
            if (latest.sessionState == "ready" && latest.playlistReady) return latest
            if (latest.sessionState in setOf("failed", "stopping", "expired")) {
                Log.e(
                    PLAYBACK_TAG,
                    "session failed sessionId=$sessionId code=${latest.errorCode} detail=${latest.errorDetail}",
                )
                error(latest.errorCode ?: "Playback session failed before becoming ready")
            }
            delay(PLAYBACK_READY_INTERVAL_MILLIS)
        }
        error(latest?.errorCode ?: "Playback session did not become ready before the deadline")
    }

    private fun parsePlaybackSessionStatus(
        sessionId: String,
        json: JSONObject,
    ): PlaybackSessionStatus =
        PlaybackSessionStatus(
            sessionId = json.optString("sessionId").ifBlank { sessionId },
            sessionState = json.optString("sessionState"),
            playlistReady = json.optBoolean("playlistReady"),
            segmentCount = json.optInt("segmentCount"),
            processAlive = json.optBoolean("processAlive"),
            errorCode = json.optString("errorCode").ifBlank { null },
            errorDetail = json.optString("errorDetail").ifBlank { null },
            lastAccessedAt = json.optString("lastAccessedAt").ifBlank { null },
        )

    suspend fun trickplay(
        session: AuthSession,
        itemId: String,
        sourceId: String?,
    ): TrickplayManifest? =
        withContext(Dispatchers.IO) {
            val manifest =
                requestJson(
                    session,
                    "/api/playback/items/${android.net.Uri.encode(itemId)}/trickplay",
                    query =
                        buildMap {
                            sourceId?.takeIf(String::isNotBlank)?.let { put("sourceId", it) }
                        },
                )
            parseTrickplayManifest(manifest, session.serverUrl)
        }

    suspend fun subtitleWebVtt(
        session: AuthSession,
        itemId: String,
        sourceId: String?,
        streamIndex: Int,
    ): String =
        withContext(Dispatchers.IO) {
            val source =
                requestJson(
                    session,
                    "/api/playback/items/${android.net.Uri.encode(itemId)}/source",
                )
            val streams = source.optJSONArray("streams")
            val mediaFileId =
                streams?.let { array ->
                    (0 until array.length())
                        .asSequence()
                        .mapNotNull { array.optJSONObject(it) }
                        .firstOrNull { it.optInt("index", -1) == streamIndex }
                        ?.optString("fileId")
                        ?.takeIf { it.isNotBlank() }
                } ?: error("Subtitle track is not an external text subtitle")
            val builder =
                "${session.serverUrl}/api/playback/items/$itemId/subtitles/$mediaFileId.vtt"
                    .toHttpUrl()
                    .newBuilder()
            session.resourceTicket?.let { builder.addQueryParameter("access", it) }
            val request =
                Request.Builder()
                    .url(builder.build())
                    .header("Accept", "text/vtt")
                    .header("Authorization", "Bearer ${session.token}")
                    .get()
                    .build()
            httpClient.newCall(request).execute().use {
                if (!it.isSuccessful)
                    throw CatalogException(
                        it.code,
                        "Subtitle request failed with ${it.code}",
                    )
                it.body?.string().orEmpty()
            }
        }

    suspend fun reportPlayback(
        session: AuthSession,
        itemId: String,
        positionSeconds: Double,
        isPaused: Boolean,
        playSessionId: String?,
        durationSeconds: Double? = null,
    ) =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("positionSeconds", positionSeconds.coerceAtLeast(0.0))
            durationSeconds
                ?.takeIf { it.isFinite() && it > 0 }
                ?.let { body.put("durationSeconds", it) }
            requestJson(
                session,
                "/api/catalog/items/$itemId/state",
                method = "PATCH",
                body = body.toString(),
            )
        }

    internal fun playbackQuery(
        session: AuthSession,
        options: PlaybackOptions,
    ): Map<String, String> = playbackParameters(session, options).mapValues { it.value.toString() }

    internal fun playbackBody(session: AuthSession, options: PlaybackOptions): String =
        JSONObject()
            .put("maxStreamingBitrate", options.maxStreamingBitrate)
            .put("startPositionSeconds", options.startPositionSeconds)
            .put("sourceId", options.sourceId)
            .put("audioStreamId", options.audioStreamId)
            .put("forceTranscoding", options.forceTranscoding)
            .put("directPlayOnly", options.directPlayOnly)
            .put("requestedMode", options.requestedMode)
            .put("DeviceProfile", deviceProfile(options))
            .toString()

    private fun playbackParameters(
        session: AuthSession,
        options: PlaybackOptions,
    ): Map<String, Any?> =
        mapOf(
                "startPositionSeconds" to options.startPositionSeconds,
                "maxStreamingBitrate" to options.maxStreamingBitrate,
                "sourceId" to options.sourceId,
                "audioStreamId" to options.audioStreamId,
                "forceTranscoding" to options.forceTranscoding,
                "directPlayOnly" to options.directPlayOnly,
                "requestedMode" to options.requestedMode,
            )
            .filterValues { it != null }

    private fun deviceProfile(options: PlaybackOptions): JSONObject {
        val directPlay =
            JSONArray()
                .put(
                    JSONObject()
                        .put("Type", "Video")
                        .put("VideoCodec", "h264,h265,vp9,av1")
                        .put("AudioCodec", "aac,ac3,opus,vorbis,mp3")
                        .put("Container", "mp4,mkv,webm")
                )
        val subtitles = JSONArray().put(JSONObject().put("Format", "vtt").put("Method", "External"))
        val transcoding =
            JSONArray()
                .put(
                    JSONObject()
                        .put("Type", "Video")
                        .put("Context", "Streaming")
                        .put("Protocol", "hls")
                        .put("Container", "ts")
                        .put("VideoCodec", "h264")
                        .put("AudioCodec", "aac")
                        .put("MaxAudioChannels", "2")
                        .put("MinSegments", 1)
                        .put("BreakOnNonKeyFrames", true)
                )
        return JSONObject()
            .put("Name", "ZenStream Android")
            .put("MaxStreamingBitrate", options.maxStreamingBitrate)
            .put("DirectPlayProfiles", directPlay)
            .put("SubtitleProfiles", subtitles)
            .put(
                "TranscodingProfiles",
                if (options.directPlayOnly) JSONArray() else transcoding,
            )
    }

    suspend fun fetchHome(session: AuthSession): HomeData =
        withContext(Dispatchers.IO) {
            parseHomeData(
                requestJson(
                    session,
                    "/api/catalog/home",
                    requestTimeoutMillis = HOME_REQUEST_TIMEOUT_MILLIS,
                )
            )
        }

    suspend fun fetchHomeFeatured(session: AuthSession): List<MediaItem> =
        withContext(Dispatchers.IO) {
            catalogItems(homeSection(session, "featured"), "latestItems")
                .filter { it.backdropImageTags.isNotEmpty() }
                .take(5)
        }

    suspend fun fetchHomeContinueWatching(session: AuthSession): List<MediaItem> =
        withContext(Dispatchers.IO) {
            catalogItems(homeSection(session, "continueWatching"), "continueWatching")
        }

    suspend fun fetchHomeNextUp(session: AuthSession): List<MediaItem> =
        withContext(Dispatchers.IO) {
            catalogItems(homeSection(session, "nextUp"), "nextUp")
        }

    suspend fun fetchHomeDerived(session: AuthSession): DerivedHomeData =
        withContext(Dispatchers.IO) {
            parseDerivedHomeData(homeSection(session, "derived"))
        }

    private suspend fun homeSection(session: AuthSession, section: String): JSONObject =
        requestJson(
            session,
            "/api/catalog/home?section=${android.net.Uri.encode(section)}",
            requestTimeoutMillis = HOME_REQUEST_TIMEOUT_MILLIS,
        )

    internal fun nextUpItemsQuery(userId: String): Map<String, String> =
        mapOf(
            "userId" to userId,
            "limit" to "18",
            "startIndex" to "0",
            "fields" to ITEM_FIELDS,
            "enableImages" to "true",
            "imageTypeLimit" to "1",
            "enableImageTypes" to ITEM_IMAGE_TYPES,
            "enableUserData" to "true",
            "enableTotalRecordCount" to "false",
            "disableFirstEpisode" to "true",
            "enableResumable" to "false",
            "enableRewatching" to "false",
        )

    internal fun latestItemsQuery(userId: String): Map<String, String> =
        mapOf(
            "userId" to userId,
            "startIndex" to "0",
            "limit" to "25",
            "recursive" to "true",
            "includeItemTypes" to "Series,Movie",
            "sortBy" to "added",
            "sortOrder" to "Descending",
            "fields" to ITEM_FIELDS,
            "enableImages" to "true",
            "imageTypeLimit" to "1",
            "enableImageTypes" to ITEM_IMAGE_TYPES,
            "enableUserData" to "true",
        )

    suspend fun getLibraries(
        session: AuthSession,
        requestTimeoutMillis: Long? = null,
    ): List<Library> =
        withContext(Dispatchers.IO) {
            val json =
                requestJson(
                    session,
                    "/api/catalog/libraries",
                    requestTimeoutMillis = requestTimeoutMillis,
                )
            jsonArray(json, "libraries")
                .mapNotNull { item ->
                    item
                        .optString("id")
                        .takeIf { it.isNotBlank() }
                        ?.let { id ->
                            Library(
                                id,
                                item.optString("name").ifBlank { "Library" },
                                when (item.optString("type")) {
                                    "tv_series" -> "tvshows"
                                    "movies" -> "movies"
                                    "collection" -> "boxsets"
                                    else -> null
                                },
                                item.optBoolean(
                                    "supportsLastAdded",
                                    item.optString("type") != "movies",
                                ),
                            )
                        }
                }
                .filter {
                    it.collectionType == "tvshows" ||
                        it.collectionType == "movies" ||
                        it.collectionType == "boxsets"
                }
        }

    suspend fun fetchLibraryData(
        session: AuthSession,
        library: Library,
        requestTimeoutMillis: Long? = null,
    ): LibraryData =
        withContext(Dispatchers.IO) {
            val path =
                "/api/catalog/items?libraryId=${android.net.Uri.encode(library.id)}&pageSize=18&sortBy=${if (library.supportsLastAdded) "lastAdded" else "added"}&sortOrder=descending"
            val items =
                catalogItems(
                    requestJson(session, path, requestTimeoutMillis = requestTimeoutMillis)
                )
            LibraryData(
                library,
                items
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        listOf(MediaRow(RowTitle.NewlyAdded, library.name, it))
                    }
                    .orEmpty(),
            )
        }

    suspend fun fetchHomeLibraryData(
        session: AuthSession,
        library: Library,
        requestTimeoutMillis: Long? = null,
    ): LibraryData =
        withContext(Dispatchers.IO) {
            val dedicated =
                try {
                    requestJson(
                        session,
                        "/api/catalog/home?section=library&libraryId=${android.net.Uri.encode(library.id)}",
                        requestTimeoutMillis = requestTimeoutMillis,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    null
                }
            val parsed = dedicated?.let { parseHomeLibraryData(it, library) }
            if (parsed != null && parsed.rows.isNotEmpty()) {
                return@withContext parsed
            }
            val aggregate =
                requestJson(
                    session,
                    "/api/catalog/home",
                    requestTimeoutMillis = requestTimeoutMillis,
                )
            parseHomeLibraryData(aggregate, library)
        }

    suspend fun fetchLibraryPage(
        session: AuthSession,
        library: Library,
        startIndex: Int,
        limit: Int,
        sort: LibrarySort,
    ): PagedLibrary =
        withContext(Dispatchers.IO) {
            val page = startIndex / limit + 1
            val sortBy = catalogSort(sort.sortBy)
            val json =
                requestJson(
                    session,
                    "/api/catalog/items?libraryId=${android.net.Uri.encode(library.id)}&page=$page&pageSize=$limit&sortBy=$sortBy&sortOrder=${sort.sortOrder.apiValue.lowercase()}",
                )
            val parsed = catalogItems(json)
            PagedLibrary(
                library = library,
                items = parsed,
                totalRecordCount = json.optInt("total", parsed.size),
            )
        }

    suspend fun fetchFavoritesPage(
        session: AuthSession,
        startIndex: Int,
        limit: Int,
        sort: FavoriteSort,
    ): PagedFavorites =
        withContext(Dispatchers.IO) {
            val page = startIndex / limit + 1
            val json =
                requestJson(
                    session,
                    "/api/catalog/favorites",
                    query =
                        mapOf(
                            "page" to page.toString(),
                            "pageSize" to limit.toString(),
                            "sortBy" to sort.sortBy.apiValue,
                            "sortOrder" to sort.sortOrder.apiValue,
                        ),
                )
            val parsed = catalogItems(json)
            PagedFavorites(
                items = parsed,
                totalRecordCount = json.optInt("total", startIndex + parsed.size),
            )
        }

    suspend fun search(session: AuthSession, query: String): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (query.trim().length < 2) return@withContext emptyList()
            catalogItems(
                requestJson(
                    session,
                    "/api/catalog/search?query=${android.net.Uri.encode(query.trim())}&pageSize=40",
                )
            )
        }

    internal fun searchQuery(userId: String, query: String): Map<String, String> =
        mapOf(
            "userId" to userId,
            "searchTerm" to query.trim(),
            "startIndex" to "0",
            "limit" to "40",
            "recursive" to "true",
            "includeItemTypes" to "Series,Movie",
            "fields" to ITEM_FIELDS,
            "enableImages" to "true",
            "imageTypeLimit" to "1",
            "enableImageTypes" to ITEM_IMAGE_TYPES,
            "enableUserData" to "true",
        )

    internal fun libraryItemsQuery(
        userId: String,
        library: Library,
        startIndex: Int,
        limit: Int,
        sort: LibrarySort,
    ): Map<String, String> =
        mapOf(
            "userId" to userId,
            "parentId" to library.id,
            "startIndex" to startIndex.toString(),
            "limit" to limit.toString(),
            "recursive" to "true",
            "includeItemTypes" to itemTypes(library.collectionType),
            "sortBy" to sort.sortBy.apiValue,
            "sortOrder" to sort.sortOrder.apiValue,
            "fields" to ITEM_FIELDS,
            "enableImages" to "true",
            "imageTypeLimit" to "1",
            "enableImageTypes" to ITEM_IMAGE_TYPES,
            "enableUserData" to "true",
            "enableTotalRecordCount" to "true",
        )

    suspend fun detail(
        session: AuthSession,
        itemId: String,
        requestedSeasonId: String? = null,
    ): DetailData =
        withContext(Dispatchers.IO) {
            try {
                val suffix =
                    requestedSeasonId
                        ?.let {
                            "?seasonId=${android.net.Uri.encode(it)}"
                        }
                        .orEmpty()
                val payload =
                    requestJson(
                        session,
                        "/api/catalog/items/${android.net.Uri.encode(itemId)}/detail$suffix",
                    )
                return@withContext DetailData(
                    item = catalogMediaItem(payload.getJSONObject("item")),
                    parentSeries = payload.optJSONObject("backgroundItem")?.let(::catalogMediaItem),
                    seasons = catalogItems(payload, "seasons"),
                    episodes = catalogItems(payload, "episodes"),
                    similar = catalogItems(payload, "similar"),
                    selectedSeasonId = payload.optString("selectedSeasonId").ifBlank { null },
                )
            } catch (error: CatalogException) {
                if (error.statusCode != 404 && error.statusCode != 405) throw error
            }
            val item = getItem(session, itemId)
            val parentSeries =
                if (item.type == "Episode" && !item.seriesId.isNullOrBlank()) {
                    getItem(session, item.seriesId)
                } else null
            val seriesId =
                when (item.type) {
                    "Series" -> item.id
                    "Episode" -> item.seriesId
                    else -> null
                }
            val seasons = if (seriesId != null) getSeasons(session, seriesId) else emptyList()
            val selectedSeason = selectInitialSeason(item, seasons, requestedSeasonId)
            val episodes =
                if (seriesId != null && selectedSeason != null) {
                    getEpisodes(session, seriesId, selectedSeason.id)
                } else emptyList()
            val similar = if (item.type == "Episode") emptyList() else getSimilar(session, item.id)
            DetailData(
                item = item,
                parentSeries = parentSeries,
                seasons = seasons,
                episodes = episodes,
                similar = similar,
                selectedSeasonId = selectedSeason?.id,
            )
        }

    suspend fun episodeNeighbors(session: AuthSession, item: MediaItem): EpisodeNeighbors =
        withContext(Dispatchers.IO) {
            val seriesId = item.seriesId ?: return@withContext EpisodeNeighbors()
            val seasons = getSeasons(session, seriesId)
            val seasonNumber = item.parentIndexNumber ?: return@withContext EpisodeNeighbors()
            val orderedSeasons =
                seasons.filter { it.indexNumber != null }.sortedBy { it.indexNumber }
            val currentIndex = orderedSeasons.indexOfFirst { it.indexNumber == seasonNumber }
            if (currentIndex < 0) return@withContext EpisodeNeighbors()
            val relevant =
                listOfNotNull(
                    orderedSeasons.getOrNull(currentIndex - 1),
                    orderedSeasons[currentIndex],
                    orderedSeasons.getOrNull(currentIndex + 1),
                )
            val cachedEpisodes = relevant.associate { season ->
                season.id to getEpisodes(session, seriesId, season.id)
            }
            resolveEpisodeNeighbors(item, orderedSeasons) { season ->
                cachedEpisodes[season.id].orEmpty()
            }
        }

    internal fun detailItemQuery(userId: String): Map<String, String> =
        mapOf(
            "userId" to userId,
            "fields" to ITEM_FIELDS,
            "enableImages" to "true",
            "imageTypeLimit" to "1",
            "enableImageTypes" to ITEM_IMAGE_TYPES,
            "enableUserData" to "true",
        )

    suspend fun setFavorite(session: AuthSession, itemId: String, favorite: Boolean) =
        withContext(Dispatchers.IO) {
            requestJson(
                session,
                "/api/catalog/items/$itemId/state",
                method = "PATCH",
                body = JSONObject().put("favorite", favorite).toString(),
            )
        }

    suspend fun setPlayed(session: AuthSession, itemId: String, played: Boolean) =
        withContext(Dispatchers.IO) {
            requestJson(
                session,
                "/api/catalog/items/$itemId/state",
                method = "PATCH",
                body = JSONObject().put("played", played).toString(),
            )
        }

    suspend fun setFollowing(session: AuthSession, itemId: String, following: Boolean) =
        withContext(Dispatchers.IO) {
            requestJson(
                session,
                "/api/catalog/items/$itemId/state",
                method = "PATCH",
                body = JSONObject().put("following", following).toString(),
            )
        }

    suspend fun notifications(
        session: AuthSession,
        limit: Int = 50,
        cursor: String? = null,
    ): NotificationPage =
        withContext(Dispatchers.IO) {
            val query = buildMap {
                put("limit", limit.coerceIn(1, 100).toString())
                cursor?.takeIf(String::isNotBlank)?.let { put("cursor", it) }
            }
            parseNotificationPage(requestJson(session, "/api/notifications", query = query))
        }

    suspend fun notificationSummary(session: AuthSession): Int =
        withContext(Dispatchers.IO) {
            requestJson(session, "/api/notifications/summary").optInt("unreadCount", 0)
        }

    suspend fun setNotificationRead(
        session: AuthSession,
        notificationId: String,
        read: Boolean,
    ) =
        withContext(Dispatchers.IO) {
            requestJson(
                session,
                "/api/notifications/$notificationId",
                method = "PATCH",
                body = JSONObject().put("read", read).toString(),
            )
        }

    suspend fun markAllNotificationsRead(session: AuthSession) =
        withContext(Dispatchers.IO) {
            requestJson(session, "/api/notifications/read-all", method = "POST")
        }

    private suspend fun getItem(session: AuthSession, itemId: String): MediaItem =
        catalogMediaItem(requestJson(session, "/api/catalog/items/$itemId"))

    private suspend fun getChildren(
        session: AuthSession,
        parent: MediaItem,
        view: String? = null,
    ): List<MediaItem> {
        val libraryId = parent.libraryId ?: return emptyList()
        val viewSuffix = view?.let { "&view=${android.net.Uri.encode(it)}" }.orEmpty()
        val path =
            "/api/catalog/items?libraryId=${android.net.Uri.encode(libraryId)}&parentId=${android.net.Uri.encode(parent.id)}&pageSize=100$viewSuffix"
        return catalogItems(requestJson(session, path))
    }

    private suspend fun getSeasons(session: AuthSession, seriesId: String): List<MediaItem> =
        getChildren(session, getItem(session, seriesId))

    private suspend fun getEpisodes(
        session: AuthSession,
        seriesId: String,
        seasonId: String,
    ): List<MediaItem> {
        @Suppress("UNUSED_VARIABLE") val ignoredSeriesId = seriesId
        return getChildren(session, getItem(session, seasonId), view = "full")
            .sortedWith(compareBy(nullsLast()) { it.indexNumber })
    }

    private suspend fun getSimilar(session: AuthSession, itemId: String): List<MediaItem> =
        catalogItems(requestJson(session, "/api/catalog/items/$itemId/similar"))

    private suspend fun requestJson(
        session: AuthSession,
        path: String,
        query: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: String? = null,
        requestTimeoutMillis: Long? = null,
    ): JSONObject =
        requestJson(
            session.serverUrl,
            path,
            query,
            session.token,
            method,
            body,
            requestTimeoutMillis,
        )

    private suspend fun requestJson(
        server: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        token: String?,
        method: String = "GET",
        body: String? = null,
        requestTimeoutMillis: Long? = null,
    ): JSONObject = suspendCancellableCoroutine { continuation ->
        val urlBuilder = "$server$path".toHttpUrl().newBuilder()
        query.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
        val request =
            Request.Builder()
                .url(urlBuilder.build())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .apply {
                    token?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
                }
                .method(
                    method,
                    if (method == "GET") null else (body ?: "{}").toRequestBody(JSON_MEDIA_TYPE),
                )
                .build()
        val call = httpClient.newCall(request)
        requestTimeoutMillis?.let {
            call.timeout().timeout(it, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isActive) return
                        if (!it.isSuccessful) {
                            continuation.resumeWithException(
                                CatalogException(
                                    it.code,
                                    "ZenStream request failed with ${it.code}",
                                )
                            )
                            return
                        }
                        runCatching {
                                JSONObject(it.body.string().ifBlank { "{}" })
                            }
                            .onSuccess(continuation::resume)
                            .onFailure(continuation::resumeWithException)
                    }
                }
            }
        )
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val ITEM_FIELDS =
            "Overview,Genres,CommunityRating,ProductionYear,PremiereDate,People,Studios,Chapters"
        private const val ITEM_IMAGE_TYPES = "Primary,Backdrop,Logo,Banner"
        internal const val HOME_REQUEST_TIMEOUT_MILLIS = 30_000L
        private const val PLAYBACK_READY_TIMEOUT_MILLIS = 15_000L
        private const val PLAYBACK_READY_INTERVAL_MILLIS = 350L
        private const val PLAYBACK_TAG = "ZenStreamPlayback"

        fun authorizationHeader(token: String) = "Bearer $token"
    }
}

private fun redactPlaybackUrl(value: String): String =
    value.replace(Regex("(?i)([?&]access=)[^&\\s\\\"']+"), "$1<redacted>")

internal fun resolveEpisodeNeighbors(
    item: MediaItem,
    seasons: List<MediaItem>,
    episodesForSeason: (MediaItem) -> List<MediaItem>,
): EpisodeNeighbors {
    val seasonNumber = item.parentIndexNumber ?: return EpisodeNeighbors()
    val episodeNumber = item.indexNumber ?: return EpisodeNeighbors()
    if (item.type != "Episode" || item.seriesId.isNullOrBlank()) return EpisodeNeighbors()
    val orderedSeasons = seasons.filter { it.indexNumber != null }.sortedBy { it.indexNumber }
    val currentSeason =
        orderedSeasons.firstOrNull { it.indexNumber == seasonNumber } ?: return EpisodeNeighbors()
    val currentEpisodes = episodesForSeason(currentSeason)
    val previousInSeason =
        currentEpisodes
            .filter { (it.indexNumber ?: Int.MIN_VALUE) < episodeNumber }
            .maxByOrNull { it.indexNumber ?: Int.MIN_VALUE }
    val nextInSeason =
        currentEpisodes
            .filter { (it.indexNumber ?: Int.MAX_VALUE) > episodeNumber }
            .minByOrNull { it.indexNumber ?: Int.MAX_VALUE }
    val currentSeasonPosition = orderedSeasons.indexOf(currentSeason)
    val previous =
        previousInSeason
            ?: orderedSeasons
                .take(currentSeasonPosition)
                .lastOrNull()
                ?.let(episodesForSeason)
                ?.maxByOrNull { it.indexNumber ?: Int.MIN_VALUE }
    val next =
        nextInSeason
            ?: orderedSeasons
                .drop(currentSeasonPosition + 1)
                .firstOrNull()
                ?.let(episodesForSeason)
                ?.minByOrNull { it.indexNumber ?: Int.MAX_VALUE }
    return EpisodeNeighbors(previous, next)
}

private fun catalogSort(value: LibrarySortBy): String = value.apiValue

private fun itemTypes(collectionType: String?, newlyAdded: Boolean = false): String {
    if (newlyAdded && collectionType == "tvshows") return "Episode"
    return when (collectionType) {
        "tvshows" -> "Series"
        "movies" -> "Movie"
        "boxsets" -> "BoxSet"
        else -> "Series,Movie"
    }
}

private fun jsonArray(root: JSONObject, key: String): List<JSONObject> =
    root
        .optJSONArray(key)
        ?.let { array ->
            List(array.length()) { array.optJSONObject(it) ?: JSONObject() }
        }
        .orEmpty()

internal fun parseNotificationPage(root: JSONObject): NotificationPage {
    val items =
        jsonArray(root, "items").mapNotNull { item ->
            val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            NotificationItem(
                id = id,
                kind = item.optString("kind"),
                title = item.optString("title").ifBlank { "ZenStream" },
                subtitle = item.optNullableString("subtitle"),
                itemId = item.optNullableString("itemId"),
                seriesId = item.optNullableString("seriesId"),
                seasonNumber = item.optIntOrNull("seasonNumber"),
                episodeNumber = item.optIntOrNull("episodeNumber"),
                createdAt = item.optString("createdAt"),
                readAt = item.optNullableString("readAt"),
                navigationTarget = item.optNullableString("navigationTarget"),
            )
        }
    return NotificationPage(
        items = items,
        unreadCount = root.optInt("unreadCount", 0).coerceAtLeast(0),
        nextCursor = root.optNullableString("nextCursor"),
    )
}

internal fun catalogItems(root: JSONObject, key: String = "items"): List<MediaItem> =
    jsonArray(root, key).map(::catalogMediaItem).distinctBy { it.id }

internal fun parseHomeData(payload: JSONObject): HomeData {
    val globalRows =
        listOfNotNull(
            catalogItems(payload, "continueWatching")
                .takeIf { it.isNotEmpty() }
                ?.let { MediaRow(RowTitle.ContinueWatching, items = it, wide = true) },
            catalogItems(payload, "nextUp")
                .takeIf { it.isNotEmpty() }
                ?.let { MediaRow(RowTitle.NextUp, items = it, wide = true) },
        )
    val libraryRows =
        jsonArray(payload, "libraryRows").mapNotNull { row ->
            val title =
                when (row.optString("titleKey")) {
                    "newlyAddedOn" -> RowTitle.NewlyAdded
                    "topRated" -> RowTitle.TopRated
                    else -> return@mapNotNull null
                }
            val items = catalogItems(row)
            val libraryName =
                row.optString("libraryName").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            items
                .takeIf { it.isNotEmpty() }
                ?.let {
                    MediaRow(
                        title,
                        libraryName,
                        it,
                        stackEpisodes = row.optBoolean("stackEpisodes", false),
                    )
                }
        }
    return HomeData(
        featured =
            catalogItems(payload, "latestItems")
                .filter { it.backdropImageTags.isNotEmpty() }
                .take(5),
        rows = orderedHomeRows(globalRows + parseDerivedHomeData(payload).rows() + libraryRows),
    )
}

internal fun parseHomeLibraryData(payload: JSONObject, library: Library): LibraryData {
    fun belongsToLibrary(row: JSONObject): Boolean {
        val id = row.optString("libraryId")
        val name = row.optString("libraryName")
        return (id.isBlank() && name.isBlank()) || id == library.id || name == library.name
    }
    val canonicalRows =
        jsonArray(payload, "libraryRows")
            .filter(::belongsToLibrary)
            .mapNotNull { row ->
                val title =
                    when (row.optString("titleKey")) {
                        "newlyAddedOn" -> RowTitle.NewlyAdded
                        "topRated" -> RowTitle.TopRated
                        else -> return@mapNotNull null
                    }
                catalogItems(row)
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        MediaRow(
                            title,
                            library.name,
                            it,
                            stackEpisodes = row.optBoolean("stackEpisodes", false),
                        )
                    }
            }
            .sortedBy { if (it.title == RowTitle.NewlyAdded) 0 else 1 }
    if (canonicalRows.isNotEmpty()) return LibraryData(library, canonicalRows)

    val legacyRow =
        jsonArray(payload, "newlyAdded").firstOrNull(::belongsToLibrary)
            ?: return LibraryData(library, emptyList())
    val items = catalogItems(legacyRow)
    return LibraryData(
        library,
        items
            .takeIf { it.isNotEmpty() }
            ?.let {
                listOf(
                    MediaRow(
                        RowTitle.NewlyAdded,
                        library.name,
                        it,
                        stackEpisodes = legacyRow.optBoolean("stackEpisodes", false),
                    )
                )
            }
            .orEmpty(),
    )
}

internal fun parseDerivedHomeData(payload: JSONObject): DerivedHomeData =
    DerivedHomeData(
        myList = catalogItems(payload, "myList"),
        recentlyPlayed = catalogItems(payload, "recentlyPlayed"),
        genreRows =
            jsonArray(payload, "genreRows").mapNotNull { row ->
                val genre =
                    row.optString("genre").trim().takeIf { it.isNotEmpty() }
                        ?: return@mapNotNull null
                val items = catalogItems(row)
                items
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        MediaRow(
                            RowTitle.Genre,
                            items = it,
                            label = genre,
                            key = "genre:${genre.lowercase()}",
                        )
                    }
            },
    )

internal fun catalogMediaItem(item: JSONObject): MediaItem {
    val metadata = item.optJSONObject("metadata") ?: JSONObject()
    val state = item.optJSONObject("userState") ?: JSONObject()
    val images = metadata.optJSONObject("images") ?: JSONObject()
    fun image(category: String) = images.optJSONObject(category)?.optNullableString("url")
    fun imageBlurHash(category: String) =
        images.optJSONObject(category)?.optNullableString("blurHash")
    val type =
        when (item.optString("type")) {
            "movie" -> "Movie"
            "series" -> "Series"
            "season" -> "Season"
            "episode" -> "Episode"
            "collection" -> "BoxSet"
            else -> item.optString("type")
        }
    val credits = metadata.optJSONObject("credits")
    val people =
        listOf("cast", "crew").flatMap { creditType ->
            val array = credits?.optJSONArray(creditType) ?: return@flatMap emptyList()
            List(array.length()) { index ->
                    val person = array.optJSONObject(index) ?: JSONObject()
                    val image = person.optJSONObject("image")
                    MediaPerson(
                        name = person.optString("name"),
                        role =
                            if (creditType == "cast") {
                                person.optNullableString("character")
                            } else {
                                person.optNullableString("job")
                            },
                        type = person.optNullableString("department"),
                        primaryImageTag = image?.optNullableString("url"),
                        id = person.optNullableString("id"),
                        creditType = creditType,
                        imageBlurHash = image?.optNullableString("blurHash"),
                    )
                }
                .filter { it.name.isNotBlank() }
        }
    val genres = metadata.optJSONArray("genres") ?: metadata.optJSONArray("tags")
    val studios =
        metadata
            .optJSONArray("studios")
            ?.let { array ->
                List(array.length()) { index ->
                        when (val value = array.opt(index)) {
                            is JSONObject -> value.optString("name")
                            else -> value?.toString().orEmpty()
                        }
                    }
                    .filter(String::isNotBlank)
            }
            .orEmpty()
    return MediaItem(
        id = item.optString("id"),
        name =
            metadata.optString("title").ifBlank { item.optString("name").ifBlank { "Untitled" } },
        type = type,
        seriesName = item.optString("seriesName").ifBlank { null },
        seriesId = item.optString("seriesId").ifBlank { null },
        seasonId = item.optString("seasonId").ifBlank { null },
        parentId = item.optString("parentId").ifBlank { null },
        libraryId = item.optString("libraryId").ifBlank { null },
        lastAddedAt = item.optString("lastAddedAt").ifBlank { null },
        parentIndexNumber = item.optIntOrNull("seasonNumber"),
        indexNumber =
            if (type == "Season") item.optIntOrNull("seasonNumber")
            else item.optIntOrNull("episodeNumber"),
        overview =
            metadata.optString("overview").ifBlank {
                metadata.optString("description").ifBlank { null }
            },
        premiereDate =
            metadata.optString("date").ifBlank {
                metadata.optString("releaseDate").ifBlank { null }
            },
        productionYear = metadata.optIntOrNull("year"),
        officialRating =
            metadata
                .optString("officialRating")
                .ifBlank { metadata.optString("certification") }
                .ifBlank { null },
        communityRating = metadata.optDoubleOrNull("communityRating"),
        genres =
            genres
                ?.let { array ->
                    List(array.length()) { array.optString(it) }.filter(String::isNotBlank)
                }
                .orEmpty(),
        studios = studios,
        people = people,
        recursiveItemCount =
            item.optIntOrNull("recursiveItemCount")
                ?: item.optIntOrNull("childCount")
                ?: metadata.optIntOrNull("recursiveItemCount")
                ?: metadata.optIntOrNull("childCount"),
        runtimeTicks =
            metadata.optDoubleOrNull("runtimeMinutes")?.let { (it * 60.0 * 10_000_000.0).toLong() },
        imageTags =
            buildMap {
                image("Primary")?.let { put("Primary", it) }
                image("Logo")?.let { put("Logo", it) }
                image("Banner")?.let { put("Banner", it) }
            },
        imageBlurHashes =
            buildMap {
                imageBlurHash("Primary")?.let { put("Primary", it) }
                imageBlurHash("Backdrop")?.let { put("Backdrop", it) }
                imageBlurHash("Banner")?.let { put("Banner", it) }
            },
        backdropImageTags = image("Backdrop")?.let(::listOf).orEmpty(),
        seriesPrimaryImageTag =
            item.optJSONObject("seriesPrimaryImage")?.optString("url")?.takeIf { it.isNotBlank() },
        seriesPrimaryImageBlurHash =
            item.optJSONObject("seriesPrimaryImage")?.optString("blurHash")?.takeIf {
                it.isNotBlank()
            },
        played = state.optBoolean("played", false),
        favorite = state.optBoolean("favorite", false),
        following =
            if (type == "Movie" || type == "Series") {
                state.optBoolean("following", false)
            } else null,
        unplayedItemCount = state.optIntOrNull("unplayedItemCount"),
        playedPercentage = state.optDoubleOrNull("playedPercentage"),
        playbackPositionTicks =
            state.optDoubleOrNull("positionSeconds")?.let { (it * 10_000_000.0).toLong() },
    )
}

internal fun selectInitialSeason(
    item: MediaItem,
    seasons: List<MediaItem>,
    requestedSeasonId: String? = null,
): MediaItem? =
    requestedSeasonId?.let { id -> seasons.find { it.id == id } }
        ?: item.seasonId?.let { id -> seasons.find { it.id == id } }
        ?: seasons.find { it.indexNumber == 1 }
        ?: seasons.firstOrNull()

class CatalogException(val statusCode: Int, message: String) : Exception(message)

private fun items(json: JSONObject): List<JSONObject> {
    val array = json.optJSONArray("Items") ?: return emptyList()
    return List(array.length()) { array.optJSONObject(it) ?: JSONObject() }
}

internal fun subtitleWebVttQuery(
    session: AuthSession,
    itemId: String,
    sourceId: String?,
): Map<String, String> = buildMap {
    put("sourceId", sourceId ?: itemId)
    put("format", "vtt")
    put("addVttTimeMap", "false")
    put("copyTimestamps", "false")
    // Keep cue timestamps on the item's absolute media timeline. The player
    // applies the playback source origin exactly once when selecting cues.
    put("startPositionTicks", "0")
    // The web client adds this ticket to subtitle requests. Without it the
    // gateway's upstream subtitle request can fail even though playback works.
    session.resourceTicket?.let { put("access", it) }
}

internal fun parseMediaSource(source: JSONObject): MediaSource {
    val streams =
        source.optJSONArray("streams")?.let { array ->
            List(array.length()) { index ->
                    val stream = array.optJSONObject(index) ?: JSONObject()
                    val codecType = stream.optString("codec_type").lowercase()
                    val type =
                        when (codecType) {
                            "video" -> "Video"
                            "audio" -> "Audio"
                            "subtitle" -> "Subtitle"
                            else -> stream.optString("type")
                        }
                    val tags = stream.optJSONObject("tags")
                    val disposition = stream.optJSONObject("disposition")
                    MediaStream(
                        index = stream.optInt("index", -1),
                        type = type,
                        displayTitle =
                            stream
                                .optString("displayTitle")
                                .ifBlank { tags?.optString("title").orEmpty() }
                                .ifBlank { null },
                        language =
                            stream
                                .optString("language")
                                .ifBlank { tags?.optString("language").orEmpty() }
                                .ifBlank { null },
                        isDefault =
                            stream.optBoolean("isDefault") ||
                                (disposition?.optInt("default", 0) ?: 0) == 1,
                        isLyrics =
                            stream.optString("kind") == "lyrics" ||
                                tags?.optString("handler_name") == "Lyrics" ||
                                tags?.optString("title") == "Lyrics",
                        codec = stream.optString("codec_name").ifBlank { null },
                        width = stream.optIntOrNull("width"),
                        height = stream.optIntOrNull("height"),
                        channels = stream.optIntOrNull("channels"),
                    )
                }
                .filter { it.index >= 0 }
        } ?: emptyList()
    return MediaSource(
        id = source.optString("id").ifBlank { null },
        url = source.optString("url").ifBlank { null },
        mediaStreams = streams,
        durationSeconds = source.optDoubleOrNull("durationSeconds"),
        container = source.optString("container").ifBlank { null },
        transcodingContainer = source.optString("transcodingContainer").ifBlank { null },
        bitrate = source.optIntOrNull("bitrate"),
    )
}

internal fun withNegotiatedPlaybackUrl(source: MediaSource, url: String?): MediaSource =
    source.copy(url = url?.trim()?.takeIf(String::isNotBlank) ?: source.url)

internal fun parseTrickplayManifest(value: JSONObject?, serverUrl: String): TrickplayManifest? {
    val manifest = value ?: return null
    val sheets = manifest.optJSONArray("sheets") ?: manifest.optJSONArray("sheetList")
    val parsedSheets =
        List(sheets?.length() ?: 0) { sheets?.optJSONObject(it) }
            .mapNotNull { sheet ->
                sheet ?: return@mapNotNull null
                val index = sheet.optIntAny("index", "sheetIndex") ?: return@mapNotNull null
                val frameCount = sheet.optIntAny("frameCount", "frames") ?: return@mapNotNull null
                val rawUrl = sheet.optStringAny("url", "sheetUrl") ?: return@mapNotNull null
                val url = resolveTrickplayUrl(serverUrl, rawUrl) ?: return@mapNotNull null
                TrickplaySheet(index, frameCount, url)
            }
    return TrickplayManifest(
        state = manifest.optStringAny("state") ?: return null,
        sourceId = manifest.optStringAny("sourceId", "source_id") ?: return null,
        frameWidth = manifest.optIntAny("frameWidth", "frame_width") ?: return null,
        frameHeight = manifest.optIntAny("frameHeight", "frame_height") ?: return null,
        intervalSeconds =
            manifest.optDoubleAny("intervalSeconds", "interval_seconds") ?: return null,
        columns = manifest.optIntAny("columns") ?: return null,
        rows = manifest.optIntAny("rows") ?: return null,
        frameCount =
            manifest.optIntAny("frameCount", "frame_count") ?: parsedSheets.sumOf { it.frameCount },
        sheets = parsedSheets,
    )
}

private fun JSONObject.optStringAny(vararg keys: String): String? =
    keys.asSequence().map { optString(it).trim() }.firstOrNull { it.isNotBlank() }

private fun JSONObject.optIntAny(vararg keys: String): Int? =
    keys.asSequence().mapNotNull { optIntOrNull(it) }.firstOrNull()

private fun JSONObject.optDoubleAny(vararg keys: String): Double? =
    keys.asSequence().mapNotNull { optDoubleOrNull(it) }.firstOrNull()

private fun resolveTrickplayUrl(serverUrl: String, value: String): String? =
    runCatching { value.toHttpUrl().toString() }.getOrNull()
        ?: value.takeIf { it.startsWith('/') }?.let { serverUrl.trimEnd('/') + it }

internal fun playbackMimeType(source: MediaSource, bitrate: Int = 0): String? {
    val negotiatedUrl = source.url
    val urlPath = negotiatedUrl?.substringBefore('?')?.substringBefore('#')?.lowercase()
    if (urlPath?.endsWith(".m3u8") == true || bitrate > 0) {
        // The canonical session URL explicitly identifies HLS.
        return "application/x-mpegURL"
    }

    return when (source.container?.lowercase()) {
        "mp4",
        "m4v",
        "mov" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts",
        "m2ts" -> "video/mp2t"
        "avi" -> "video/avi"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        else -> null
    }
}

fun playbackUrl(
    session: AuthSession,
    itemId: String,
    source: MediaSource,
    bitrate: Int = 0,
    startPositionSeconds: Double = 0.0,
): String {
    val negotiated = source.url
    if (negotiated != null) {
        val gateway = session.serverUrl.toHttpUrl()
        val resolved = gateway.resolve(negotiated)
        if (
            resolved != null &&
                resolved.scheme == gateway.scheme &&
                resolved.host == gateway.host &&
                resolved.port == gateway.port
        ) {
            return resolved.toString()
        }
    }
    error("Canonical playback response did not include a usable URL")
}

fun playbackStreamStartPositionSeconds(
    session: AuthSession,
    source: MediaSource,
    requestedStartSeconds: Double = 0.0,
    streamStartsAtRequestedPosition: Boolean = false,
): Double {
    return 0.0
}

fun playbackLocalPositionSeconds(
    absolutePositionSeconds: Double,
    streamOriginSeconds: Double,
): Double = absolutePositionSeconds.coerceAtLeast(0.0)

fun parseMediaItems(json: JSONObject): List<MediaItem> =
    items(json).mapNotNull { item ->
        val id = item.optString("Id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val userData = item.optJSONObject("UserData")
        val imageTags = buildMap {
            item.optJSONObject("ImageTags")?.keys()?.forEach { key ->
                item
                    .optJSONObject("ImageTags")
                    ?.optString(key)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put(key, it) }
            }
        }
        val backdropTags =
            item.optJSONArray("BackdropImageTags")?.let { array ->
                List(array.length()) { array.optString(it) }.filter(String::isNotBlank)
            } ?: emptyList()
        MediaItem(
            id = id,
            name = item.optString("Name").ifBlank { "Untitled" },
            type = item.optString("Type").ifBlank { null },
            seriesName = item.optString("SeriesName").ifBlank { null },
            seriesId = item.optString("SeriesId").ifBlank { null },
            seasonId = item.optString("SeasonId").ifBlank { null },
            parentId = item.optString("ParentId").ifBlank { null },
            parentIndexNumber = item.optIntOrNull("ParentIndexNumber"),
            indexNumber = item.optIntOrNull("IndexNumber"),
            overview = item.optString("Overview").ifBlank { null },
            premiereDate = item.optString("PremiereDate").ifBlank { null },
            productionYear = item.optIntOrNull("ProductionYear"),
            officialRating = item.optString("OfficialRating").ifBlank { null },
            communityRating = item.optDoubleOrNull("CommunityRating"),
            genres = stringArray(item, "Genres"),
            studios = objectNameArray(item, "Studios"),
            people = people(item),
            recursiveItemCount =
                item.optIntOrNull("RecursiveItemCount") ?: item.optIntOrNull("ChildCount"),
            runtimeTicks = item.optLongOrNull("RunTimeTicks"),
            imageTags = imageTags,
            backdropImageTags = backdropTags,
            seriesPrimaryImageTag = item.optString("SeriesPrimaryImageTag").ifBlank { null },
            played = userData?.optBoolean("Played") ?: false,
            favorite = userData?.optBoolean("IsFavorite") ?: false,
            following =
                if (
                    item.optString("Type").equals("Movie", ignoreCase = true) ||
                        item.optString("Type").equals("Series", ignoreCase = true)
                ) {
                    userData?.optBoolean("IsFollowing") ?: false
                } else null,
            unplayedItemCount = userData?.optIntOrNull("UnplayedItemCount"),
            playedPercentage = userData?.optDoubleOrNull("PlayedPercentage"),
            playbackPositionTicks = userData?.optLongOrNull("PlaybackPositionTicks"),
            chapters = parseChapters(item),
        )
    }

internal fun parseChapters(item: JSONObject): List<MediaChapter> =
    item.optJSONArray("Chapters")?.let { array ->
        List(array.length()) { index ->
                val chapter = array.optJSONObject(index) ?: JSONObject()
                MediaChapter(
                    startPositionTicks = chapter.optLong("StartPositionTicks", -1L),
                    name = chapter.optString("Name").ifBlank { null },
                )
            }
            .filter { it.startPositionTicks >= 0L }
            .sortedBy { it.startPositionTicks }
    } ?: emptyList()

private fun marker(type: PlaybackSegmentType, rawStart: Double, rawEnd: Double): PlaybackSegment? {
    val start = rawStart.toPlaybackSeconds()
    val end = rawEnd.toPlaybackSeconds()
    return if (start.isFinite() && end.isFinite() && end > start) {
        PlaybackSegment(type, start, end)
    } else null
}

private fun Double.toPlaybackSeconds(): Double =
    if (this > 1_000_000.0) this / 10_000_000.0 else this

private fun stringArray(item: JSONObject, key: String): List<String> =
    item.optJSONArray(key)?.let { array ->
        List(array.length()) { array.optString(it) }.filter(String::isNotBlank)
    } ?: emptyList()

private fun objectNameArray(item: JSONObject, key: String): List<String> =
    item.optJSONArray(key)?.let { array ->
        List(array.length()) { array.optJSONObject(it)?.optString("Name").orEmpty() }
            .filter(String::isNotBlank)
    } ?: emptyList()

private fun people(item: JSONObject): List<MediaPerson> =
    item.optJSONArray("People")?.let { array ->
        List(array.length()) { index ->
                val person = array.optJSONObject(index) ?: JSONObject()
                person
                    .optString("Name")
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        MediaPerson(
                            name = it,
                            role = person.optNullableString("Role"),
                            type = person.optNullableString("Type"),
                            primaryImageTag = person.optNullableString("PrimaryImageTag"),
                        )
                    }
            }
            .filterNotNull()
    } ?: emptyList()

internal fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
}

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null
