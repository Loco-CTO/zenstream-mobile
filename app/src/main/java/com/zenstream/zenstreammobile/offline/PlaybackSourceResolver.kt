package com.zenstream.zenstreammobile.offline

import androidx.room.withTransaction
import com.zenstream.zenstreammobile.data.playbackUrl
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaChapter
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.MediaSource
import com.zenstream.zenstreammobile.model.MediaStream
import com.zenstream.zenstreammobile.model.PlaybackData
import com.zenstream.zenstreammobile.model.PlaybackOptions
import com.zenstream.zenstreammobile.model.PlaybackSegment
import com.zenstream.zenstreammobile.model.PlaybackSegmentType
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class PlaybackSourcePreference {
    PREFER_DOWNLOAD,
    DOWNLOAD_ONLY,
    STREAM,
}

enum class LocalPlaybackFailure {
    UNAVAILABLE,
    LOCKED,
    DAMAGED,
    AUTHENTICATION_REQUIRED,
    NETWORK_DISABLED,
}

class LocalPlaybackUnavailable(val reason: LocalPlaybackFailure) :
    IOException(
        when (reason) {
            LocalPlaybackFailure.UNAVAILABLE ->
                "This item is not downloaded. Connect to download it or explicitly stream it."
            LocalPlaybackFailure.LOCKED -> "Sign in to the saved account to unlock this download."
            LocalPlaybackFailure.DAMAGED ->
                "This download is missing or damaged. Retry the download or explicitly choose streaming."
            LocalPlaybackFailure.AUTHENTICATION_REQUIRED -> "Sign in to stream this item."
            LocalPlaybackFailure.NETWORK_DISABLED -> "Streaming is unavailable in offline mode."
        }
    )

/** Only the resolver can create a local source. Players never infer trust from a file: URL. */
sealed interface ResolvedPlaybackSource : Closeable {
    val data: PlaybackData
    val uri: String

    class Remote internal constructor(override val data: PlaybackData, override val uri: String) :
        ResolvedPlaybackSource {
        override fun close() = Unit
    }

    class Local
    internal constructor(
        val profile: OfflineProfile,
        val asset: OfflineAsset,
        override val data: PlaybackData,
        private val lease: OfflineFileLease,
        val supplements: List<LocalSupplement>,
        val bundle: JSONObject,
    ) : ResolvedPlaybackSource {
        override val uri: String
            get() = lease.file.toURI().toASCIIString()

        val file: File
            get() = lease.file

        override fun close() = lease.close()
    }
}

class LocalSupplement
internal constructor(val key: String, val file: File, val descriptor: JSONObject)

internal fun localPlaybackData(
    item: MediaItem,
    asset: OfflineAsset,
    startSeconds: Double,
): PlaybackData {
    val descriptor = JSONObject(asset.descriptor)
    val bundle = descriptor.optJSONObject("bundle") ?: JSONObject()
    val streams =
        descriptor.optJSONArray("trackManifest")?.objects().orEmpty().map { track ->
            MediaStream(
                index = track.getInt("index"),
                type =
                    when (track.getString("type")) {
                        "audio" -> "Audio"
                        "video" -> "Video"
                        "subtitle" -> "Subtitle"
                        else -> error("Unknown local track type")
                    },
                displayTitle = track.optionalText("displayTitle"),
                language = track.optionalText("language"),
                codec = track.optionalText("codec"),
                isDefault = track.optBoolean("isDefault"),
                channels = track.optionalInt("channels"),
                sampleRate = track.optionalInt("sampleRate"),
                bitrate = track.optionalInt("bitrate"),
            )
        }
    require(
        streams.all { it.index >= 0 } && streams.map { it.index }.distinct().size == streams.size
    )
    val duration =
        descriptor.optDouble("durationSeconds").takeIf { it.isFinite() && it >= 0 }
            ?: item.durationSeconds
            ?: item.runtimeTicks?.div(10000000.0)
    val chapters =
        bundle.optJSONArray("chapters")?.objects()?.mapNotNull {
            val start = it.optDouble("startSeconds")
            if (start.isFinite() && start >= 0)
                MediaChapter((start * 10000000).toLong(), it.optionalText("name"))
            else null
        } ?: item.chapters
    val segments =
        bundle.optJSONArray("segments")?.objects().orEmpty().mapNotNull {
            val type =
                when (it.optString("type").lowercase()) {
                    "intro" -> PlaybackSegmentType.INTRO
                    "outro" -> PlaybackSegmentType.OUTRO
                    else -> return@mapNotNull null
                }
            val start = it.optDouble("startSeconds")
            val end = it.optDouble("endSeconds")
            if (start.isFinite() && end.isFinite() && start >= 0 && end > start)
                PlaybackSegment(type, start, end)
            else null
        }
    return PlaybackData(
        item.copy(chapters = chapters),
        MediaSource(
            asset.sourceId,
            mediaStreams = streams,
            durationSeconds = duration,
            container = asset.mimeType.substringAfter('/'),
        ),
        streams.filter { it.type == "Audio" },
        streams.filter { it.type == "Subtitle" },
        segments = segments,
        qualities = emptyList(),
        mode = "local",
        mimeType = asset.mimeType,
        durationSeconds = duration,
        startPositionSeconds = startSeconds.coerceAtLeast(0.0),
    )
}

