package com.zenstream.zenstreammobile.data

import android.os.Build
import com.zenstream.zenstreammobile.BuildConfig
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.DetailData
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
import com.zenstream.zenstreammobile.model.PagedLibrary
import com.zenstream.zenstreammobile.model.PlaybackData
import com.zenstream.zenstreammobile.model.PlaybackOptions
import com.zenstream.zenstreammobile.model.PlaybackSegment
import com.zenstream.zenstreammobile.model.PlaybackSegmentType
import com.zenstream.zenstreammobile.model.RowTitle
import com.zenstream.zenstreammobile.model.TrickplayInfo
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

class CatalogApi(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val deviceId: String = UUID.randomUUID().toString(),
) {
    suspend fun authenticate(serverUrl: String, username: String, password: String): AuthSession =
        withContext(Dispatchers.IO) {
            val server = normalizeServerUrl(serverUrl)
            val body = JSONObject()
                .put("username", username.trim())
                .put("password", password)
                .toString()
            val json = requestJson(
                server = server,
                path = "/api/auth/login",
                token = null,
                method = "POST",
                body = body,
            )
            val user = json.optJSONObject("user")
            val token = json.optString("token").takeIf { it.isNotBlank() }
                ?: error("Server did not return an access token")
            val userId = user?.optString("id").orEmpty().takeIf { it.isNotBlank() }
                ?: error("Server did not return a user ID")
            val ticket = requestJson(server, "/api/auth/resource-ticket", token = token)
                .optString("ticket").takeIf { it.isNotBlank() }
            AuthSession(
                server,
                token,
                userId,
                user?.optString("username").orEmpty().ifBlank { username.trim() },
                ticket)
        }

    suspend fun playback(
        session: AuthSession,
        itemId: String,
        options: PlaybackOptions = PlaybackOptions(),
    ): PlaybackData = withContext(Dispatchers.IO) {
        val item = getItem(session, itemId)
        val json = requestJson(
            session,
            "/api/playback/items/$itemId/negotiate",
            method = "POST",
            body = JSONObject()
                .put("engine", "media3")
                .put("mediaSourceId", options.mediaSourceId)
                .put("forceTranscoding", options.forceTranscoding)
                .put("containers", if (options.forceTranscoding) JSONArray() else JSONArray(listOf("mp4", "webm")))
                .put("videoCodecs", if (options.forceTranscoding) JSONArray() else JSONArray(listOf("h264", "vp9", "av1")))
                .put("audioCodecs", if (options.forceTranscoding) JSONArray() else JSONArray(listOf("aac", "opus", "vorbis")))
                .put("maxStreamingBitrate", options.maxStreamingBitrate)
                .put("audioStreamIndex", options.audioStreamIndex)
                .toString(),
        )
        val sourcePayload = json.optJSONObject("source") ?: error("Server did not return a media source")
        val sourceJson = playbackSource(json, sourcePayload)
        val source = parseMediaSource(sourceJson)
        val playSessionId = json.optString("sessionId").ifBlank { null }
        PlaybackData(
            item = item,
            source = source,
            audio = source.mediaStreams.filter { it.type == "Audio" },
            subtitles = source.mediaStreams.filter { it.type == "Subtitle" },
            segments = getPlaybackSegments(session, itemId, item),
            playSessionId = playSessionId,
        )
    }

    suspend fun trickplay(
        session: AuthSession,
        itemId: String,
    ): Map<String, Map<String, TrickplayInfo>> = emptyMap()

    private fun getPlaybackSegments(
        session: AuthSession,
        itemId: String,
        item: MediaItem,
    ): List<PlaybackSegment> {
        val providerSegments = playbackMarkerPaths(android.net.Uri.encode(itemId)).asSequence()
            .mapNotNull { path ->
                runCatching {
                    parsePlaybackMarkers(
                        requestMarkerPayload(
                            session,
                            path
                        )
                    )
                }.getOrNull()
            }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        return mergePlaybackSegments(providerSegments, chapterPlaybackSegments(item))
    }

    private fun requestMarkerPayload(session: AuthSession, path: String): Any? {
        val request = Request.Builder()
            .url("${session.serverUrl}$path".toHttpUrl())
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${session.token}")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw CatalogException(
                response.code,
                "ZenStream marker request failed with ${response.code}",
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
		val negotiated = requestJson(
			session,
			"/api/playback/items/$itemId/negotiate",
			method = "POST",
			body = JSONObject().put("engine", "mpv").toString(),
		)
		val streams = negotiated.optJSONObject("source")?.optJSONArray("streams")
		val mediaFileId = streams?.let { array ->
			(0 until array.length()).asSequence().mapNotNull { array.optJSONObject(it) }
				.firstOrNull { it.optInt("index", -1) == streamIndex }
				?.optString("fileId")?.takeIf { it.isNotBlank() }
		} ?: error("Subtitle track is not an external text subtitle")
		val builder = "${session.serverUrl}/api/playback/items/$itemId/subtitles/$mediaFileId.vtt"
			.toHttpUrl().newBuilder()
		session.resourceTicket?.let { builder.addQueryParameter("access", it) }
        val request = Request.Builder()
            .url(builder.build())
            .header("Accept", "text/vtt")
            .header("Authorization", "Bearer ${session.token}")
            .get()
            .build()
        httpClient.newCall(request).execute().use {
            if (!it.isSuccessful) throw CatalogException(
                it.code,
                "Subtitle request failed with ${it.code}"
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
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject()
			.put("positionSeconds", positionSeconds.coerceAtLeast(0.0))
		durationSeconds?.takeIf { it.isFinite() && it > 0 }?.let { body.put("durationSeconds", it) }
        requestJson(
            session,
			"/api/catalog/items/$itemId/state",
			method = "PATCH",
            body = body.toString(),
        )
    }

    internal fun playbackQuery(
        session: AuthSession,
        options: PlaybackOptions
    ): Map<String, String> =
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

    private fun playbackParameters(
        session: AuthSession,
        options: PlaybackOptions
    ): Map<String, Any?> = mapOf(
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
        val directPlay = JSONArray()
            .put(
                JSONObject().put("Type", "Video").put("VideoCodec", "h264,h265,vp9,av1")
                    .put("AudioCodec", "aac,ac3,opus,vorbis,mp3").put("Container", "mp4,mkv,webm")
            )
        val subtitles = JSONArray()
            .put(JSONObject().put("Format", "vtt").put("Method", "External"))
        val transcoding = JSONArray()
            .put(
                JSONObject().put("Type", "Video").put("Context", "Streaming").put("Protocol", "hls")
                    .put("Container", "ts").put("VideoCodec", "h264").put("AudioCodec", "aac")
                    .put("MaxAudioChannels", "2").put("MinSegments", 1)
                    .put("BreakOnNonKeyFrames", true)
            )
        return JSONObject()
            .put("Name", "ZenStream Android")
            .put("MaxStreamingBitrate", options.maxStreamingBitrate)
            .put("DirectPlayProfiles", directPlay)
            .put("SubtitleProfiles", subtitles)
            .put(
                "TranscodingProfiles",
                if (options.directPlayOnly) JSONArray() else transcoding
            )
    }

    suspend fun fetchHome(session: AuthSession): HomeData = coroutineScope {
        val home = async {
            requestJson(session, "/api/catalog/home", requestTimeoutMillis = HOME_REQUEST_TIMEOUT_MILLIS)
        }
        val libraries = async { getLibraries(session, HOME_REQUEST_TIMEOUT_MILLIS) }
        val libraryData = libraries.await().flatMap { library ->
            if (library.collectionType != "tvshows" && library.collectionType != "movies") emptyList()
            else listOf(async { fetchLibraryData(session, library, HOME_REQUEST_TIMEOUT_MILLIS) })
        }.awaitAll()
        val payload = home.await()
        val rows = buildList {
            add(MediaRow(RowTitle.ContinueWatching, items = catalogItems(payload, "continueWatching"), wide = true))
            add(MediaRow(RowTitle.NextUp, items = catalogItems(payload, "nextUp"), wide = true))
            libraryData.flatMapTo(this) { it.rows }
        }.filter { it.items.isNotEmpty() }
        HomeData(
            featured = catalogItems(payload, "latestItems").filter { it.backdropImageTags.isNotEmpty() }.take(5),
            rows = rows
        )
    }

    suspend fun fetchHomeFeatured(session: AuthSession): List<MediaItem> =
        catalogItems(requestJson(session, "/api/catalog/home", requestTimeoutMillis = HOME_REQUEST_TIMEOUT_MILLIS), "latestItems")
            .filter { it.backdropImageTags.isNotEmpty() }.take(5)

    suspend fun fetchHomeContinueWatching(session: AuthSession): List<MediaItem> =
        catalogItems(requestJson(session, "/api/catalog/home", requestTimeoutMillis = HOME_REQUEST_TIMEOUT_MILLIS), "continueWatching")

    suspend fun fetchHomeNextUp(session: AuthSession): List<MediaItem> =
        catalogItems(requestJson(session, "/api/catalog/home", requestTimeoutMillis = HOME_REQUEST_TIMEOUT_MILLIS), "nextUp")

    internal fun nextUpItemsQuery(userId: String): Map<String, String> = mapOf(
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

    internal fun latestItemsQuery(userId: String): Map<String, String> = mapOf(
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
    ): List<Library> = withContext(Dispatchers.IO) {
        val json = requestJson(session, "/api/catalog/libraries", requestTimeoutMillis = requestTimeoutMillis)
        jsonArray(json, "libraries").mapNotNull { item ->
            item.optString("id").takeIf { it.isNotBlank() }?.let { id ->
                Library(
                    id,
                    item.optString("name").ifBlank { "Library" },
                    when (item.optString("type")) { "tv_series" -> "tvshows"; "movies" -> "movies"; "collection" -> "boxsets"; else -> null },
                    item.optBoolean("supportsLastAdded", item.optString("type") != "movies"))
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
			fun path(sortBy: String) = "/api/catalog/items?libraryId=${android.net.Uri.encode(library.id)}&pageSize=18&sortBy=$sortBy&sortOrder=descending"
			val recent = async { catalogItems(requestJson(session, path(if (library.supportsLastAdded) "lastAdded" else "added"), requestTimeoutMillis = requestTimeoutMillis)) }
			val topRated = async { catalogItems(requestJson(session, path("rating"), requestTimeoutMillis = requestTimeoutMillis)) }
			val newReleases = async { catalogItems(requestJson(session, path("release"), requestTimeoutMillis = requestTimeoutMillis)) }
            LibraryData(
                library, listOf(
                    MediaRow(RowTitle.NewlyAdded, library.name, recent.await()),
                    MediaRow(RowTitle.TopRated, library.name, topRated.await()),
                    MediaRow(RowTitle.NewReleases, library.name, newReleases.await()),
                ).filter { it.items.isNotEmpty() })
        }

    suspend fun fetchLibraryPage(
        session: AuthSession,
        library: Library,
        startIndex: Int,
        limit: Int,
        sort: LibrarySort,
    ): PagedLibrary = withContext(Dispatchers.IO) {
        val page = startIndex / limit + 1
		val sortBy = catalogSort(sort.sortBy)
		val json = requestJson(session, "/api/catalog/items?libraryId=${android.net.Uri.encode(library.id)}&page=$page&pageSize=$limit&sortBy=$sortBy&sortOrder=${sort.sortOrder.apiValue.lowercase()}")
		val parsed = catalogItems(json)
        PagedLibrary(
            library = library,
            items = parsed,
            totalRecordCount = json.optInt("total", parsed.size),
        )
    }

    suspend fun search(session: AuthSession, query: String): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (query.trim().length < 2) return@withContext emptyList()
			catalogItems(requestJson(session, "/api/catalog/search?query=${android.net.Uri.encode(query.trim())}&pageSize=40"))
        }

    internal fun searchQuery(userId: String, query: String): Map<String, String> = mapOf(
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
    ): Map<String, String> = mapOf(
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

    suspend fun setFavorite(session: AuthSession, itemId: String, favorite: Boolean) =
        withContext(Dispatchers.IO) {
            requestJson(session, "/api/catalog/items/$itemId/state", method = "PATCH", body = JSONObject().put("favorite", favorite).toString())
        }

    suspend fun setPlayed(session: AuthSession, itemId: String, played: Boolean) =
        withContext(Dispatchers.IO) {
            requestJson(session, "/api/catalog/items/$itemId/state", method = "PATCH", body = JSONObject().put("played", played).toString())
        }

    private fun getItem(session: AuthSession, itemId: String): MediaItem =
        catalogMediaItem(requestJson(session, "/api/catalog/items/$itemId"))

    private fun getChildren(session: AuthSession, parent: MediaItem): List<MediaItem> {
        val libraryId = parent.libraryId ?: return emptyList()
        val path = "/api/catalog/items?libraryId=${android.net.Uri.encode(libraryId)}&parentId=${android.net.Uri.encode(parent.id)}&pageSize=100"
        return catalogItems(requestJson(session, path))
    }

    private fun getSeasons(session: AuthSession, seriesId: String): List<MediaItem> =
        getChildren(session, getItem(session, seriesId))

    private fun getEpisodes(session: AuthSession, seriesId: String, seasonId: String): List<MediaItem> {
        @Suppress("UNUSED_VARIABLE") val ignoredSeriesId = seriesId
        return getChildren(session, getItem(session, seasonId)).sortedWith(compareBy(nullsLast()) { it.indexNumber })
    }

    private fun getSimilar(session: AuthSession, itemId: String): List<MediaItem> =
        catalogItems(requestJson(session, "/api/catalog/items/$itemId/similar"))

    private fun requestJson(
        session: AuthSession,
        path: String,
        query: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: String? = null,
        requestTimeoutMillis: Long? = null,
    ): JSONObject = requestJson(session.serverUrl, path, query, session.token, method, body, requestTimeoutMillis)

    private fun requestJson(
        server: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        token: String?,
        method: String = "GET",
        body: String? = null,
        requestTimeoutMillis: Long? = null,
    ): JSONObject {
        val urlBuilder = "$server$path".toHttpUrl().newBuilder()
        query.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .apply { token?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") } }
            .method(method, if (method == "GET") null else (body ?: "{}").toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val call = httpClient.newCall(request)
        requestTimeoutMillis?.let { call.timeout().timeout(it, java.util.concurrent.TimeUnit.MILLISECONDS) }
        call.execute().use {
            if (!it.isSuccessful) throw CatalogException(it.code, "ZenStream request failed with ${it.code}")
            return JSONObject(it.body?.string().orEmpty().ifBlank { "{}" })
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val ITEM_FIELDS = "Overview,Genres,CommunityRating,ProductionYear,PremiereDate,People,Studios,Chapters"
        private const val ITEM_IMAGE_TYPES = "Primary,Backdrop,Logo,Banner"
        internal const val HOME_REQUEST_TIMEOUT_MILLIS = 30_000L

        fun authorizationHeader(token: String) = "Bearer $token"
    }

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
	root.optJSONArray(key)?.let { array ->
		List(array.length()) { array.optJSONObject(it) ?: JSONObject() }
	}.orEmpty()

internal fun catalogItems(root: JSONObject, key: String = "items"): List<MediaItem> =
	jsonArray(root, key).map(::catalogMediaItem)

internal fun catalogMediaItem(item: JSONObject): MediaItem {
	val metadata = item.optJSONObject("metadata") ?: JSONObject()
	val state = item.optJSONObject("userState") ?: JSONObject()
	val images = metadata.optJSONObject("images") ?: JSONObject()
	fun image(category: String) = images.optJSONObject(category)?.optString("url")?.takeIf { it.isNotBlank() }
	val type = when (item.optString("type")) {
		"movie" -> "Movie"; "series" -> "Series"; "season" -> "Season"
		"episode" -> "Episode"; "collection" -> "BoxSet"; else -> item.optString("type")
	}
	val people = metadata.optJSONArray("people")?.let { array ->
		List(array.length()) { index ->
			val person = array.optJSONObject(index) ?: JSONObject()
			MediaPerson(person.optString("name"), person.optString("role").ifBlank { null }, person.optString("department").ifBlank { null })
		}.filter { it.name.isNotBlank() }
	}.orEmpty()
	val genres = metadata.optJSONArray("genres") ?: metadata.optJSONArray("tags")
	return MediaItem(
		id = item.optString("id"),
		name = metadata.optString("title").ifBlank { item.optString("name").ifBlank { "Untitled" } },
		type = type,
		seriesId = item.optString("seriesId").ifBlank { null },
		seasonId = item.optString("seasonId").ifBlank { null },
		parentId = item.optString("parentId").ifBlank { null },
		libraryId = item.optString("libraryId").ifBlank { null },
		parentIndexNumber = item.optIntOrNull("seasonNumber"),
		indexNumber = item.optIntOrNull("episodeNumber"),
		overview = metadata.optString("overview").ifBlank { metadata.optString("description").ifBlank { null } },
		premiereDate = metadata.optString("date").ifBlank { metadata.optString("releaseDate").ifBlank { null } },
		productionYear = metadata.optIntOrNull("year"),
		communityRating = metadata.optDoubleOrNull("communityRating"),
		genres = genres?.let { array -> List(array.length()) { array.optString(it) }.filter(String::isNotBlank) }.orEmpty(),
		people = people,
		runtimeTicks = metadata.optDoubleOrNull("runtimeMinutes")?.let { (it * 60.0 * 10_000_000.0).toLong() },
		imageTags = buildMap { image("Primary")?.let { put("Primary", it) }; image("Logo")?.let { put("Logo", it) }; image("Banner")?.let { put("Banner", it) } },
		backdropImageTags = image("Backdrop")?.let(::listOf).orEmpty(),
		played = state.optBoolean("played", false),
		favorite = state.optBoolean("favorite", false),
		playedPercentage = state.optDoubleOrNull("playedPercentage"),
		playbackPositionTicks = state.optDoubleOrNull("positionSeconds")?.let { (it * 10_000_000.0).toLong() },
	)
}

private fun playbackSource(root: JSONObject, source: JSONObject): JSONObject {
	val streams = source.optJSONArray("streams") ?: JSONArray()
	val mappedStreams = JSONArray()
	for (index in 0 until streams.length()) {
		val stream = streams.optJSONObject(index) ?: continue
		val type = stream.optString("codec_type").replaceFirstChar { it.uppercase() }
		mappedStreams.put(JSONObject()
			.put("Index", stream.optInt("index", index))
			.put("Type", type)
			.put("Language", stream.optJSONObject("tags")?.optString("language"))
			.put("DisplayTitle", stream.optJSONObject("tags")?.optString("title"))
			.put("Kind", stream.optString("kind"))
			.put("IsDefault", stream.optJSONObject("disposition")?.optInt("default", 0) == 1))
	}
	return JSONObject()
		.put("Id", source.optString("id"))
		.put("Container", source.optString("container"))
		.put("RunTimeTicks", (source.optDouble("durationSeconds", 0.0) * 10_000_000.0).toLong())
		.put("MediaStreams", mappedStreams)
		.put(if (root.optString("mode") == "hls") "TranscodingUrl" else "DirectStreamUrl", root.optString("url"))
}

internal fun selectInitialSeason(
    item: MediaItem,
    seasons: List<MediaItem>,
    requestedSeasonId: String? = null,
): MediaItem? = requestedSeasonId?.let { id -> seasons.find { it.id == id } }
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
    put("MediaSourceId", sourceId ?: itemId)
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
				isLyrics = stream.optString("Kind") == "lyrics" || stream.optString("DisplayTitle") == "Lyrics",
            )
        }.filter { it.index >= 0 }
    } ?: emptyList()
    return MediaSource(
        id = source.optString("Id").ifBlank { null },
        directStreamUrl = source.optString("DirectStreamUrl").ifBlank { null },
        transcodingUrl = source.optString("TranscodingUrl").ifBlank { null },
        mediaStreams = streams,
        runTimeTicks = source.optLongOrNull("RunTimeTicks"),
        trickplay = parseTrickplaySource(source.optJSONObject("Trickplay")),
        container = source.optString("Container").ifBlank { null },
        transcodingContainer = source.optString("TranscodingContainer").ifBlank { null },
    )
}

internal fun parseTrickplayBySource(value: JSONObject?): Map<String, Map<String, TrickplayInfo>> =
    value?.keys()?.asSequence()?.mapNotNull { sourceId ->
        val source = value.optJSONObject(sourceId) ?: return@mapNotNull null
        sourceId to parseTrickplaySource(source)
    }?.toMap().orEmpty()

internal fun parseTrickplaySource(value: JSONObject?): Map<String, TrickplayInfo> =
    value?.keys()?.asSequence()?.mapNotNull { width ->
        val info = value.optJSONObject(width) ?: return@mapNotNull null
        width to TrickplayInfo(
            width = info.optIntOrNull("Width") ?: info.optIntOrNull("width"),
            height = info.optIntOrNull("Height") ?: info.optIntOrNull("height"),
            tileWidth = info.optIntOrNull("TileWidth") ?: info.optIntOrNull("tileWidth"),
            tileHeight = info.optIntOrNull("TileHeight") ?: info.optIntOrNull("tileHeight"),
            intervalMillis = info.optLongOrNull("Interval") ?: info.optLongOrNull("interval"),
        )
    }?.toMap().orEmpty()

internal fun playbackMimeType(source: MediaSource, bitrate: Int = 0): String? {
    val negotiatedUrl = source.transcodingUrl ?: source.directStreamUrl
    val urlPath = negotiatedUrl
        ?.substringBefore('?')
        ?.substringBefore('#')
        ?.lowercase()
    if (urlPath?.endsWith(".m3u8") == true || source.transcodingUrl != null || bitrate > 0) {
        // The gateway deliberately rewrites manifests to /api/video/.../stream,
        // so Media3 cannot infer HLS from the URL after negotiation.
        return "application/x-mpegURL"
    }

    return when (source.container?.lowercase()) {
        "mp4", "m4v", "mov" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts", "m2ts" -> "video/mp2t"
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
    startTimeTicks: Long = 0L,
): String {
    val negotiated = source.transcodingUrl ?: source.directStreamUrl
    if (negotiated != null) {
        val gateway = session.serverUrl.toHttpUrl()
        val resolved = gateway.resolve(negotiated)
        if (resolved != null && resolved.scheme == gateway.scheme &&
            resolved.host == gateway.host && resolved.port == gateway.port
        ) {
            return resolved.toString()
        }
    }
	val builder =
		"${session.serverUrl}/api/playback/items/$itemId/stream".toHttpUrl()
            .newBuilder()
    builder.addQueryParameter("Static", if (bitrate > 0) "false" else "true")
    builder.addQueryParameter("MediaSourceId", source.id ?: itemId)
    if (startTimeTicks > 0) builder.addQueryParameter("startTimeTicks", startTimeTicks.toString())
    if (bitrate > 0) builder.addQueryParameter("TranscodingMaxBitrate", bitrate.toString())
    builder.addQueryParameter("TranscodingMaxAudioChannels", "2")
    session.resourceTicket?.let { builder.addQueryParameter("access", it) }
    return builder.build().toString()
}

fun playbackStreamStartPositionSeconds(
    session: AuthSession,
    source: MediaSource,
    requestedStartSeconds: Double = 0.0,
    streamStartsAtRequestedPosition: Boolean = false,
): Double {
    val negotiated = source.transcodingUrl ?: source.directStreamUrl
    if (negotiated == null) return requestedStartSeconds.coerceAtLeast(0.0)
    val resolved = runCatching { session.serverUrl.toHttpUrl().resolve(negotiated) }.getOrNull()
    val startTicks = resolved?.queryParameter("startTimeTicks")?.toLongOrNull()
        ?: resolved?.queryParameter("StartTimeTicks")?.toLongOrNull()
    return startTicks?.div(10_000_000.0)?.coerceAtLeast(0.0)
        ?: requestedStartSeconds.takeIf { streamStartsAtRequestedPosition }?.coerceAtLeast(0.0)
        ?: 0.0
}

fun playbackLocalPositionSeconds(
    absolutePositionSeconds: Double,
    streamOriginSeconds: Double
): Double =
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
    "/api/playback/markers/$itemId",
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
