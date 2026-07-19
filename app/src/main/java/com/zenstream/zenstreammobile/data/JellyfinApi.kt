package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.DetailData
import com.zenstream.zenstreammobile.model.HomeData
import com.zenstream.zenstreammobile.model.Library
import com.zenstream.zenstreammobile.model.LibraryData
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.MediaPerson
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
            val json = requestJson(session, "/Items/$itemId", detailItemQuery(session.userId))
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
        query: Map<String, String>
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        parseMediaItems(requestJson(session, path, query))
    }

    private fun requestJson(
        session: AuthSession,
        path: String,
        query: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: String? = null,
    ): JSONObject = requestJson(session.serverUrl, path, query, session.token, method, body)

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
        requestBuilder.method(
            method,
            if (method == "GET") null else (body ?: "{}").toRequestBody(JSON_MEDIA_TYPE),
        )
        val response = httpClient.newCall(requestBuilder.build()).execute()
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
    )
}

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
