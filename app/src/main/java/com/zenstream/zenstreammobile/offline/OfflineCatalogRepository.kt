package com.zenstream.zenstreammobile.offline

import androidx.room.withTransaction
import com.zenstream.zenstreammobile.data.catalogMediaItem
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaItem
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

data class OfflineEpisodeNavigation(
    val previous: MediaItem? = null,
    val next: MediaItem? = null,
    val nextUnavailable: Boolean = false,
)

internal data class OfflineEpisodeNeighborIds(
    val previous: String? = null,
    val next: String? = null,
)

internal data class BundledEpisodeNeighbor(val id: String?)

internal fun JSONObject?.bundledEpisodeNeighbor(direction: String): BundledEpisodeNeighbor? {
    if (this == null || !has(direction)) return null
    if (isNull(direction)) return BundledEpisodeNeighbor(null)
    return BundledEpisodeNeighbor(optString(direction).takeIf { it.isNotBlank() && it != "null" })
}

internal fun offlineEpisodeNeighborIds(
    currentId: String,
    episodes: List<OfflineCatalogItem>,
): OfflineEpisodeNeighborIds {
    val current =
        episodes.firstOrNull { it.id == currentId && it.type == "episode" }
            ?: return OfflineEpisodeNeighborIds()
    val previous =
        episodes
            .asSequence()
            .filter { candidate ->
                candidate.type == "episode" &&
                    (candidate.season < current.season ||
                        candidate.season == current.season &&
                            candidate.episodeEnd < current.episode)
            }
            .maxWithOrNull(
                compareBy<OfflineCatalogItem>(
                    { it.season },
                    { it.episodeEnd },
                    { it.episode },
                    { it.id },
                )
            )
            ?.id
    val next =
        episodes
            .asSequence()
            .filter { candidate ->
                candidate.type == "episode" &&
                    (candidate.season > current.season ||
                        candidate.season == current.season &&
                            candidate.episode > current.episodeEnd)
            }
            .minWithOrNull(
                compareBy<OfflineCatalogItem>(
                    { it.season },
                    { it.episode },
                    { it.episodeEnd },
                    { it.id },
                )
            )
            ?.id
    return OfflineEpisodeNeighborIds(previous, next)
}

typealias OfflineRequest = suspend (AuthSession, String, String, JSONObject?) -> JSONObject

internal fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

internal fun searchPattern(value: String): String =
    "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"

