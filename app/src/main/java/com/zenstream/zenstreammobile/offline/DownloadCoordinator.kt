package com.zenstream.zenstreammobile.offline

import androidx.room.withTransaction
import com.zenstream.zenstreammobile.data.CatalogException
import com.zenstream.zenstreammobile.model.AuthSession
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.min
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

typealias OfflineBinaryRequest =
    suspend (AuthSession, String, Map<String, String>, suspend (Response) -> Unit) -> Unit

internal class RepresentationChanged :
    IOException("The server source changed. Restart this download.")

private class TransferSuspended : IOException("Download paused.")

internal data class TransferRange(val append: Boolean, val verifyOnly: Boolean = false)

internal fun validateTransferRange(
    offset: Long,
    total: Long,
    digest: String,
    status: Int,
    etag: String?,
    contentRange: String?,
): TransferRange {
    if (status == 416 && offset == total) return TransferRange(true, verifyOnly = true)
    if (status !in setOf(200, 206)) throw IOException("Download request failed ($status).")
    if (etag != "\"$digest\"") throw RepresentationChanged()
    if (status == 200) return TransferRange(false)
    val range =
        Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(contentRange.orEmpty())
            ?: throw RepresentationChanged()
    if (
        range.groupValues[1].toLongOrNull() != offset ||
            range.groupValues[2].toLongOrNull() != total - 1 ||
            range.groupValues[3].toLongOrNull() != total
    )
        throw RepresentationChanged()
    return TransferRange(true)
}

