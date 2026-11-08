package com.zenstream.zenstreammobile.offline

import android.net.Uri
import androidx.room.withTransaction
import com.zenstream.zenstreammobile.model.AuthSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class MediaUpdatePreview(
    val asset: OfflineAsset,
    val sources: List<JSONObject>,
    val profiles: List<String>,
    val hdrToneMapping: Boolean = false,
) {
    val source: JSONObject?
        get() = sources.firstOrNull { it.optString("id") == asset.sourceId }

    fun availableProfiles(source: JSONObject?): List<String> {
        val streams = source?.optJSONArray("streams")?.objects().orEmpty()
        val hdr = streams.any {
            it.optString("color_transfer") in setOf("smpte2084", "arib-std-b67")
        }
        val bitmap = streams.any {
            streamType(it) == "subtitle" && it.optString("codec_name") !in downloadableTextSubtitles
        }
        return profiles.filter {
            it == "original" || !bitmap && (!it.startsWith("video-") || !hdr || hdrToneMapping)
        }
    }
}

/** Update intent lives in transfer options so preparation and replacement survive process death. */
class OfflineMediaUpdates(
    private val catalog: OfflineCatalogRepository,
    private val request: OfflineRequest,
) {
    private val dao = catalog.database.offline()
    private val mutex = Mutex()

    internal suspend fun preview(
        session: AuthSession,
        profile: String,
        assetId: String,
    ): MediaUpdatePreview {
        val retained = catalog.requireProfile(profile)
        require(retained.account == session.userId && retained.serverUrl == session.serverUrl)
        val asset = requireNotNull(dao.asset(profile, assetId))
        check(asset.state == "complete" && !asset.locked)
        val capabilities = request(session, "/api/offline/capabilities", "GET", null)
        check(capabilities.getString("serverInstanceId") == retained.serverInstance)
        val bundle =
            request(
                session,
                "/api/offline/items/${Uri.encode(asset.item)}/bundle?sourceId=${Uri.encode(asset.sourceId)}&sourceRevision=${Uri.encode(asset.sourceRevision)}",
                "GET",
                null,
            )
        val spec = DownloadSpec(asset.item, "", type = dao.item(profile, asset.item)?.type)
        return MediaUpdatePreview(
            asset,
            bundle.optJSONArray("availableSources")?.objects().orEmpty(),
            downloadProfiles(spec, capabilities),
            capabilities.optBoolean("hdrToneMapping"),
        )
    }

    internal suspend fun enqueue(
        session: AuthSession,
        preview: MediaUpdatePreview,
        source: JSONObject,
        quality: String,
        automatic: Boolean,
    ): Boolean = mutex.withLock {
        val asset = preview.asset
        val retained = catalog.requireProfile(asset.profile)
        require(retained.account == session.userId && retained.serverUrl == session.serverUrl)
        require(quality in preview.availableProfiles(source))
        require(
            preview.sources.any {
                it.getString("id") == source.getString("id") &&
                    it.getString("revision") == source.getString("revision")
            }
        )
        if (automatic) require(source.getString("id") == asset.sourceId && quality == asset.quality)
        val revision = source.getString("revision")
        if (
            source.getString("id") == asset.sourceId &&
                revision == asset.sourceRevision &&
                quality == asset.quality
        )
            return@withLock false
        val id =
            transferIdentity(
                asset.profile,
                "update:${asset.id}:${source.getString("id")}:$quality",
                revision,
            )
        catalog.database.withTransaction {
            val current = dao.asset(asset.profile, asset.id) ?: return@withTransaction false
            if (current.state != "complete" || current.locked) return@withTransaction false
            val owner =
                dao.assetSelections(asset.profile, asset.id).firstOrNull {
                    dao.selection(asset.profile, it)?.state != "removal_pending"
                } ?: return@withTransaction false
            val existing = dao.transfer(asset.profile, id)
            if (existing != null) {
                if (!automatic && existing.state in setOf("failed", "cancelled", "paused")) {
                    val options = JSONObject(existing.options).put("automaticUpdate", false)
                    options.remove("updateSelectionId")
                    dao.putTransfer(
                        existing.copy(
                            selection = owner,
                            state = "queued",
                            error = null,
                            nextAttemptMs = 0,
                            options = options.toString(),
                            assetId = null,
                            descriptor = null,
                        )
                    )
                    return@withTransaction true
                }
                return@withTransaction false
            }
            // Only one replacement owns this saved representation at a time.
            if (
                dao.mediaUpdateTransfers(asset.profile).any {
                    JSONObject(it.options).optString("replacesAssetId") == asset.id &&
                        it.state !in setOf("complete", "cancelled", "failed")
                }
            )
                return@withTransaction false
            val options =
                JSONObject(asset.descriptor).optJSONObject("downloadOptions")?.let {
                    JSONObject(it.toString())
                } ?: JSONObject()
            options.remove("updateSelectionId")
            for (key in
                listOf(
                    "audioStreamId",
                    "audioLanguage",
                    "subtitleStreamIndices",
                    "subtitleLanguages",
                )) options.remove(key)
            options
                .put("profile", quality)
                .put("sourceId", source.getString("id"))
                .put("includeAllTracks", true)
                .put("skipExisting", false)
                .put("replacesAssetId", asset.id)
                .put("targetSourceRevision", revision)
                .put("automaticUpdate", automatic)
            dao.putTransfer(
                OfflineTransfer(
                    asset.profile,
                    id,
                    owner,
                    asset.item,
                    options.toString(),
                    priority = Int.MAX_VALUE,
                )
            )
            true
        }
    }
}
