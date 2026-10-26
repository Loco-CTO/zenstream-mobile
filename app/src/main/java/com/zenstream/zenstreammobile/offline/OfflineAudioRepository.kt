package com.zenstream.zenstreammobile.offline

import androidx.room.withTransaction
import com.zenstream.zenstreammobile.data.audioQueueScope
import com.zenstream.zenstreammobile.data.audioQueueSnapshotFromJson
import com.zenstream.zenstreammobile.data.parseAudioLyrics
import com.zenstream.zenstreammobile.data.toJson
import com.zenstream.zenstreammobile.model.AudioLyrics
import com.zenstream.zenstreammobile.model.AudioQueueSnapshot
import com.zenstream.zenstreammobile.model.AuthSession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Queue ownership is an account identity, independently of online authorization. */
data class AudioQueueIdentity(
    val serverUrl: String,
    val userId: String,
    val username: String,
    val session: AuthSession? = null,
    val profile: OfflineProfile? = null,
) {
    val scope: String
        get() = profile?.let { "offline:" + it.id } ?: audioQueueScope(serverUrl, userId)

    fun owns(snapshot: AudioQueueSnapshot): Boolean =
        audioQueueScope(snapshot.serverUrl, snapshot.userId) == audioQueueScope(serverUrl, userId)

    companion object {
        fun online(session: AuthSession) =
            AudioQueueIdentity(session.serverUrl, session.userId, session.username, session)
    }
}

class OfflineAudioRepository(
    private val catalog: OfflineCatalogRepository,
    private val storage: OfflineStorage,
) {
    private val dao = catalog.database.offline()

    suspend fun identity(profileId: String?, session: AuthSession?): AudioQueueIdentity? {
        val profile =
            profileId
                ?.let { dao.profile(it) }
                ?.takeUnless { it.locked }
                ?.takeIf {
                    session == null ||
                        session.userId == it.account && session.serverUrl == it.serverUrl
                }
        return profile?.let {
            AudioQueueIdentity(it.serverUrl, it.account, it.displayName, session, it)
        } ?: session?.let(AudioQueueIdentity::online)
    }

    suspend fun restore(identity: AudioQueueIdentity): AudioQueueSnapshot? {
        val profile = identity.profile ?: return null
        catalog.requireProfile(profile.id)
        return try {
            dao.audioQueue(profile.id)
                ?.let { audioQueueSnapshotFromJson(JSONObject(it.payload)) }
                ?.takeIf(identity::owns)
        } catch (_: org.json.JSONException) {
            null
        }
    }

    suspend fun save(identity: AudioQueueIdentity, snapshot: AudioQueueSnapshot) =
        withContext(Dispatchers.IO) {
            val profile = requireNotNull(identity.profile)
            require(identity.owns(snapshot))
            val encoded = snapshot.toJson().toString()
            catalog.database.withTransaction {
                catalog.requireProfile(profile.id)
                dao.putAudioQueue(OfflineAudioQueue(profile.id, encoded, snapshot.updatedAt))
            }
        }

    suspend fun clear(identity: AudioQueueIdentity) {
        identity.profile?.let { dao.clearAudioQueue(it.id) }
    }

    suspend fun artwork(profileId: String, itemId: String): File? =
        withContext(Dispatchers.IO) {
            catalog.requireProfile(profileId)
            val row = dao.artwork(profileId, "art:$itemId:Primary") ?: return@withContext null
            runCatching {
                    require(row.relativePath.startsWith(profileId + "/"))
                    storage.paths.resolve(row.relativePath).takeIf { it.isFile }
                }
                .getOrNull()
        }

    suspend fun lyrics(profileId: String, itemId: String): AudioLyrics? {
        catalog.requireProfile(profileId)
        val asset = dao.playable(profileId, itemId).firstOrNull() ?: return null
        return parseAudioLyrics(JSONObject(asset.descriptor).optJSONObject("bundle")?.opt("lyrics"))
    }
}