internal fun presentationRevision(item: JSONObject): String {
    val copy = JSONObject(item.toString()).apply { remove("userState") }
    return MessageDigest.getInstance("SHA-256")
        .digest(copy.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun offlineCatalogItem(profile: String, payload: JSONObject): OfflineCatalogItem {
    val item = catalogMediaItem(payload)
    require(item.id.isNotBlank())
    return OfflineCatalogItem(
        profile = profile,
        id = item.id,
        libraryId = item.libraryId,
        parentId = item.parentId,
        type = payload.getString("type"),
        name = item.name,
        season = payload.optInt("seasonNumber"),
        episode = payload.optInt("episodeNumber"),
        episodeEnd = payload.optInt("episodeEndNumber", payload.optInt("episodeNumber")),
        disc = item.discNumber ?: 0,
        track = item.trackNumber ?: 0,
        payload = payload.toString(),
        presentationRevision = presentationRevision(payload),
    )
}

internal data class SelectionPage(
    val id: String,
    val state: String,
    val count: Int,
    val items: List<JSONObject>,
    val metadata: List<JSONObject>,
    val next: Int?,
    val error: String?,
) {
    companion object {
        fun parse(json: JSONObject, offset: Int): SelectionPage {
            val items = (json.optJSONArray("items") ?: JSONArray()).objects()
            val metadata = (json.optJSONArray("metadata") ?: JSONArray()).objects()
            require(items.size <= 100 && metadata.size <= 1701)
            val next = if (json.isNull("nextOffset")) null else json.getInt("nextOffset")
            require(next == null || next == offset + items.size && next > offset)
            return SelectionPage(
                json.getString("id"),
                json.getString("state"),
                json.getInt("itemCount"),
                items,
                metadata,
                next,
                json.optString("errorCode").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }
}

class OfflineCatalogRepository(
    val database: OfflineDatabase,
    private val request: OfflineRequest,
) {
    val dao = database.offline()

    suspend fun requireProfile(profile: String): OfflineProfile =
        requireNotNull(dao.profile(profile)) { "Offline profile is unavailable." }
            .also { check(!it.locked) { "Sign in to unlock these downloads." } }

    suspend fun discover(session: AuthSession): Pair<OfflineProfile, JSONObject> {
        val capabilities = request(session, "/api/offline/capabilities", "GET", null)
        check(
            capabilities.optBoolean("enabled") &&
                capabilities.optString("contractVersion") == "offline-v1"
        ) {
            "This server does not support downloads."
        }
        val instance = capabilities.getString("serverInstanceId")
        val id = OfflineProfile.identity(instance, session.userId)
        val existing = dao.profile(id)
        val profile =
            existing?.copy(serverUrl = session.serverUrl, displayName = session.username)
                ?: OfflineProfile(id, instance, session.userId, session.serverUrl, session.username)
        dao.putProfile(profile)
        return profile to capabilities
    }

    suspend fun createSelection(
        session: AuthSession,
        profile: String,
        rootId: String,
        title: String,
        kind: String,
        options: JSONObject,
        language: String,
    ): OfflineSelection {
        val retained = requireProfile(profile)
        require(retained.account == session.userId && retained.serverUrl == session.serverUrl)
        val response =
            request(
                session,
                "/api/offline/selections",
                "POST",
                JSONObject().put("itemId", rootId).put("kind", kind).put("language", language),
            )
        val selection =
            OfflineSelection(
                profile,
                response.getString("id"),
                rootId,
                kind,
                title,
                options.toString(),
            )
        dao.putSelection(selection)
        return selection
    }

    /** A committed page is the restart checkpoint; no transfer starts until ready. */
    suspend fun ingestSelectionPage(
        session: AuthSession,
        profile: String,
        id: String,
    ): OfflineSelection {
        requireProfile(profile)
        val current = requireNotNull(dao.selection(profile, id))
        if (current.state != "enumerating") return current
        val page =
            SelectionPage.parse(
                request(
                    session,
                    "/api/offline/selections/$id?offset=${current.cursor}",
                    "GET",
                    null,
                ),
                current.cursor,
            )
        require(page.id == id)
        return database.withTransaction {
            val latest = requireNotNull(dao.selection(profile, id))
            if (latest.state != current.state || latest.cursor != current.cursor)
                return@withTransaction latest
            if (page.state == "ready") {
                ingestItems(profile, page.metadata + page.items)
                dao.putMembers(
                    page.items
                        .mapIndexed { index, item ->
                            OfflineMember(profile, id, item.getString("id"), current.cursor + index)
                        }
                        .distinctBy { it.item }
                )
            }
            val updated =
                current.copy(
                    state =
                        if (page.state == "ready" && page.next != null) "enumerating"
                        else page.state,
                    cursor = page.next ?: current.cursor + page.items.size,
                    itemCount = page.count,
                    error = page.error,
                )
            dao.putSelection(updated)
            updated
        }
    }

    suspend fun ingestItems(profile: String, items: List<JSONObject>) {
        require(items.size <= 1801)
        database.withTransaction {
            for (item in items.distinctBy { it.getString("id") }) {
                val row = offlineCatalogItem(profile, item)
                val previous = dao.item(profile, row.id)
                dao.putItems(
                    listOf(
                        row.copy(
                            newSourceAvailable = previous?.newSourceAvailable ?: false,
                            unavailable = previous?.unavailable ?: false,
                        )
                    )
                )
                val state = dao.state(profile, row.id) ?: OfflineUserState(profile, row.id)
                dao.putState(
                    state.copy(
                        canonical = (item.optJSONObject("userState") ?: JSONObject()).toString()
                    )
                )
                if (dao.playbackState(profile, row.id) == null) {
                    val userState = item.optJSONObject("userState") ?: JSONObject()
                    val lastPlayedAt = userState.optString("lastPlayedAt")
                    val activityMs =
                        runCatching { Instant.parse(lastPlayedAt).toEpochMilli() }.getOrDefault(0L)
                    dao.putPlaybackState(
                        OfflinePlaybackState(
                            profile = profile,
                            target = row.id,
                            positionSeconds =
                                userState.optDouble("positionSeconds", 0.0).coerceAtLeast(0.0),
                            durationSeconds =
                                userState.optDouble("durationSeconds", 0.0).coerceAtLeast(0.0),
                            played = userState.optBoolean("played", false),
                            activityMs = activityMs,
                        )
                    )
                }
            }
        }
    }

    suspend fun saveEpisodeNeighbors(profile: String, itemId: String, bundle: JSONObject) {
        if (dao.item(profile, itemId)?.type != "episode") return
        val neighbors = bundle.optJSONObject("episodeNeighbors") ?: return
        database.withTransaction {
            val current =
                dao.playbackState(profile, itemId) ?: OfflinePlaybackState(profile, itemId)
            dao.putPlaybackState(
                current.copy(
                    previousEpisodeId = neighbors.bundledEpisodeNeighbor("previous")?.id,
                    nextEpisodeId = neighbors.bundledEpisodeNeighbor("next")?.id,
                )
            )
        }
    }

    suspend fun item(profile: String, id: String): MediaItem? {
        requireProfile(profile)
        val row = dao.item(profile, id) ?: return null
        val payload = JSONObject(row.payload)
        val state = dao.state(profile, id)
        val merged = JSONObject(state?.canonical ?: "{}")
        JSONObject(state?.overlay ?: "{}").let { overlay ->
            overlay.keys().forEach { merged.put(it, overlay.get(it)) }
        }
        state?.localOnlyResume?.let { local ->
            JSONObject(local).let { value ->
                value.keys().forEach { merged.put(it, value.get(it)) }
            }
        }
        if (row.type == "episode") {
            val seriesId = dao.episodeSeriesId(profile, id)
            val seriesState = seriesId?.let { dao.state(profile, it) }
            if (seriesState != null) {
                val canonical = JSONObject(seriesState.canonical)
                val overlay = JSONObject(seriesState.overlay)
                if (canonical.has("following") || overlay.has("following"))
                    merged.put(
                        "following",
                        overlay.optBoolean("following", canonical.optBoolean("following")),
                    )
            }
        }
        payload.put("userState", merged)
        return catalogMediaItem(payload)
    }

    suspend fun search(
        profile: String,
        category: String,
        query: String,
        offset: Int = 0,
    ): List<MediaItem> {
        requireProfile(profile)
        require(category in setOf("", "movies", "tv", "music") && offset >= 0)
        return dao.browse(profile, category, searchPattern(query), 100, offset).mapNotNull {
            item(profile, it.id)
        }
    }

    suspend fun availability(profile: String, id: String): OfflineAvailability {
        requireProfile(profile)
        return dao.availability(profile, id)
    }

    /**
     * Resolves only adjacent episodes and exposes a neighbor only when its verified asset is local.
     */
    suspend fun episodeNavigation(
        profile: String,
        itemId: String,
        bundle: JSONObject,
    ): OfflineEpisodeNavigation {
        requireProfile(profile)
        if (dao.item(profile, itemId)?.type != "episode") return OfflineEpisodeNavigation()
        val persisted = offlineEpisodeNeighborIds(itemId, dao.episodesInSeries(profile, itemId))
        val bundled = bundle.optJSONObject("episodeNeighbors")
        val previousId =
            bundled.bundledEpisodeNeighbor("previous")?.id
                ?: if (bundled?.has("previous") == true) null else persisted.previous
        val nextId =
            bundled.bundledEpisodeNeighbor("next")?.id
                ?: if (bundled?.has("next") == true) null else persisted.next

        suspend fun playableNeighbor(id: String?): MediaItem? {
            if (id.isNullOrBlank() || id == itemId || dao.playable(profile, id).isEmpty())
                return null
            return item(profile, id)
        }

        val previous = playableNeighbor(previousId)
        val next = playableNeighbor(nextId)
        return OfflineEpisodeNavigation(
            previous = previous,
            next = next,
            nextUnavailable = nextId != null && next == null,
        )
    }

    fun selections(profile: String): Flow<List<OfflineSelection>> = dao.selections(profile)

    /** Stage pages under a separate key. Failed refreshes leave the usable snapshot intact. */
    suspend fun cachePlaylistPage(
        session: AuthSession,
        profile: String,
        id: String,
        language: String,
    ): Boolean {
        val retained = requireProfile(profile)
        val staging = "$id#offline-staging"
        val staged = dao.playlist(profile, staging)
        val offset = staged?.cursor ?: 0
        val response =
            request(
                session,
                "/api/offline/reconcile",
                "POST",
                JSONObject()
                    .put("serverInstanceId", retained.serverInstance)
                    .put("language", language)
                    .put(
                        "objects",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("id", id)
                                    .put("kind", "playlist")
                                    .put("offset", offset)
                            ),
                    ),
            )
        require(response.getString("serverInstanceId") == retained.serverInstance)
        val result = response.getJSONArray("objects").getJSONObject(0)
        check(result.getString("status") == "updated") { "Playlist is unavailable." }
        val version = result.getString("presentationRevision")
        val payload = result.getJSONObject("playlist")
        val entries = payload.getJSONArray("items").objects()
        require(entries.size <= 100)
        if (staged != null && staged.revision != version) {
            database.withTransaction {
                dao.clearPlaylistEntries(profile, staging)
                dao.putPlaylist(staged.copy(revision = version, cursor = 0))
            }
            return false
        }
        return database.withTransaction {
            ingestItems(profile, entries.map { it.getJSONObject("item") })
            val next = if (result.isNull("nextOffset")) null else result.getInt("nextOffset")
            require(next == null || next == offset + 100)
            val page =
                OfflinePlaylist(
                    profile,
                    staging,
                    JSONObject(payload.toString())
                        .apply {
                            remove("items")
                            remove("shareToken")
                            put(
                                "stateRevisions",
                                result.optJSONObject("stateRevisions") ?: JSONObject(),
                            )
                        }
                        .toString(),
                    version,
                    false,
                    payload.getInt("itemCount"),
                    next ?: offset + entries.size,
                )
            dao.putPlaylist(page)
            dao.putPlaylistEntries(
                entries.mapIndexed { index, entry ->
                    OfflinePlaylistEntry(
                        profile,
                        staging,
                        entry.getJSONObject("item").getString("id"),
                        entry.getString("entryId"),
                        offset + index,
                    )
                }
            )
            if (next == null) {
                check(dao.playlistEntryCount(profile, staging) == page.expectedCount) {
                    "Playlist changed during refresh. Retry its snapshot."
                }
                if (dao.hasPendingPlaylistOperations(profile, id)) {
                    dao.clearPlaylistEntries(profile, staging)
                    dao.deletePlaylist(profile, staging)
                    return@withTransaction true
                }
                dao.clearPlaylistEntries(profile, id)
                dao.publishPlaylistEntries(profile, staging, id)
                dao.putPlaylist(page.copy(id = id, complete = true))
                dao.clearPlaylistEntries(profile, staging)
                dao.deletePlaylist(profile, staging)
            }
            next == null
        }
    }
}
