package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.HomeData
import com.zenstream.zenstreammobile.model.Library
import com.zenstream.zenstreammobile.model.LibraryData
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.MediaRow
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
import org.json.JSONObject
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

    suspend fun fetchHome(session: AuthSession): HomeData = coroutineScope {
        val latest = async {
            getItems(
                session,
                "/Items",
                latestItemsQuery(session.userId)
            )
        }
        val resume = async {
            getItems(
                session,
                "/UserItems/Resume",
                mapOf(
                    "userId" to session.userId,
                    "limit" to "18",
                    "includeItemTypes" to "Episode,Movie"
                )
            )
        }
        val nextUp = async {
            getItems(
                session,
                "/Shows/NextUp",
                mapOf("userId" to session.userId, "limit" to "18", "disableFirstEpisode" to "true")
            )
        }
        val libraries = async { getLibraries(session) }
        val libraryData = libraries.await().flatMap { library ->
            if (library.collectionType != "tvshows" && library.collectionType != "movies") emptyList()
            else listOf(async { fetchLibraryData(session, library) })
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

    suspend fun getLibraries(session: AuthSession): List<Library> = withContext(Dispatchers.IO) {
        val json = requestJson(
            session,
            "/Users/${session.userId}/Views",
            mapOf("fields" to "CollectionType")
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

    suspend fun fetchLibraryData(session: AuthSession, library: Library): LibraryData =
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
                    common + mapOf(
                        "sortBy" to "DateCreated",
                        "sortOrder" to "Descending",
                        "includeItemTypes" to itemTypes(library.collectionType)
                    )
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
                    )
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
                    )
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

    private suspend fun getItems(
        session: AuthSession,
        path: String,
        query: Map<String, String>
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        parseMediaItems(requestJson(session, path, query))
    }

    private fun requestJson(
        session: AuthSession,
        path: String,
        query: Map<String, String> = emptyMap(),
    ): JSONObject = requestJson(session.serverUrl, path, query, session.token)

    private fun requestJson(
        server: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        token: String?,
        method: String = "GET",
        body: String? = null,
    ): JSONObject {
        val urlBuilder = "$server${path}".toHttpUrl().newBuilder()
        query.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
        val requestBuilder = Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Authorization", authorizationHeader(token, deviceId))
        if (method == "POST") requestBuilder.post((body ?: "{}").toRequestBody(JSON_MEDIA_TYPE))
        val response = httpClient.newCall(requestBuilder.build()).execute()
        response.use {
            if (!it.isSuccessful) throw JellyfinException(
                it.code,
                "Jellyfin request failed with ${it.code}"
            )
            return JSONObject(it.body?.string().orEmpty())
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val ITEM_FIELDS =
            "Overview,Genres,PrimaryImageAspectRatio,CommunityRating,ProductionYear,PremiereDate,RecursiveItemCount,ParentId,ImageTags,BackdropImageTags,ImageBlurHashes,UserData,SeriesPrimaryImage"
        private const val ITEM_IMAGE_TYPES = "Primary,Backdrop,Logo,Thumb"

        fun authorizationHeader(token: String?, deviceId: String = "ZenStreamMobile") = listOf(
            token?.let { "Token=\"$it\"" },
            "Client=\"ZenStream\"",
            "Device=\"Android\"",
            "DeviceId=\"$deviceId\"",
            "Version=\"1.0\"",
        ).filterNotNull().joinToString(", ").let { "MediaBrowser $it" }

        private fun itemTypes(collectionType: String?) = when (collectionType) {
            "tvshows" -> "Series"
            "movies" -> "Movie"
            "boxsets" -> "BoxSet"
            else -> "Series,Movie"
        }
    }
}

class JellyfinException(val statusCode: Int, message: String) : Exception(message)

private fun items(json: JSONObject): List<JSONObject> {
    val array = json.optJSONArray("Items") ?: return emptyList()
    return List(array.length()) { array.optJSONObject(it) ?: JSONObject() }
}

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
        parentIndexNumber = item.optIntOrNull("ParentIndexNumber"),
        indexNumber = item.optIntOrNull("IndexNumber"),
        overview = item.optString("Overview").ifBlank { null },
        productionYear = item.optIntOrNull("ProductionYear"),
        officialRating = item.optString("OfficialRating").ifBlank { null },
        communityRating = item.optDoubleOrNull("CommunityRating"),
        runtimeTicks = item.optLongOrNull("RunTimeTicks"),
        imageTags = imageTags,
        backdropImageTags = backdropTags,
        seriesPrimaryImageTag = item.optString("SeriesPrimaryImageTag").ifBlank { null },
        played = userData?.optBoolean("Played") ?: false,
        unplayedItemCount = userData?.optIntOrNull("UnplayedItemCount"),
        playedPercentage = userData?.optDoubleOrNull("PlayedPercentage"),
        playbackPositionTicks = userData?.optLongOrNull("PlaybackPositionTicks"),
    )
}

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null