private fun JSONObject.optionalText(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.optionalInt(key: String): Int? =
    if (isNull(key)) null else optInt(key).takeIf { it > 0 }

class PlaybackSourceResolver(
    private val catalog: OfflineCatalogRepository,
    private val storage: OfflineStorage,
    private val policy: suspend () -> ConnectionState,
    private val remote: suspend (AuthSession, String, PlaybackOptions) -> PlaybackData,
) {
    private val dao = catalog.database.offline()

    suspend fun resolve(
        itemId: String,
        session: AuthSession?,
        options: PlaybackOptions = PlaybackOptions(),
        preference: PlaybackSourcePreference = PlaybackSourcePreference.PREFER_DOWNLOAD,
        profileId: String? = null,
        assetId: String? = null,
    ): ResolvedPlaybackSource {
        require(itemId.isNotBlank() && options.startPositionSeconds.isFinite())
        val connection = policy()
        check(connection.initialized)
        val profile =
            (profileId ?: connection.profile)
                ?.let { dao.profile(it) }
                ?.takeIf {
                    session == null ||
                        it.account == session.userId && it.serverUrl == session.serverUrl
                }
        if (preference != PlaybackSourcePreference.STREAM && profile != null) {
            if (profile.locked) throw LocalPlaybackUnavailable(LocalPlaybackFailure.LOCKED)
            val candidates =
                if (assetId == null) dao.playbackCandidates(profile.id, itemId, 100)
                else listOfNotNull(dao.asset(profile.id, assetId)?.takeIf { it.item == itemId })
            for (asset in candidates.filter { it.state == "complete" && !it.locked }) {
                val lease =
                    try {
                        storage.acquireVerified(profile.id, asset.id)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        catalog.database.withTransaction {
                            val latest = dao.asset(profile.id, asset.id)
                            if (
                                latest?.state == "complete" &&
                                    !latest.locked &&
                                    dao.profile(profile.id)?.locked == false
                            )
                                dao.putAsset(latest.copy(state = "corrupt"))
                        }
                        continue
                    }
                try {
                    val item =
                        catalog.item(profile.id, itemId)
                            ?: throw LocalPlaybackUnavailable(LocalPlaybackFailure.DAMAGED)
                    val start =
                        if (options.startPositionSeconds > 0) options.startPositionSeconds
                        else (item.playbackPositionTicks ?: 0) / 10000000.0
                    val data = localPlaybackData(item, asset, start)
                    val supplementary =
                        withContext(Dispatchers.IO) {
                            dao.supplements(profile.id, asset.id).mapNotNull { row ->
                                try {
                                    require(row.relativePath.startsWith(profile.id + "/"))
                                    val file = storage.paths.resolve(row.relativePath)
                                    if (!file.isFile) null
                                    else LocalSupplement(row.key, file, JSONObject(row.descriptor))
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    null
                                }
                            }
                        }
                    return ResolvedPlaybackSource.Local(
                        profile,
                        asset,
                        data,
                        lease,
                        supplementary,
                        JSONObject(asset.descriptor).optJSONObject("bundle") ?: JSONObject(),
                    )
                } catch (error: Exception) {
                    lease.close()
                    if (error is CancellationException) throw error
                }
            }
            if (dao.profile(profile.id)?.locked != false)
                throw LocalPlaybackUnavailable(LocalPlaybackFailure.LOCKED)
            if (candidates.isNotEmpty())
                throw LocalPlaybackUnavailable(
                    if (candidates.all { it.locked }) LocalPlaybackFailure.LOCKED
                    else LocalPlaybackFailure.DAMAGED
                )
        }
        if (
            preference == PlaybackSourcePreference.DOWNLOAD_ONLY ||
                assetId != null ||
                connection.mode == ConnectionMode.OFFLINE ||
                preference == PlaybackSourcePreference.PREFER_DOWNLOAD &&
                    connection.reachability in
                        setOf(
                            ServerReachability.UNREACHABLE,
                            ServerReachability.CERTIFICATE_ERROR,
                            ServerReachability.PERMISSION_DENIED,
                        )
        )
            throw LocalPlaybackUnavailable(
                if (preference == PlaybackSourcePreference.STREAM)
                    LocalPlaybackFailure.NETWORK_DISABLED
                else LocalPlaybackFailure.UNAVAILABLE
            )
        val account =
            session ?: throw LocalPlaybackUnavailable(LocalPlaybackFailure.AUTHENTICATION_REQUIRED)
        OfflineNetworkPolicy.requireNetwork()
        val data = remote(account, itemId, options)
        val uri = playbackUrl(account, itemId, data.source.copy(url = data.url ?: data.source.url))
        return ResolvedPlaybackSource.Remote(data, uri)
    }
}
