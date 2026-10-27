package com.zenstream.zenstreammobile.offline

import android.net.Uri
import androidx.room.withTransaction
import com.zenstream.zenstreammobile.data.CatalogException
import com.zenstream.zenstreammobile.model.AuthSession
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Refreshes local presentation and account snapshots without changing verified media files. */
class OfflineMetadataRefreshCoordinator(
    private val catalog: OfflineCatalogRepository,
    private val storage: OfflineStorage,
    private val request: OfflineRequest,
    private val binary: OfflineBinaryRequest,
    private val loadCursor: suspend (String) -> Int,
    private val saveCursor: suspend (String, Int) -> Unit,
) {
    private val database = catalog.database
    private val dao = database.offline()
    private val refreshMutex = Mutex()
    private val playlistRefreshMutex = Mutex()

    suspend fun refresh(profileId: String, session: AuthSession, language: String) =
        refreshMutex.withLock {
            val profile = catalog.requireProfile(profileId)
            require(profile.account == session.userId)
            require(profile.serverUrl.trimEnd('/') == session.serverUrl.trimEnd('/'))
            refreshCatalogPage(profile, session, language)
            refreshAssetPage(profile, session, language)
            refreshPlaylistPage(profile, session, language)
        }

    private suspend fun refreshCatalogPage(
        profile: OfflineProfile,
        session: AuthSession,
        language: String,
    ) {
        val key = "${profile.id}:metadata-catalog"
        val offset = loadCursor(key).coerceAtLeast(0)
        val rows = dao.catalogPage(profile.id, CATALOG_PAGE_SIZE, offset)
        if (rows.isEmpty()) {
            saveCursor(key, 0)
            return
        }

        val known = JSONArray()
        rows.forEach { row ->
            val item = JSONObject().put("id", row.id).put("kind", "catalog")
            item.put("presentationRevision", row.presentationRevision)
            row.libraryId?.let { item.put("libraryId", it) }
            dao.latestCompleteAsset(profile.id, row.id)?.let { asset ->
                item.put("sourceId", asset.sourceId).put("sourceRevision", asset.sourceRevision)
            }
            known.put(item)
        }
        val response =
            request(
                session,
                "/api/offline/reconcile",
                "POST",
                JSONObject()
                    .put("serverInstanceId", profile.serverInstance)
                    .put("language", language)
                    .put("objects", known),
            )
        check(response.getString("serverInstanceId") == profile.serverInstance) {
            "The server installation changed; cached metadata cannot be refreshed from it."
        }
        val results = response.getJSONArray("objects").objects().associateBy { it.getString("id") }
        require(results.size == rows.size) { "Metadata reconciliation omitted a cached item." }
        database.withTransaction {
            rows.forEach { row ->
                val result = requireNotNull(results[row.id])
                applyCatalogResult(profile.id, row, result)
            }
        }
        saveCursor(
            key,
            if (rows.size < CATALOG_PAGE_SIZE) 0 else offset + rows.size,
        )
    }

    private suspend fun applyCatalogResult(
        profileId: String,
        previous: OfflineCatalogItem,
        result: JSONObject,
    ) {
        when (result.getString("status")) {
            "unavailable",
            "deleted" -> {
                dao.putItems(listOf(previous.copy(unavailable = true, newSourceAvailable = false)))
                dao.lockAssetsForItem(profileId, previous.id)
                dao.cancelTransfersForItem(profileId, previous.id)
                return
            }
            "unchanged",
            "updated" -> Unit
            else -> error("Server returned an unknown metadata status.")
        }

        val payload = result.optJSONObject("item")
        val sourceStatus = result.optString("sourceStatus")
        val current = dao.item(profileId, previous.id) ?: previous
        val hasDownloadedAsset = dao.latestCompleteAsset(profileId, previous.id) != null
        if (payload != null && !hasDownloadedAsset) {
            dao.putItems(
                listOf(
                    offlineCatalogItem(profileId, payload)
                        .copy(
                            presentationRevision = result.getString("presentationRevision"),
                            unavailable = false,
                            newSourceAvailable = sourceStatus == "changed",
                        )
                )
            )
        } else {
            dao.putItems(
                listOf(
                    current.copy(
                        unavailable = false,
                        newSourceAvailable =
                            when (sourceStatus) {
                                "changed" -> true
                                "unchanged" -> false
                                else -> current.newSourceAvailable
                            },
                    )
                )
            )
        }
        applyCanonicalState(
            profileId,
            previous.id,
            result.optJSONObject("state"),
            result.optJSONObject("stateRevisions"),
        )
    }

    private suspend fun refreshAssetPage(
        profile: OfflineProfile,
        session: AuthSession,
        language: String,
    ) {
        val key = "${profile.id}:metadata-assets"
        val offset = loadCursor(key).coerceAtLeast(0)
        val assets = dao.metadataAssetPage(profile.id, ASSET_PAGE_SIZE, offset)
        if (assets.isEmpty()) {
            saveCursor(key, 0)
            return
        }
        for (asset in assets) {
            try {
                refreshAssetBundle(profile, session, asset, language)
            } catch (error: CancellationException) {
                throw error
            } catch (error: CatalogException) {
                if (error.statusCode in RETRYABLE_HTTP_STATUS) throw error
                // Revoked or removed assets stay playable locally; reconcile marks their catalog
                // row.
            } catch (error: IOException) {
                throw error
            }
        }
        saveCursor(key, if (assets.size < ASSET_PAGE_SIZE) 0 else offset + assets.size)
    }

    private suspend fun refreshAssetBundle(
        profile: OfflineProfile,
        session: AuthSession,
        asset: OfflineAsset,
        fallbackLanguage: String,
    ) {
        val currentAsset = dao.asset(profile.id, asset.id) ?: return
        if (currentAsset.state != "complete" || currentAsset.locked) return
        val descriptor = JSONObject(currentAsset.descriptor)
        val options = descriptor.optJSONObject("downloadOptions")
        val language =
            options?.optString("language")?.takeIf { it.isNotBlank() } ?: fallbackLanguage
        val bundle =
            request(
                session,
                "/api/offline/assets/${Uri.encode(asset.id)}/bundle?language=${Uri.encode(language)}",
                "GET",
                null,
            )
        require(bundle.getString("itemId") == asset.item) {
            "Server returned a metadata bundle for a different media item."
        }
        val oldBundle = descriptor.optJSONObject("bundle")
        if (
            oldBundle != null &&
                oldBundle.optString("presentationRevision") ==
                    bundle.optString("presentationRevision") &&
                oldBundle.optString("supplementRevision") == bundle.optString("supplementRevision")
        )
            return

        val previousSupplements = dao.supplements(profile.id, asset.id)
        val downloaded = downloadSupplements(profile.id, session, asset.id, bundle)
        descriptor.put("bundle", bundle)
        try {
            database.withTransaction {
                val latest = requireNotNull(dao.asset(profile.id, asset.id))
                check(
                    latest.state == "complete" &&
                        !latest.locked &&
                        latest.relativePath == currentAsset.relativePath &&
                        latest.sourceRevision == currentAsset.sourceRevision
                ) {
                    "Downloaded media changed during metadata refresh."
                }
                applyBundle(profile.id, currentAsset, descriptor, bundle)
                dao.deleteSupplements(profile.id, asset.id)
                dao.putSupplements(downloaded.supplements)
                dao.putAsset(latest.copy(descriptor = descriptor.toString()))
                val neighbors = bundle.optJSONObject("episodeNeighbors") ?: return@withTransaction
                val playback =
                    dao.playbackState(profile.id, asset.item)
                        ?: OfflinePlaybackState(profile.id, asset.item)
                dao.putPlaybackState(
                    playback.copy(
                        previousEpisodeId = neighbors.bundledEpisodeNeighbor("previous")?.id,
                        nextEpisodeId = neighbors.bundledEpisodeNeighbor("next")?.id,
                    )
                )
            }
        } catch (error: Exception) {
            deleteUnreferencedFiles(profile.id, downloaded.createdPaths)
            throw error
        }
        deleteUnreferencedFiles(profile.id, previousSupplements.map { it.relativePath })
    }

    private suspend fun downloadSupplements(
        profileId: String,
        session: AuthSession,
        assetId: String,
        bundle: JSONObject,
    ): DownloadedSupplements {
        val pending = mutableListOf<Pair<OfflineSupplement, ByteArray>>()
        for ((kind, list) in
            listOf(
                "artwork" to bundle.optJSONArray("artwork"),
                "subtitle" to bundle.optJSONArray("subtitles"),
            )) {
            for (entry in list?.objects().orEmpty()) {
                val url =
                    entry.optString("url").takeIf { it.isNotBlank() && it != "null" } ?: continue
                val key =
                    if (kind == "artwork")
                        "art:${entry.optString("itemId")}:${entry.optString("type")}"
                    else "subtitle:${entry.getInt("index")}"
                val bytes = downloadBytes(session, url)
                val digest = sha256(bytes)
                if (entry.has("sha256") && entry.getString("sha256") != digest)
                    throw IOException("Supplement integrity check failed.")
                val relative =
                    storage.paths.relative(
                        profileId,
                        digest,
                        if (kind == "subtitle") "vtt" else "art",
                    )
                entry.put("localPath", relative)
                pending +=
                    (OfflineSupplement(
                        profile = profileId,
                        asset = assetId,
                        key = key,
                        relativePath = relative,
                        revision = entry.optString("revision", digest).ifBlank { digest },
                        descriptor = entry.toString(),
                    ) to bytes)
            }
        }
        val created = mutableListOf<String>()
        try {
            pending.forEach { (supplement, bytes) ->
                if (publishSupplement(supplement.relativePath, bytes))
                    created += supplement.relativePath
            }
        } catch (error: Exception) {
            deleteUnreferencedFiles(profileId, created)
            throw error
        }
        return DownloadedSupplements(pending.map { it.first }, created)
    }

    private suspend fun downloadBytes(session: AuthSession, url: String): ByteArray {
        var bytes = byteArrayOf()
        binary(session, url, emptyMap()) { response ->
            if (!response.isSuccessful)
                throw IOException("Supplement request failed (${response.code}).")
            val output = ByteArrayOutputStream()
            response.body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_SUPPLEMENT_BYTES)
                        throw IOException("Supplement is too large to cache.")
                    output.write(buffer, 0, count)
                }
            }
            bytes = output.toByteArray()
        }
        if (bytes.isEmpty()) throw IOException("Supplement response was empty.")
        return bytes
    }

    private suspend fun applyBundle(
        profileId: String,
        currentAsset: OfflineAsset,
        descriptor: JSONObject,
        bundle: JSONObject,
    ) {
        val items = bundle.optJSONArray("items") ?: JSONArray()
        val stateRevisions = bundle.optJSONObject("stateRevisions") ?: JSONObject()
        val targetPresentationRevision =
            bundle.optString("presentationRevision").takeIf { it.isNotBlank() && it != "null" }
        for (item in items.objects()) {
            val id = item.getString("id")
            applyCatalogPayload(
                profileId,
                item,
                item.optJSONObject("userState"),
                stateRevisions.optJSONObject(id),
                targetPresentationRevision.takeIf { id == currentAsset.item },
            )
        }
        descriptor.put("bundle", bundle)
        // The media representation and its verified path intentionally remain untouched.
        require(dao.asset(profileId, currentAsset.id)?.relativePath == currentAsset.relativePath)
    }

    private suspend fun applyCanonicalState(
        profileId: String,
        target: String,
        canonical: JSONObject?,
        incomingRevisions: JSONObject?,
    ) {
        if (canonical == null && incomingRevisions == null) return
        val current = dao.state(profileId, target) ?: OfflineUserState(profileId, target)
        val revisions = JSONObject(current.revisions)
        incomingRevisions?.keys()?.forEach { field ->
            revisions.put(field, incomingRevisions.get(field))
        }
        dao.putState(
            current.copy(
                canonical = canonical?.toString() ?: current.canonical,
                revisions = revisions.toString(),
            )
        )
        if (
            canonical != null &&
                current.localOnlyResume == null &&
                dao.targetOperations(profileId, target).none { it.kind in PLAYBACK_KINDS }
        )
            applyPlaybackState(profileId, target, canonical)
    }

    private suspend fun applyCatalogPayload(
        profileId: String,
        payload: JSONObject,
        canonical: JSONObject?,
        revisions: JSONObject?,
        presentationRevision: String? = null,
    ) {
        val row = offlineCatalogItem(profileId, payload)
        val previous = dao.item(profileId, row.id)
        dao.putItems(
            listOf(
                row.copy(
                    presentationRevision =
                        presentationRevision
                            ?: previous?.presentationRevision
                            ?: row.presentationRevision,
                    unavailable = previous?.unavailable ?: false,
                    newSourceAvailable = previous?.newSourceAvailable ?: false,
                )
            )
        )
        val userState = canonical ?: payload.optJSONObject("userState")
        applyCanonicalState(profileId, row.id, userState, revisions)
    }

    private suspend fun applyPlaybackState(profileId: String, target: String, state: JSONObject) {
        if (!state.has("positionSeconds") && !state.has("durationSeconds") && !state.has("played"))
            return
        val previous = dao.playbackState(profileId, target)
        val lastPlayedAt = state.optString("lastPlayedAt")
        val activityMs = runCatching { Instant.parse(lastPlayedAt).toEpochMilli() }.getOrDefault(0L)
        dao.putPlaybackState(
            OfflinePlaybackState(
                profile = profileId,
                target = target,
                positionSeconds = state.optDouble("positionSeconds", 0.0).finiteNonNegative(),
                durationSeconds = state.optDouble("durationSeconds", 0.0).finiteNonNegative(),
                played = state.optBoolean("played", false),
                activityMs = activityMs.takeIf { it > 0 } ?: previous?.activityMs ?: 0L,
                previousEpisodeId = previous?.previousEpisodeId,
                nextEpisodeId = previous?.nextEpisodeId,
            )
        )
    }

    private suspend fun publishSupplement(relative: String, bytes: ByteArray): Boolean {
        val target = storage.paths.resolve(relative)
        if (target.isFile) {
            if (
                target.length() == bytes.size.toLong() &&
                    sha256(target.readBytes()) == sha256(bytes)
            )
                return false
            throw IOException("Existing metadata asset failed its integrity check.")
        }
        check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true)
        val temporary = storage.paths.resolve(relative + "." + UUID.randomUUID() + ".part")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("Could not finalize refreshed metadata asset.")
        }
        return true
    }

    private suspend fun deleteUnreferencedFiles(profileId: String, paths: List<String>) {
        paths.distinct().forEach { relative ->
            if (dao.fileReferences(profileId, relative, "") == 0) {
                runCatching { storage.paths.resolve(relative).delete() }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun Double.finiteNonNegative(): Double = takeIf { it.isFinite() && it >= 0.0 } ?: 0.0

    private suspend fun refreshPlaylistPage(
        profile: OfflineProfile,
        session: AuthSession,
        language: String,
    ) = playlistRefreshMutex.withLock {
        val key = "${profile.id}:metadata-playlists"
        val offset = loadCursor(key).coerceAtLeast(0)
        val playlists = dao.playlistRefreshPage(profile.id, PLAYLIST_PAGE_SIZE, offset)
        if (playlists.isEmpty()) {
            saveCursor(key, 0)
            return@withLock
        }
        playlists.forEach { playlist ->
            val pending = dao.targetOperations(profile.id, playlist.id)
            if (pending.any { it.kind == "playlist_create" || it.kind == "playlist_delete" })
                return@forEach
            var complete = false
            repeat(MAX_PLAYLIST_PAGES_PER_PASS) {
                if (!complete)
                    complete = catalog.cachePlaylistPage(session, profile.id, playlist.id, language)
            }
        }
        saveCursor(
            key,
            if (playlists.size < PLAYLIST_PAGE_SIZE) 0 else offset + playlists.size,
        )
    }

    companion object {
        private const val CATALOG_PAGE_SIZE = 100
        private const val ASSET_PAGE_SIZE = 16
        private const val PLAYLIST_PAGE_SIZE = 4
        private const val MAX_PLAYLIST_PAGES_PER_PASS = 5
        private const val MAX_SUPPLEMENT_BYTES = 16 * 1024 * 1024
        private val PLAYBACK_KINDS =
            setOf("checkpoint", "complete", "watched", "unwatched", "music_play")
        private val RETRYABLE_HTTP_STATUS = setOf(401, 408, 425, 429, 500, 502, 503, 504)
    }

    private data class DownloadedSupplements(
        val supplements: List<OfflineSupplement>,
        val createdPaths: List<String>,
    )
}
