package com.zenstream.zenstreammobile.data

import android.os.Build
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.DetailData
import com.zenstream.zenstreammobile.model.HomeData
import com.zenstream.zenstreammobile.model.Library
import com.zenstream.zenstreammobile.model.LibraryData
import com.zenstream.zenstreammobile.model.MediaChapter
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.MediaPerson
import com.zenstream.zenstreammobile.model.MediaRow
import com.zenstream.zenstreammobile.model.MediaSource
import com.zenstream.zenstreammobile.model.MediaStream
import com.zenstream.zenstreammobile.model.PlaybackData
import com.zenstream.zenstreammobile.model.PlaybackOptions
import com.zenstream.zenstreammobile.model.PlaybackSegment
import com.zenstream.zenstreammobile.model.PlaybackSegmentType
import com.zenstream.zenstreammobile.model.RowTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

class JellyfinApi(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val deviceId: String = UUID.randomUUID().toString(),
) {
    suspend fun authenticate(serverUrl: String, username: String, password: String): AuthSession =
        withContext(Dispatchers.IO) {
            val server = normalizeServerUrl(serverUrl)
            val body = JSONObject()
                .put("Username", username.trim())
                .put("Pw", password)
                .toString()
            val json = requestJson(
                server = server,
                path = "/Users/AuthenticateByName",
                token = null,
                method = "POST",
                body = body,
            )
            val user = json.optJSONObject("User")
            val token = json.optString("AccessToken").takeIf { it.isNotBlank() }
                ?: error("Server did not return an access token")
            val userId = user?.optString("Id").orEmpty().takeIf { it.isNotBlank() }
                ?: error("Server did not return a user ID")
            AuthSession(
                server,
                token,
                userId,
                user?.optString("Name").orEmpty().ifBlank { username.trim() })
        }

    suspend fun playback(
        session: AuthSession,
        itemId: String,
        options: PlaybackOptions = PlaybackOptions(),
    ): PlaybackData = withContext(Dispatchers.IO) {
        val item = getItem(session, itemId)
        val json = requestJson(
            session,
            "/Items/$itemId/PlaybackInfo",
            playbackQuery(session, options),
            method = "POST",
            body = playbackBody(session, options),
        )
        val sourceJson = json.optJSONArray("MediaSources")?.optJSONObject(0)
            ?: error("Jellyfin did not return a media source")
        val source = parseMediaSource(sourceJson)
        val playSessionId = playbackSessionIdFromInfo(
            json.optString("PlaySessionId"),
            session,
            source,
        )
        PlaybackData(
            item = item,
            source = source,
            audio = source.mediaStreams.filter { it.type == "Audio" },
            subtitles = source.mediaStreams.filter { it.type == "Subtitle" },
            segments = getPlaybackSegments(session, itemId, item),
            playSessionId = playSessionId,
        )
    }

    private fun getPlaybackSegments(
        session: AuthSession,
        itemId: String,
        item: MediaItem,
    ): List<PlaybackSegment> {
        val providerSegments = playbackMarkerPaths(android.net.Uri.encode(itemId)).asSequence()
            .mapNotNull { path ->
                runCatching { parsePlaybackMarkers(requestMarkerPayload(session, path)) }.getOrNull()
            }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        return mergePlaybackSegments(providerSegments, chapterPlaybackSegments(item))
    }

    private fun requestMarkerPayload(session: AuthSession, path: String): Any? {
        val request = Request.Builder()
            .url("${session.serverUrl}$path".toHttpUrl())
            .header("Accept", "application/json")
            .header("Authorization", authorizationHeader(session.token, deviceId))
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw JellyfinException(
                response.code,
                "Jellyfin marker request failed with ${response.code}",
            )
            val body = response.body?.string().orEmpty().ifBlank { return null }
            return JSONTokener(body).nextValue()
        }
    }

    suspend fun subtitleWebVtt(
        session: AuthSession,
        itemId: String,
        sourceId: String?,
        streamIndex: Int,
    ): String = withContext(Dispatchers.IO) {
        val params = subtitleWebVttQuery(session, itemId, sourceId)
        val builder = "${session.serverUrl}/Videos/$itemId/${sourceId ?: itemId}/Subtitles/$streamIndex/Stream.vtt"
            .toHttpUrl()
            .newBuilder()
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        val request = Request.Builder()
            .url(builder.build())
            .header("Accept", "text/vtt")
            .header("Authorization", authorizationHeader(session.token, deviceId))
            .get()
            .build()
        httpClient.newCall(request).execute().use {
            if (!it.isSuccessful) throw JellyfinException(it.code, "Subtitle request failed with ${it.code}")
            it.body?.string().orEmpty()
        }
    }

    suspend fun reportPlayback(
        session: AuthSession,
        itemId: String,
        positionSeconds: Double,
        isPaused: Boolean,
        playSessionId: String?,
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("ItemId", itemId)
            .put("PositionTicks", (positionSeconds.coerceAtLeast(0.0) * 10_000_000.0).toLong())
            .put("IsPaused", isPaused)
            .put("PlayMethod", "DirectStream")
        playSessionId?.takeIf { it.isNotBlank() }?.let { body.put("PlaySessionId", it) }
        requestJson(
            session,
            "/Sessions/Playing/Progress",
            method = "POST",
            body = body.toString(),
        )
    }

    internal fun playbackQuery(session: AuthSession, options: PlaybackOptions): Map<String, String> =
        playbackParameters(session, options).mapValues { it.value.toString() }

    internal fun playbackBody(session: AuthSession, options: PlaybackOptions): String =
        JSONObject()
            .put("UserId", session.userId)
            .put("MaxStreamingBitrate", options.maxStreamingBitrate)
            .put("StartTimeTicks", options.startTimeTicks)
            .put("MediaSourceId", options.mediaSourceId)
            .put("AudioStreamIndex", options.audioStreamIndex)
            .put("SubtitleStreamIndex", -1)
            .put("EnableDirectPlay", !options.forceTranscoding)
            .put("EnableDirectStream", !options.forceTranscoding)
            .put("AllowVideoStreamCopy", !options.forceTranscoding)
            .put("AllowAudioStreamCopy", !options.forceTranscoding)
            .put("EnableTranscoding", !options.directPlayOnly)
            .put("DeviceProfile", deviceProfile(options))
            .toString()

    private fun playbackParameters(session: AuthSession, options: PlaybackOptions): Map<String, Any?> = mapOf(
        "userId" to session.userId,
        "startTimeTicks" to options.startTimeTicks,
        "maxStreamingBitrate" to options.maxStreamingBitrate,
        "mediaSourceId" to options.mediaSourceId,
        "audioStreamIndex" to options.audioStreamIndex,
        "subtitleStreamIndex" to -1,
        "enableDirectPlay" to !options.forceTranscoding,
        "enableDirectStream" to !options.forceTranscoding,
        "allowVideoStreamCopy" to !options.forceTranscoding,
        "allowAudioStreamCopy" to !options.forceTranscoding,
        "enableTranscoding" to !options.directPlayOnly,
    ).filterValues { it != null }

    private fun deviceProfile(options: PlaybackOptions): JSONObject {
        val directPlay = org.json.JSONArray()
            .put(JSONObject().put("Type", "Video").put("VideoCodec", "h264,h265,vp9,av1").put("AudioCodec", "aac,ac3,opus,vorbis,mp3").put("Container", "mp4,mkv,webm"))
        val subtitles = org.json.JSONArray()
            .put(JSONObject().put("Format", "vtt").put("Method", "External"))
        val transcoding = org.json.JSONArray()
            .put(JSONObject().put("Type", "Video").put("Context", "Streaming").put("Protocol", "hls").put("Container", "ts").put("VideoCodec", "h264").put("AudioCodec", "aac").put("MaxAudioChannels", "2").put("MinSegments", 1).put("BreakOnNonKeyFrames", true))
        return JSONObject()
            .put("Name", "ZenStream Android")
            .put("MaxStreamingBitrate", options.maxStreamingBitrate)
            .put("DirectPlayProfiles", directPlay)
            .put("SubtitleProfiles", subtitles)
            .put("TranscodingProfiles", if (options.directPlayOnly) org.json.JSONArray() else transcoding)
    }

    suspend fun fetchHome(session: AuthSession): HomeData = coroutineScope {
        val latest = async {
            fetchHomeFeatured(session)
        }
        val resume = async {
            fetchHomeContinueWatching(session)
        }
        val nextUp = async {
            fetchHomeNextUp(session)
        }
        val libraries = async { getLibraries(session, HOME_REQUEST_TIMEOUT_MILLIS) }
        val libraryData = libraries.await().flatMap { library ->
            if (library.collectionType != "tvshows" && library.collectionType != "movies") emptyList()
            else listOf(async { fetchLibraryData(session, library, HOME_REQUEST_TIMEOUT_MILLIS) })
        }.awaitAll()
        val latestItems = latest.await()
        val rows = buildList {
            add(MediaRow(RowTitle.ContinueWatching, items = resume.await(), wide = true))
            add(MediaRow(RowTitle.NextUp, items = nextUp.await(), wide = true))
            libraryData.flatMapTo(this) { it.rows }
        }.filter { it.items.isNotEmpty() }
        HomeData(
            featured = latestItems.filter { it.backdropImageTags.isNotEmpty() }.take(5),
            rows = rows
        )
    }

    suspend fun fetchHomeFeatured(session: AuthSession): List<MediaItem> =
        getItems(
            session,
            "/Items",
            latestItemsQuery(session.userId),
            HOME_REQUEST_TIMEOUT_MILLIS,
        ).filter { it.backdropImageTags.isNotEmpty() }.take(5)

    suspend fun fetchHomeContinueWatching(session: AuthSession): List<MediaItem> =
        getItems(
            session,
            "/UserItems/Resume",
            mapOf(
                "userId" to session.userId,
                "limit" to "18",
                "includeItemTypes" to "Episode,Movie"
            ),
            HOME_REQUEST_TIMEOUT_MILLIS,
        )

    suspend fun fetchHomeNextUp(session: AuthSession): List<MediaItem> =
        getItems(
            session,
            "/Shows/NextUp",
            mapOf("userId" to session.userId, "limit" to "18", "disableFirstEpisode" to "true"),
            HOME_REQUEST_TIMEOUT_MILLIS,
        )

    internal fun latestItemsQuery(userId: String): Map<String, String> = mapOf(
        "userId" to userId,
        "startIndex" to "0",
        "limit" to "25",
        "recursive" to "true",
        "includeItemTypes" to "Series,Movie",
        "sortBy" to "DateCreated",
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
    ): List<Library> = withContext(Dispatchers.IO) {
        val json = requestJson(
            session,
            "/Users/${session.userId}/Views",
            mapOf("fields" to "CollectionType"),
            requestTimeoutMillis = requestTimeoutMillis,
        )
        items(json).mapNotNull { item ->
            item.optString("Id").takeIf { it.isNotBlank() }?.let { id ->
                Library(
                    id,
                    item.optString("Name").ifBlank { "Library" },
                    item.optString("CollectionType").ifBlank { null })
            }
        }
            .filter { it.collectionType == "tvshows" || it.collectionType == "movies" || it.collectionType == "boxsets" }
    }

    suspend fun fetchLibraryData(
        session: AuthSession,
        library: Library,
        requestTimeoutMillis: Long? = null,
    ): LibraryData =
        coroutineScope {
            val common = mapOf(
                "userId" to session.userId,
                "parentId" to library.id,
                "recursive" to "true",
                "limit" to "18"
            )
            val recent = async {
                getItems(
                    session,
                    "/Items",
                    common + newlyAddedItemsQuery(library.collectionType),
                    requestTimeoutMillis,
                )
            }
            val topRated = async {
                getItems(
                    session,
                    "/Items",
                    common + mapOf(
                        "sortBy" to "CommunityRating",
                        "sortOrder" to "Descending",
                        "includeItemTypes" to itemTypes(library.collectionType)
                    ),
                    requestTimeoutMillis,
                )
            }
            val newReleases = async {
                getItems(
                    session,
                    "/Items",
                    common + mapOf(
                        "sortBy" to "PremiereDate",
                        "sortOrder" to "Descending",
                        "includeItemTypes" to itemTypes(library.collectionType)
                    ),
                    requestTimeoutMillis,
                )
            }
            LibraryData(
                library, listOf(
                    MediaRow(RowTitle.NewlyAdded, library.name, recent.await()),
                    MediaRow(RowTitle.TopRated, library.name, topRated.await()),
                    MediaRow(RowTitle.NewReleases, library.name, newReleases.await()),
                ).filter { it.items.isNotEmpty() })
        }

    suspend fun search(session: AuthSession, query: String): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (query.trim().length < 2) return@withContext emptyList()
            getItems(
                session, "/Items", mapOf(
                    "userId" to session.userId,
                    "searchTerm" to query.trim(),
                    "recursive" to "true",
                    "limit" to "40",
                    "includeItemTypes" to "Series,Movie",
                    "sortBy" to "SortName",
                    "sortOrder" to "Ascending",
                )
            )
        }

    suspend fun detail(
        session: AuthSession,
        itemId: String,
        requestedSeasonId: String? = null,
    ): DetailData = withContext(Dispatchers.IO) {
        val item = getItem(session, itemId)
        val parentSeries = if (item.type == "Episode" && !item.seriesId.isNullOrBlank()) {
            getItem(session, item.seriesId)
        } else null
        val seriesId = when (item.type) {
            "Series" -> item.id
            "Episode" -> item.seriesId
            else -> null
        }
        val seasons = if (seriesId != null) getSeasons(session, seriesId) else emptyList()
        val selectedSeason = selectInitialSeason(item, seasons, requestedSeasonId)
        val episodes = if (seriesId != null && selectedSeason != null) {
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

    internal fun detailItemQuery(userId: String): Map<String, String> = mapOf(
        "userId" to userId,
        "fields" to ITEM_FIELDS,
        "enableImages" to "true",
        "imageTypeLimit" to "1",
        "enableImageTypes" to ITEM_IMAGE_TYPES,
        "enableUserData" to "true",
    )

    private fun playbackItemQuery(userId: String): Map<String, String> =
        detailItemQuery(userId) + ("fields" to "$ITEM_FIELDS,Chapters")

    internal fun newlyAddedItemsQuery(collectionType: String?): Map<String, String> = mapOf(
        "sortBy" to "DateCreated",
        "sortOrder" to "Descending",
        "includeItemTypes" to itemTypes(collectionType, newlyAdded = true),
    )

    suspend fun setFavorite(session: AuthSession, itemId: String, favorite: Boolean) =
        withContext(Dispatchers.IO) {
            requestJson(
                session,
                "/UserFavoriteItems/$itemId",
                method = if (favorite) "POST" else "DELETE",
            )
        }

    suspend fun setPlayed(session: AuthSession, itemId: String, played: Boolean) =
        withContext(Dispatchers.IO) {
            requestJson(
                session,
                "/UserPlayedItems/$itemId",
                method = if (played) "POST" else "DELETE",
            )
        }

    private suspend fun getItem(session: AuthSession, itemId: String): MediaItem =
        withContext(Dispatchers.IO) {
            val json = requestJson(session, "/Items/$itemId", playbackItemQuery(session.userId))
            parseMediaItems(JSONObject().put("Items", org.json.JSONArray().put(json))).first()
        }

    private suspend fun getSeasons(session: AuthSession, seriesId: String): List<MediaItem> =
        withContext(Dispatchers.IO) {
            parseMediaItems(
                requestJson(
                    session,
                    "/Shows/$seriesId/Seasons",
                    detailItemQuery(session.userId),
                )
            )
        }

    private suspend fun getEpisodes(
        session: AuthSession,
        seriesId: String,
        seasonId: String,
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        parseMediaItems(
            requestJson(
                session,
                "/Shows/$seriesId/Episodes",
                detailItemQuery(session.userId) + mapOf("seasonId" to seasonId),
            )
        ).sortedWith(compareBy(nullsLast()) { it.indexNumber })
    }

    private suspend fun getSimilar(session: AuthSession, itemId: String): List<MediaItem> =
        withContext(Dispatchers.IO) {
            parseMediaItems(
                requestJson(
                    session,
                    "/Items/$itemId/Similar",
                    detailItemQuery(session.userId) + mapOf("limit" to "8"),
                )
            )
        }

    private suspend fun getItems(
        session: AuthSession,
        path: String,
        query: Map<String, String>,
        requestTimeoutMillis: Long? = null,
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        parseMediaItems(requestJson(session, path, query, requestTimeoutMillis = requestTimeoutMillis))
    }

    private fun requestJson(
        session: AuthSession,
        path: String,
        query: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: String? = null,
        requestTimeoutMillis: Long? = null,
    ): JSONObject = requestJson(
        session.serverUrl,
        path,
        query,
        session.token,
        method,
        body,
        requestTimeoutMillis,
    )

    private fun requestJson(
        server: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        token: String?,
        method: String = "GET",
        body: String? = null,
        requestTimeoutMillis: Long? = null,
    ): JSONObject {
        val urlBuilder = "$server${path}".toHttpUrl().newBuilder()
        query.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
        val requestBuilder = Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Authorization", authorizationHeader(token, deviceId))
        requestBuilder.method(
            method,
            if (method == "GET") null else (body ?: "{}").toRequestBody(JSON_MEDIA_TYPE),
        )
        val call = httpClient.newCall(requestBuilder.build())
        requestTimeoutMillis?.let { call.timeout().timeout(it, java.util.concurrent.TimeUnit.MILLISECONDS) }
        val response = call.execute()
        response.use {
            if (!it.isSuccessful) throw JellyfinException(
                it.code,
                "Jellyfin request failed with ${it.code}"
            )
            return JSONObject(it.body?.string().orEmpty().ifBlank { "{}" })
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val ITEM_FIELDS =
            "Overview,Genres,PrimaryImageAspectRatio,CommunityRating,ProductionYear,PremiereDate,RecursiveItemCount,ParentId,ImageTags,BackdropImageTags,ImageBlurHashes,UserData,SeriesPrimaryImage,People,Studios,ChildCount"
        private const val ITEM_IMAGE_TYPES = "Primary,Backdrop,Logo,Thumb"
        internal const val HOME_REQUEST_TIMEOUT_MILLIS = 30_000L

        fun authorizationHeader(token: String?, deviceId: String = "ZenStreamMobile") = listOf(
            token?.let { "Token=\"$it\"" },
            "Client=\"ZenStream\"",
            "Device=\"${deviceName()}\"",
            "DeviceId=\"$deviceId\"",
            "Version=\"${BuildConfig.ZENSTREAM_VERSION}\"",
        ).filterNotNull().joinToString(", ").let { "MediaBrowser $it" }

        private fun deviceName(): String = Build.MODEL
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }
            ?: "Android"

        private fun itemTypes(collectionType: String?, newlyAdded: Boolean = false) = when (collectionType) {
            "tvshows" -> if (newlyAdded) "Episode" else "Series"
            "movies" -> "Movie"
            "boxsets" -> "BoxSet"
            else -> "Series,Movie"
        }
    }

}

internal fun selectInitialSeason(
    item: MediaItem,
    seasons: List<MediaItem>,
    requestedSeasonId: String? = null,
): MediaItem? = requestedSeasonId?.let { id -> seasons.find { it.id == id } }
    ?: item.seasonId?.let { id -> seasons.find { it.id == id } }
    ?: seasons.find { it.indexNumber == 1 }
    ?: seasons.firstOrNull()

class JellyfinException(val statusCode: Int, message: String) : Exception(message)

private fun items(json: JSONObject): List<JSONObject> {
    val array = json.optJSONArray("Items") ?: return emptyList()
    return List(array.length()) { array.optJSONObject(it) ?: JSONObject() }
}

internal fun subtitleWebVttQuery(
    session: AuthSession,
    itemId: String,
    sourceId: String?,
): Map<String, String> = mapOf(
    "api_key" to session.token,
    "MediaSourceId" to (sourceId ?: itemId),
    "format" to "vtt",
    "addVttTimeMap" to "false",
    "copyTimestamps" to "false",
    // Keep cue timestamps on the item's absolute media timeline. The player
    // applies the playback source origin exactly once when selecting cues.
    "startPositionTicks" to "0",
)

private fun parseMediaSource(source: JSONObject): MediaSource {
    val streams = source.optJSONArray("MediaStreams")?.let { array ->
        List(array.length()) { index ->
            val stream = array.optJSONObject(index) ?: JSONObject()
            MediaStream(
                index = stream.optInt("Index", -1),
                type = stream.optString("Type"),
                displayTitle = stream.optString("DisplayTitle").ifBlank { null },
                language = stream.optString("Language").ifBlank { null },
                isDefault = stream.optBoolean("IsDefault", false),
            )
        }.filter { it.index >= 0 }
    } ?: emptyList()
    return MediaSource(
        id = source.optString("Id").ifBlank { null },
        directStreamUrl = source.optString("DirectStreamUrl").ifBlank { null },
        transcodingUrl = source.optString("TranscodingUrl").ifBlank { null },
        mediaStreams = streams,
        runTimeTicks = source.optLongOrNull("RunTimeTicks"),
    )
}

fun playbackUrl(
    session: AuthSession,
    itemId: String,
    source: MediaSource,
    bitrate: Int = 0,
    startTimeTicks: Long = 0L,
): String {
    val negotiated = source.transcodingUrl ?: source.directStreamUrl
    if (negotiated != null) {
        val resolved = session.serverUrl.toHttpUrl().resolve(negotiated)
            ?: error("Jellyfin returned an invalid playback URL")
        val url = resolved.newBuilder()
        if (url.build().queryParameter("api_key") == null && url.build().queryParameter("apiKey") == null) {
            url.addQueryParameter("api_key", session.token)
        }
        return url.build().toString()
    }
    val builder = "${session.serverUrl}/Videos/$itemId/${if (bitrate > 0) "stream.mp4" else "stream"}".toHttpUrl().newBuilder()
    builder.addQueryParameter("api_key", session.token)
    builder.addQueryParameter("Static", if (bitrate > 0) "false" else "true")
    builder.addQueryParameter("MediaSourceId", source.id ?: itemId)
    if (startTimeTicks > 0) builder.addQueryParameter("startTimeTicks", startTimeTicks.toString())
    if (bitrate > 0) builder.addQueryParameter("TranscodingMaxBitrate", bitrate.toString())
    builder.addQueryParameter("TranscodingMaxAudioChannels", "2")
    return builder.build().toString()
}

fun playbackStreamStartPositionSeconds(
    session: AuthSession,
    source: MediaSource,
    requestedStartSeconds: Double = 0.0,
): Double {
    val negotiated = source.transcodingUrl ?: source.directStreamUrl
    if (negotiated == null) return requestedStartSeconds.coerceAtLeast(0.0)
    val resolved = runCatching { session.serverUrl.toHttpUrl().resolve(negotiated) }.getOrNull()
    val startTicks = resolved?.queryParameter("startTimeTicks")?.toLongOrNull()
        ?: resolved?.queryParameter("StartTimeTicks")?.toLongOrNull()
    return startTicks?.div(10_000_000.0)?.coerceAtLeast(0.0) ?: 0.0
}

fun playbackLocalPositionSeconds(absolutePositionSeconds: Double, streamOriginSeconds: Double): Double =
    (absolutePositionSeconds - streamOriginSeconds).coerceAtLeast(0.0)

fun parseMediaItems(json: JSONObject): List<MediaItem> = items(json).mapNotNull { item ->
    val id = item.optString("Id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
    val userData = item.optJSONObject("UserData")
    val imageTags = buildMap {
        item.optJSONObject("ImageTags")?.keys()?.forEach { key ->
            item.optJSONObject("ImageTags")?.optString(key)?.takeIf { it.isNotBlank() }
                ?.let { put(key, it) }
        }
    }
    val backdropTags = item.optJSONArray("BackdropImageTags")
        ?.let { array -> List(array.length()) { array.optString(it) }.filter(String::isNotBlank) }
        ?: emptyList()
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
        recursiveItemCount = item.optIntOrNull("RecursiveItemCount")
            ?: item.optIntOrNull("ChildCount"),
        runtimeTicks = item.optLongOrNull("RunTimeTicks"),
        imageTags = imageTags,
        backdropImageTags = backdropTags,
        seriesPrimaryImageTag = item.optString("SeriesPrimaryImageTag").ifBlank { null },
        played = userData?.optBoolean("Played") ?: false,
        favorite = userData?.optBoolean("IsFavorite") ?: false,
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
        }.filter { it.startPositionTicks >= 0L }
            .sortedBy { it.startPositionTicks }
    } ?: emptyList()

internal fun parsePlaybackMarkers(value: Any?): List<PlaybackSegment> {
    val entries = when (value) {
        is JSONArray -> List(value.length()) { value.optJSONObject(it) }.filterNotNull()
        is JSONObject -> value.optJSONArray("Items")?.let { items ->
            List(items.length()) { items.optJSONObject(it) }.filterNotNull()
        } ?: listOf(value)
        else -> emptyList()
    }
    val typed = normalizePlaybackMarkers(
        entries.map { entry ->
            PlaybackMarkerInput(
                type = entry.optString("Type").ifBlank { null },
                start = entry.numberValue("StartTicks", "StartTimeTicks"),
                end = entry.numberValue("EndTicks", "EndTimeTicks"),
            )
        }
    )
    if (typed.isNotEmpty()) return typed
    val root = value as? JSONObject ?: return emptyList()
    return listOfNotNull(
        parseNamedMarkerInput(
            root,
            listOf("intro", "introduction", "opening"),
            listOf("IntroStart", "IntroStartTicks", "StartTicks"),
            listOf("IntroEnd", "IntroEndTicks", "EndTicks"),
        )?.let { PlaybackMarkerInput("Intro", it.first, it.second) },
        parseNamedMarkerInput(
            root,
            listOf("outro", "credits", "closing"),
            listOf("OutroStart", "CreditsStart", "CreditsStartTicks"),
            listOf("OutroEnd", "CreditsEnd", "CreditsEndTicks"),
        )?.let { PlaybackMarkerInput("Outro", it.first, it.second) },
    ).let(::normalizePlaybackMarkers)
}

private fun parseNamedMarkerInput(
    root: JSONObject,
    names: List<String>,
    startKeys: List<String>,
    endKeys: List<String>,
): Pair<Double, Double>? {
    val nested = names.asSequence()
        .mapNotNull { root.opt(it) as? JSONObject }
        .firstOrNull()
    val start = nested?.numberValue("start", "Start")
        ?: startKeys.asSequence().mapNotNull(root::numberValue).firstOrNull()
    val end = nested?.numberValue("end", "End")
        ?: endKeys.asSequence().mapNotNull(root::numberValue).firstOrNull()
    return if (start != null && end != null) start to end else null
}

internal data class PlaybackMarkerInput(
    val type: String?,
    val start: Double?,
    val end: Double?,
)

internal fun normalizePlaybackMarkers(inputs: List<PlaybackMarkerInput>): List<PlaybackSegment> =
    inputs.mapNotNull { input ->
        val type = when (input.type?.lowercase()) {
            "intro", "opening" -> PlaybackSegmentType.INTRO
            "outro", "credits", "closing" -> PlaybackSegmentType.OUTRO
            else -> null
        }
        if (type != null && input.start != null && input.end != null) {
            marker(type, input.start, input.end)
        } else null
    }

private fun marker(type: PlaybackSegmentType, rawStart: Double, rawEnd: Double): PlaybackSegment? {
    val start = rawStart.toPlaybackSeconds()
    val end = rawEnd.toPlaybackSeconds()
    return if (start.isFinite() && end.isFinite() && end > start) {
        PlaybackSegment(type, start, end)
    } else null
}

private fun JSONObject.numberValue(vararg keys: String): Double? = keys.asSequence()
    .mapNotNull { key ->
        if (!has(key) || isNull(key)) null else when (val value = opt(key)) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            null -> null
            else -> null
        }
    }
    .firstOrNull()