internal fun transferIdentity(profile: String, selection: String, item: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$profile\u0000$selection\u0000$item".toByteArray())
        .joinToString("") { "%02x".format(it) }

class DownloadCoordinator(
    private val catalog: OfflineCatalogRepository,
    private val storage: OfflineStorage,
    private val request: OfflineRequest,
    private val binary: OfflineBinaryRequest,
    private val activeSession: suspend () -> AuthSession?,
    private val sourceDetails: (suspend (AuthSession, String) -> JSONObject)? = null,
) {
    private val database = catalog.database
    private val dao = database.offline()
    private val runMutex = Mutex()

    private data class AssetMutex(val mutex: Mutex = Mutex(), var users: Int = 0)

    private val assetLocks = mutableMapOf<String, AssetMutex>()

    private suspend fun withAssetLock(key: String, block: suspend () -> Unit) {
        val lock =
            synchronized(assetLocks) {
                assetLocks.getOrPut(key) { AssetMutex() }.also { it.users++ }
            }
        try {
            lock.mutex.withLock { block() }
        } finally {
            synchronized(assetLocks) {
                lock.users--
                if (lock.users == 0) assetLocks.remove(key)
            }
        }
    }

    private suspend fun saveActive(candidate: OfflineTransfer, allowSkip: Boolean = false) {
        database.withTransaction {
            val latest = dao.transfer(candidate.profile, candidate.id) ?: throw TransferSuspended()
            if (latest.state in setOf("paused", "cancelled")) {
                if (allowSkip) return@withTransaction
                throw TransferSuspended()
            }
            dao.putTransfer(candidate)
        }
    }

    /** All Android execution mechanisms call this same persistent queue. */
    suspend fun runOnce(session: AuthSession, profileId: String): Boolean = runMutex.withLock {
        val profile = catalog.requireProfile(profileId)
        checkSession(profile, session)
        val capabilities = request(session, "/api/offline/capabilities", "GET", null)
        if (capabilities.optString("serverInstanceId") != profile.serverInstance)
            throw RepresentationChanged()
        for (selection in dao.cleanupSelections(profileId)) {
            try {
                request(session, "/api/offline/selections/${selection.id}", "DELETE", null)
            } catch (error: CatalogException) {
                if (error.statusCode != 404) throw error
            }
            database.withTransaction {
                dao.deleteSelectionTransfers(profileId, selection.id)
                dao.deleteSelection(profileId, selection.id)
            }
        }
        var worked = false
        for (selection in dao.playlistMetadataWork(profileId)) {
            try {
                val complete =
                    catalog.cachePlaylistPage(
                        session,
                        profileId,
                        selection.rootId,
                        JSONObject(selection.options).optString("language", "en"),
                    )
                if (complete && selection.state == "metadata_only")
                    database.withTransaction {
                        val latest = dao.selection(profileId, selection.id)
                        if (latest?.state == "metadata_only")
                            dao.putSelection(latest.copy(state = "metadata_complete"))
                    }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (
                    error is CatalogException &&
                        error.statusCode in setOf(401, 429, 500, 502, 503, 504)
                )
                    throw error
                if (error is IOException) throw error
                database.withTransaction {
                    val latest = dao.selection(profileId, selection.id)
                    if (latest?.state == selection.state)
                        dao.putSelection(
                            latest.copy(
                                error = "playlist_metadata_unavailable",
                                state =
                                    if (selection.state == "metadata_only") "metadata_failed"
                                    else selection.state,
                            )
                        )
                }
            }
            worked = true
        }
        for (selection in dao.pendingSelections(profileId).take(3)) {
            catalog.ingestSelectionPage(session, profileId, selection.id)
            worked = true
        }
        for (selection in dao.queueableSelections(profileId)) {
            queuePage(selection)
            worked = true
        }
        val pending = dao.pendingTransfers(profileId, System.currentTimeMillis(), 2)
        coroutineScope {
            pending
                .map { transfer -> async(Dispatchers.IO) { process(session, profile, transfer) } }
                .awaitAll()
        }
        worked || pending.isNotEmpty()
    }

    private suspend fun queuePage(selection: OfflineSelection) {
        val offset = if (selection.state == "ready") 0 else selection.cursor
        val members = dao.members(selection.profile, selection.id, 100, offset)
        val priority = JSONObject(selection.options).optString("priorityItem")
        database.withTransaction {
            val latest = dao.selection(selection.profile, selection.id) ?: return@withTransaction
            if (latest.state != selection.state || latest.cursor != selection.cursor)
                return@withTransaction
            for (member in members) {
                val id = transferIdentity(selection.profile, selection.id, member.item)
                if (dao.transfer(selection.profile, id) == null) {
                    dao.putTransfer(
                        OfflineTransfer(
                            selection.profile,
                            id,
                            selection.id,
                            member.item,
                            selection.options,
                            priority =
                                if (priority == member.item) Int.MAX_VALUE
                                else Int.MAX_VALUE - 1 - member.ordinal,
                        )
                    )
                }
            }
            dao.putSelection(
                selection.copy(
                    state = if (members.size == 100) "queueing" else "queued",
                    cursor = offset + members.size,
                )
            )
        }
    }

    private suspend fun checkSession(profile: OfflineProfile, expected: AuthSession) {
        currentCoroutineContext().ensureActive()
        OfflineNetworkPolicy.requireNetwork()
        val current = activeSession()
        if (
            current == null ||
                current.userId != expected.userId ||
                current.serverUrl != expected.serverUrl ||
                profile.account != current.userId ||
                profile.serverUrl != current.serverUrl ||
                dao.profile(profile.id)?.locked != false
        ) {
            throw TransferSuspended()
        }
    }

    private suspend fun checkTransfer(profile: OfflineProfile, session: AuthSession, id: String) {
        checkSession(profile, session)
        if (dao.transfer(profile.id, id)?.state in setOf(null, "paused", "cancelled"))
            throw TransferSuspended()
    }

    private suspend fun process(
        session: AuthSession,
        profile: OfflineProfile,
        original: OfflineTransfer,
    ) {
        try {
            checkTransfer(profile, session, original.id)
            val requestedOptions = JSONObject(original.options)
            if (requestedOptions.optBoolean("skipExisting")) {
                for (asset in dao.playable(profile.id, original.item)) {
                    val savedOptions = JSONObject(asset.descriptor).optJSONObject("downloadOptions")
                    if (
                        asset.quality != requestedOptions.optString("profile", "original") ||
                            !sameDownloadChoices(requestedOptions, savedOptions ?: JSONObject())
                    )
                        continue
                    if (storage.adoptCompleted(profile.id, asset.id, original.id, asset.sha256)) {
                        return
                    }
                }
            }
            val descriptor = resolve(session, original)
            val state = descriptor.getString("state")
            if (state in setOf("queued", "preparing")) {
                saveActive(
                    original.copy(
                        state = "preparing",
                        assetId = descriptor.getString("id"),
                        descriptor = descriptor.toString(),
                        nextAttemptMs = System.currentTimeMillis() + 3000,
                    )
                )
                return
            }
            check(state == "ready") {
                descriptor.optString("errorCode", "Preparation failed. Retry this download.")
            }
            val id = descriptor.getString("id")
            val size = descriptor.getLong("sizeBytes")
            val digest = descriptor.getString("sha256")
            require(
                size >= 0 &&
                    Regex("[a-f0-9]{64}").matches(id) &&
                    Regex("[a-f0-9]{64}").matches(digest)
            )
            withAssetLock("${profile.id}:$id") {
                if (storage.adoptCompleted(profile.id, id, original.id, digest))
                    return@withAssetLock
                val part = storage.staging(profile.id, id)
                check(part.parentFile!!.isDirectory || part.parentFile!!.mkdirs())
                var current =
                    original.copy(
                        state = "transferring",
                        assetId = id,
                        descriptor = descriptor.toString(),
                        bytes = part.takeIf { it.isFile }?.length() ?: 0,
                        total = size,
                        etag = "\"$digest\"",
                    )
                if (current.bytes > size) {
                    check(part.delete())
                    current = current.copy(bytes = 0)
                }
                saveActive(current)
                current = storage.reserve(profile.id, original.id, size)
                val headers = buildMap {
                    if (current.bytes > 0) {
                        put("Range", "bytes=${current.bytes}-")
                        put("If-Range", "\"$digest\"")
                    }
                }
                binary(session, descriptor.getString("contentUrl"), headers) { response ->
                    val range =
                        validateTransferRange(
                            current.bytes,
                            size,
                            digest,
                            response.code,
                            response.header("ETag"),
                            response.header("Content-Range"),
                        )
                    if (!range.verifyOnly) {
                        if (!range.append) {
                            FileOutputStream(part, false).close()
                            current = current.copy(bytes = 0)
                            saveActive(current)
                            current = storage.reserve(profile.id, original.id, size)
                        }
                        var written = current.bytes
                        var checkpoint = written
                        var checkpointTime = System.currentTimeMillis()
                        FileOutputStream(part, range.append).use { output ->
                            response.body.byteStream().use { input ->
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    if (!OfflineNetworkPolicy.allowed) throw TransferSuspended()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (written > size - count) throw RepresentationChanged()
                                    output.write(buffer, 0, count)
                                    written += count
                                    if (
                                        written - checkpoint >= 1024 * 1024 ||
                                            System.currentTimeMillis() - checkpointTime >= 1000
                                    ) {
                                        checkTransfer(profile, session, original.id)
                                        val latest =
                                            requireNotNull(dao.transfer(profile.id, original.id))
                                        saveActive(
                                            latest.copy(bytes = written, reserved = size - written)
                                        )
                                        checkpoint = written
                                        checkpointTime = System.currentTimeMillis()
                                    }
                                }
                            }
                            output.fd.sync()
                        }
                        checkTransfer(profile, session, original.id)
                        saveActive(
                            requireNotNull(dao.transfer(profile.id, original.id))
                                .copy(bytes = written, reserved = size - written)
                        )
                        if (written != size)
                            throw IOException(
                                "Download interrupted; its verified representation can resume."
                            )
                    }
                }
                checkTransfer(profile, session, original.id)
                saveActive(
                    requireNotNull(dao.transfer(profile.id, original.id)).copy(state = "verifying")
                )
                if (sha256(part) != digest) {
                    part.delete()
                    throw RepresentationChanged()
                }
                val bundle =
                    supplementary(
                        session,
                        profile.id,
                        original.item,
                        id,
                        descriptor,
                        JSONObject(original.options),
                    )
                val combined =
                    JSONObject(descriptor.toString())
                        .put("bundle", bundle)
                        .put("downloadOptions", JSONObject(original.options))
                checkTransfer(profile, session, original.id)
                saveActive(
                    requireNotNull(dao.transfer(profile.id, original.id)).copy(state = "publishing")
                )
                storage.publish(
                    OfflineAsset(
                        profile.id,
                        id,
                        original.item,
                        descriptor.getString("profile"),
                        descriptor.getString("sourceId"),
                        descriptor.getString("sourceRevision"),
                        size,
                        digest,
                        descriptor.getString("mimeType"),
                        combined.toString(),
                        "",
                    ),
                    original.id,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: TransferSuspended) {
            // Explicit pause/cancel, offline mode, logout and account switching keep their state.
        } catch (error: Exception) {
            val latest = dao.transfer(profile.id, original.id) ?: return
            if (latest.state in setOf("paused", "cancelled")) return
            val terminal =
                error is RepresentationChanged ||
                    error is IllegalArgumentException ||
                    error is IllegalStateException ||
                    error is CatalogException && error.statusCode in setOf(400, 403, 404, 409, 422)
            val auth = error is CatalogException && error.statusCode == 401
            saveActive(
                latest.copy(
                    state = if (terminal) "failed" else if (auth) "paused" else "queued",
                    attempt = latest.attempt + 1,
                    nextAttemptMs =
                        System.currentTimeMillis() +
                            min(300000, 1000L shl min(latest.attempt + 1, 8)),
                    error =
                        when {
                            error is RepresentationChanged -> "source_changed"
                            auth -> "authentication_required"
                            else ->
                                error.message?.take(160) ?: "Download failed. Retry when connected."
                        },
                    reserved = if (terminal || auth) 0 else latest.reserved,
                ),
                allowSkip = true,
            )
        }
    }

    private suspend fun resolve(session: AuthSession, transfer: OfflineTransfer): JSONObject {
        if (transfer.assetId != null) {
            try {
                return request(session, "/api/offline/assets/${transfer.assetId}", "GET", null)
            } catch (error: CatalogException) {
                if (error.statusCode != 404) throw error
            }
        }
        var options = JSONObject(transfer.options)
        if (options.has("audioLanguage") || options.has("subtitleLanguages")) {
            options =
                languageSelections(requireNotNull(sourceDetails)(session, transfer.item), options)
        }
        val body =
            JSONObject()
                .put("selectionId", transfer.selection)
                .put("itemId", transfer.item)
                .put("profile", options.optString("profile", "original"))
        for (key in
            listOf("sourceId", "audioStreamId", "subtitleStreamIndices", "includeAllTracks")) {
            if (options.has(key) && !options.isNull(key)) body.put(key, options.get(key))
        }
        val result = request(session, "/api/offline/assets", "POST", body)
        if (transfer.assetId != null && transfer.assetId != result.getString("id"))
            throw RepresentationChanged()
        return result
    }

    private suspend fun supplementary(
        session: AuthSession,
        profile: String,
        item: String,
        asset: String,
        descriptor: JSONObject,
        options: JSONObject,
    ): JSONObject {
        val warnings = JSONArray()
        val language = options.optString("language", "en")
        val bundle =
            try {
                request(
                    session,
                    descriptor.getString("bundleUrl") + "?language=$language",
                    "GET",
                    null,
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                return JSONObject().put("warnings", warnings.put("metadata_refresh_needed"))
            }
        val items = (bundle.optJSONArray("items") ?: JSONArray()).objects()
        catalog.ingestItems(profile, items)
        catalog.saveEpisodeNeighbors(profile, item, bundle)
        val supplements = mutableListOf<OfflineSupplement>()
        for ((kind, list) in
            listOf(
                "artwork" to bundle.optJSONArray("artwork"),
                "subtitle" to bundle.optJSONArray("subtitles"),
            )) {
            for (entry in list?.objects().orEmpty()) {
                if (!entry.has("url")) continue
                try {
                    var bytes = byteArrayOf()
                    binary(session, entry.getString("url"), emptyMap()) { response ->
                        if (!response.isSuccessful) throw IOException("Supplement is unavailable.")
                        val data = ByteArrayOutputStream()
                        response.body.byteStream().use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (data.size() + count > 16 * 1024 * 1024)
                                    throw IOException("Supplement is too large.")
                                data.write(buffer, 0, count)
                            }
                        }
                        bytes = data.toByteArray()
                    }
                    val digest =
                        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                            "%02x".format(it)
                        }
                    if (entry.has("sha256") && entry.getString("sha256") != digest)
                        throw IOException("Supplement integrity failed.")
                    val relative =
                        storage.paths.relative(
                            profile,
                            digest,
                            if (kind == "subtitle") "vtt" else "art",
                        )
                    val target = storage.paths.resolve(relative)
                    check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                    if (!target.exists()) {
                        val temporary =
                            storage.paths.resolve(
                                relative +
                                    "." +
                                    java.util.UUID.randomUUID().toString().replace("-", "") +
                                    ".part"
                            )
                        FileOutputStream(temporary).use { output ->
                            output.write(bytes)
                            output.fd.sync()
                        }
                        check(temporary.renameTo(target))
                    }
                    entry.put("localPath", relative)
                    val key =
                        if (kind == "artwork")
                            "art:${entry.optString("itemId", item)}:${entry.optString("type")}"
                        else "subtitle:${entry.getInt("index")}"
                    supplements +=
                        OfflineSupplement(
                            profile,
                            asset,
                            key,
                            relative,
                            entry.optString("revision", digest),
                            entry.toString(),
                        )
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    warnings.put("${kind}_refresh_needed")
                }
            }
        }
        dao.putSupplements(supplements)
        bundle.put("downloadWarnings", warnings)
        return bundle
    }

    suspend fun pause(profile: String, id: String) {
        val current = dao.transfer(profile, id) ?: return
        if (current.state != "complete")
            dao.putTransfer(current.copy(state = "paused", reserved = 0))
    }

    suspend fun retry(profile: String, id: String) {
        val current = dao.transfer(profile, id) ?: return
        if (current.state == "complete") return
        val reset = current.error == "source_changed"
        if (reset && current.assetId != null) storage.staging(profile, current.assetId).delete()
        dao.putTransfer(
            current.copy(
                state = "queued",
                error = null,
                attempt = 0,
                nextAttemptMs = 0,
                assetId = if (reset) null else current.assetId,
                descriptor = if (reset) null else current.descriptor,
                bytes = if (reset) 0 else current.bytes,
            )
        )
    }

    suspend fun cancel(profile: String, id: String) {
        val current = dao.transfer(profile, id) ?: return
        if (current.state != "complete")
            dao.putTransfer(current.copy(state = "cancelled", reserved = 0))
    }
}
