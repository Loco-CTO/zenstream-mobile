package com.zenstream.zenstreammobile.offline

import androidx.room.withTransaction
import com.zenstream.zenstreammobile.model.MediaItem
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores private playlist edits locally and queues their matching account operations atomically.
 */
class OfflinePlaylistRepository(
    private val database: OfflineDatabase,
    private val operationWriter: OfflinePlaybackStateRepository,
    private val catalog: OfflineCatalogRepository,
) {
    private val dao = database.offline()

    fun playlists(profileId: String): Flow<List<OfflinePlaylist>> = dao.playlists(profileId)

    suspend fun playlist(profileId: String, playlistId: String): OfflinePlaylist? {
        requireProfile(profileId)
        return dao.playlist(profileId, playlistId)?.takeUnless { it.deleted }
    }

    suspend fun entries(
        profileId: String,
        playlistId: String,
        limit: Int = PAGE_SIZE,
        offset: Int = 0,
    ): List<OfflinePlaylistEntry> {
        requireProfile(profileId)
        require(limit in 1..MAX_ENTRIES && offset >= 0)
        return dao.playlistEntries(profileId, playlistId, limit, offset)
    }

    suspend fun knownTracks(
        profileId: String,
        query: String = "",
        limit: Int = PAGE_SIZE,
        offset: Int = 0,
    ): List<MediaItem> {
        requireProfile(profileId)
        require(limit in 1..PAGE_SIZE && offset >= 0)
        return dao.knownTracks(profileId, searchPattern(query), limit, offset).mapNotNull {
            catalog.item(profileId, it.id)
        }
    }

    suspend fun knownMusicGroups(
        profileId: String,
        query: String = "",
        limit: Int = PAGE_SIZE,
        offset: Int = 0,
    ): List<MediaItem> {
        requireProfile(profileId)
        require(limit in 1..PAGE_SIZE && offset >= 0)
        return dao.knownMusicGroups(profileId, searchPattern(query), limit, offset).mapNotNull {
            catalog.item(profileId, it.id)
        }
    }

    /** Returns up to 100 locally known tracks; larger or incomplete groups remain partial. */
    suspend fun knownTracksFromGroup(profileId: String, sourceId: String): List<MediaItem> {
        requireProfile(profileId)
        val sourceRow =
            requireNotNull(dao.item(profileId, sourceId)) { "Music group is not saved locally." }
        require(sourceRow.type in setOf("artist", "release")) { "Choose an album or artist." }
        val matches = ArrayList<MediaItem>(GROUP_ADD_LIMIT)
        var offset = 0
        var scanned = 0
        while (scanned < MAX_SOURCE_SCAN && matches.size < GROUP_ADD_LIMIT) {
            val rows = dao.knownTracks(profileId, "%", PAGE_SIZE, offset)
            if (rows.isEmpty()) break
            for (row in rows) {
                val track = catalog.item(profileId, row.id) ?: continue
                val belongs =
                    if (sourceRow.type == "release") {
                        track.albumId == sourceId || track.parentId == sourceId
                    } else {
                        track.artistId == sourceId ||
                            track.artistCredits.any { credit -> credit.id == sourceId }
                    }
                if (belongs) matches.add(track)
                if (matches.size >= GROUP_ADD_LIMIT) break
            }
            offset += rows.size
            scanned += rows.size
        }
        return matches.distinctBy { it.id }
    }

    suspend fun addKnownTracksFromGroup(
        profileId: String,
        playlistId: String,
        sourceId: String,
    ): Int =
        addTracks(profileId, playlistId, knownTracksFromGroup(profileId, sourceId).map { it.id })

    suspend fun create(profileId: String, name: String, description: String? = null): String {
        val normalizedName = validateName(name)
        val normalizedDescription = validateDescription(description)
        val id = UUID.randomUUID().toString()
        database.withTransaction {
            requireProfile(profileId)
            val stateRevisions =
                JSONObject()
                    .put("name", revision(1))
                    .put("description", revision(1))
                    .put("create", revision(1))
                    .put("membership", revision(0))
                    .put("order", revision(0))
                    .put("deleted", revision(0))
            val metadata =
                JSONObject()
                    .put("id", id)
                    .put("name", normalizedName)
                    .put("description", normalizedDescription ?: JSONObject.NULL)
                    .put("isPrivate", true)
                    .put("isOwner", true)
                    .put("itemCount", 0)
                    .put("createdAt", Instant.now().toString())
                    .put("updatedAt", Instant.now().toString())
                    .put("stateRevisions", stateRevisions)
            dao.putPlaylist(
                OfflinePlaylist(
                    profile = profileId,
                    id = id,
                    metadata = metadata.toString(),
                    complete = true,
                    expectedCount = 0,
                )
            )
            operationWriter.appendAccountOperation(
                profileId,
                id,
                "playlist_create",
                baseRevision = 0,
                payload =
                    JSONObject()
                        .put("name", normalizedName)
                        .put("description", normalizedDescription ?: JSONObject.NULL),
            )
        }
        return id
    }

    suspend fun rename(profileId: String, playlistId: String, name: String): Boolean =
        updateScalar(profileId, playlistId, "name", "playlist_name", validateName(name))

    suspend fun updateDescription(
        profileId: String,
        playlistId: String,
        description: String?,
    ): Boolean =
        updateScalar(
            profileId,
            playlistId,
            "description",
            "playlist_description",
            validateDescription(description),
        )

    suspend fun updateDetails(
        profileId: String,
        playlistId: String,
        name: String,
        description: String?,
    ): Boolean = database.withTransaction {
        val row = editablePlaylist(profileId, playlistId)
        val normalizedName = validateName(name)
        val normalizedDescription = validateDescription(description)
        val metadata = JSONObject(row.metadata)
        val oldName = metadata.optString("name")
        val oldDescription =
            if (metadata.isNull("description")) null
            else metadata.optString("description").ifBlank { null }
        if (oldName == normalizedName && oldDescription == normalizedDescription)
            return@withTransaction false

        if (oldName != normalizedName) {
            val baseRevision = fieldRevision(metadata, "name")
            metadata.put("name", normalizedName)
            bumpRevision(metadata, "name")
            operationWriter.appendAccountOperation(
                profileId,
                playlistId,
                "playlist_name",
                baseRevision,
                JSONObject().put("value", normalizedName),
            )
        }
        if (oldDescription != normalizedDescription) {
            val baseRevision = fieldRevision(metadata, "description")
            metadata.put("description", normalizedDescription ?: JSONObject.NULL)
            bumpRevision(metadata, "description")
            operationWriter.appendAccountOperation(
                profileId,
                playlistId,
                "playlist_description",
                baseRevision,
                JSONObject().put("value", normalizedDescription ?: JSONObject.NULL),
            )
        }
        metadata.put("updatedAt", Instant.now().toString())
        dao.putPlaylist(row.copy(metadata = metadata.toString()))
        true
    }

    suspend fun delete(profileId: String, playlistId: String): Boolean = database.withTransaction {
        val row = editablePlaylist(profileId, playlistId)
        val metadata = JSONObject(row.metadata)
        val baseRevision = fieldRevision(metadata, "deleted")
        metadata.put("itemCount", 0).put("updatedAt", Instant.now().toString())
        bumpRevision(metadata, "deleted")
        dao.clearPlaylistEntries(profileId, playlistId)
        dao.putPlaylist(row.copy(metadata = metadata.toString(), expectedCount = 0, deleted = true))
        operationWriter.appendAccountOperation(
            profileId,
            playlistId,
            "playlist_delete",
            baseRevision,
            JSONObject(),
        )
        true
    }

    /**
     * Adds only tracks whose catalog metadata already exists locally; media files are untouched.
     */
    suspend fun addTracks(
        profileId: String,
        playlistId: String,
        trackIds: List<String>,
    ): Int = database.withTransaction {
        require(trackIds.size <= MAX_BATCH) { "Add at most $MAX_BATCH tracks at a time." }
        val ids = trackIds.distinct()
        val row = editablePlaylist(profileId, playlistId)
        val existing = dao.playlistEntries(profileId, playlistId, MAX_ENTRIES, 0)
        val existingIds = existing.mapTo(HashSet()) { it.track }
        val known = ids.map { id ->
            val item = requireNotNull(dao.item(profileId, id)) { "Track is not saved locally." }
            require(item.type == "track") { "Only music tracks can be added." }
            item
        }
        val newTracks = known.filterNot { it.id in existingIds }
        require(existing.size + newTracks.size <= MAX_ENTRIES) {
            "Playlist is too large to reorder on this device."
        }
        val metadata = JSONObject(row.metadata)
        var position = existing.size
        for (track in newTracks) {
            val entryId = UUID.randomUUID().toString()
            val field = "member:${track.id}"
            val baseRevision = fieldRevision(metadata, field)
            dao.putPlaylistEntries(
                listOf(
                    OfflinePlaylistEntry(
                        profile = profileId,
                        playlist = playlistId,
                        track = track.id,
                        entryId = entryId,
                        position = position++,
                    )
                )
            )
            operationWriter.appendAccountOperation(
                profileId,
                playlistId,
                "playlist_add",
                baseRevision,
                JSONObject().put("trackId", track.id),
                operationId = entryId,
            )
            bumpRevision(metadata, field)
            existingIds.add(track.id)
        }
        if (newTracks.isNotEmpty()) {
            val count = existing.size + newTracks.size
            metadata.put("itemCount", count).put("updatedAt", Instant.now().toString())
            dao.putPlaylist(
                row.copy(metadata = metadata.toString(), expectedCount = count, complete = true)
            )
        }
        newTracks.size
    }

    suspend fun removeTrack(profileId: String, playlistId: String, trackId: String): Boolean =
        database.withTransaction {
            val row = editablePlaylist(profileId, playlistId)
            val oldEntries = dao.playlistEntries(profileId, playlistId, MAX_ENTRIES, 0)
            if (oldEntries.none { it.track == trackId }) return@withTransaction false
            val metadata = JSONObject(row.metadata)
            val field = "member:$trackId"
            val baseRevision = fieldRevision(metadata, field)
            dao.deletePlaylistEntry(profileId, playlistId, trackId)
            val updated = oldEntries.filterNot { it.track == trackId }
            dao.putPlaylistEntries(
                updated.mapIndexed { index, entry -> entry.copy(position = index) }
            )
            metadata.put("itemCount", updated.size).put("updatedAt", Instant.now().toString())
            bumpRevision(metadata, field)
            dao.putPlaylist(
                row.copy(
                    metadata = metadata.toString(),
                    expectedCount = updated.size,
                    complete = true,
                )
            )
            operationWriter.appendAccountOperation(
                profileId,
                playlistId,
                "playlist_remove",
                baseRevision,
                JSONObject().put("trackId", trackId),
            )
            true
        }

    suspend fun moveTrack(
        profileId: String,
        playlistId: String,
        trackId: String,
        destination: Int,
    ): Boolean = database.withTransaction {
        val row = editablePlaylist(profileId, playlistId)
        val current = dao.playlistEntries(profileId, playlistId, MAX_ENTRIES, 0)
        val source = current.indexOfFirst { it.track == trackId }
        if (source < 0 || current.isEmpty()) return@withTransaction false
        val target = destination.coerceIn(0, current.lastIndex)
        if (source == target) return@withTransaction false
        val ordered = current.toMutableList().apply { add(target, removeAt(source)) }
        val metadata = JSONObject(row.metadata)
        val baseRevision = fieldRevision(metadata, "order")
        dao.putPlaylistEntries(ordered.mapIndexed { index, entry -> entry.copy(position = index) })
        metadata.put("updatedAt", Instant.now().toString())
        bumpRevision(metadata, "order")
        dao.putPlaylist(row.copy(metadata = metadata.toString()))
        operationWriter.appendAccountOperation(
            profileId,
            playlistId,
            "playlist_order",
            baseRevision,
            JSONObject().put("trackIds", JSONArray(ordered.map { it.track })),
        )
        true
    }

    private suspend fun updateScalar(
        profileId: String,
        playlistId: String,
        field: String,
        kind: String,
        value: String?,
    ): Boolean = database.withTransaction {
        val row = editablePlaylist(profileId, playlistId)
        val metadata = JSONObject(row.metadata)
        val oldValue =
            if (metadata.isNull(field)) null else metadata.optString(field).ifBlank { null }
        if (oldValue == value) return@withTransaction false
        val baseRevision = fieldRevision(metadata, field)
        metadata.put(field, value ?: JSONObject.NULL).put("updatedAt", Instant.now().toString())
        bumpRevision(metadata, field)
        dao.putPlaylist(row.copy(metadata = metadata.toString()))
        operationWriter.appendAccountOperation(
            profileId,
            playlistId,
            kind,
            baseRevision,
            JSONObject().put("value", value ?: JSONObject.NULL),
        )
        true
    }

    private suspend fun editablePlaylist(profileId: String, playlistId: String): OfflinePlaylist {
        requireProfile(profileId)
        val row = requireNotNull(dao.playlist(profileId, playlistId)) { "Playlist is unavailable." }
        require(!row.deleted) { "Playlist was deleted." }
        require(row.complete) { "Save the complete playlist membership before editing." }
        require(dao.playlistEntryCount(profileId, playlistId) == row.expectedCount) {
            "Playlist membership is incomplete. Save it again before editing."
        }
        require(row.expectedCount <= MAX_ENTRIES) { "Playlist is too large to edit offline." }
        return row
    }

    private suspend fun requireProfile(profileId: String): OfflineProfile =
        requireNotNull(dao.profile(profileId)) { "Offline profile is unavailable." }
            .also { check(!it.locked) { "Sign in to unlock these downloads." } }

    private fun validateName(value: String): String =
        value.trim().also { require(it.isNotEmpty() && it.length <= 100) }

    private fun validateDescription(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }?.also { require(it.length <= 500) }

    private fun fieldRevision(metadata: JSONObject, field: String): Long {
        val revision = metadata.optJSONObject("stateRevisions")?.opt(field)
        return when (revision) {
            is JSONObject -> revision.optLong("revision")
            is Number -> revision.toLong()
            else -> 0L
        }
    }

    private fun bumpRevision(metadata: JSONObject, field: String) {
        val revisions = metadata.optJSONObject("stateRevisions") ?: JSONObject()
        revisions.put(field, revision(fieldRevision(metadata, field) + 1))
        metadata.put("stateRevisions", revisions)
    }

    private fun revision(value: Long) = JSONObject().put("revision", value)

    companion object {
        const val PAGE_SIZE = 100
        private const val MAX_BATCH = 100
        private const val MAX_ENTRIES = 10_000
        private const val GROUP_ADD_LIMIT = 100
        private const val MAX_SOURCE_SCAN = 10_000
    }
}