private fun Double.toPlaybackSeconds(): Double =
    if (this > 1_000_000.0) this / 10_000_000.0 else this

internal fun chapterPlaybackSegments(item: MediaItem): List<PlaybackSegment> {
    val chapters = item.chapters.sortedBy { it.startPositionTicks }
    val runtime = item.runtimeTicks ?: return emptyList()
    return chapters.mapIndexedNotNull { index, chapter ->
        val end = chapters.getOrNull(index + 1)?.startPositionTicks ?: runtime
        chapterNameType(chapter.name)?.let { type ->
            marker(type, chapter.startPositionTicks.toDouble(), end.toDouble())
        }
    }
}

private fun chapterNameType(name: String?): PlaybackSegmentType? {
    val normalized = name.orEmpty().trim().lowercase()
    return when {
        normalized == "op" || normalized == "opening" || normalized.contains("intro") -> PlaybackSegmentType.INTRO
        normalized == "ed" || normalized == "ending" || normalized == "outro" ||
            normalized.contains("credit") || normalized.contains("closing") -> PlaybackSegmentType.OUTRO
        else -> null
    }
}

internal fun mergePlaybackSegments(
    providerSegments: List<PlaybackSegment>,
    chapterSegments: List<PlaybackSegment>,
): List<PlaybackSegment> {
    val providerTypes = providerSegments.map { it.type }.toSet()
    return (providerSegments + chapterSegments.filter { it.type !in providerTypes })
        .filter { it.startSeconds >= 0.0 && it.endSeconds > it.startSeconds }
        .sortedWith(compareBy<PlaybackSegment> { it.startSeconds }.thenBy { it.type })
}

internal fun playbackMarkerPaths(itemId: String): List<String> = listOf(
    "/Episode/$itemId/IntroSkipperSegments",
    "/Episode/$itemId/Timestamps",
    "/MediaSegments/$itemId",
)

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
            person.optString("Name").takeIf { it.isNotBlank() }?.let {
                MediaPerson(
                    name = it,
                    role = person.optString("Role").ifBlank { null },
                    type = person.optString("Type").ifBlank { null },
                    primaryImageTag = person.optString("PrimaryImageTag").ifBlank { null },
                )
            }
        }.filterNotNull()
    } ?: emptyList()

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null
