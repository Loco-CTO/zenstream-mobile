package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.SyncplayGroup
import com.zenstream.zenstreammobile.model.SyncplayMember
import com.zenstream.zenstreammobile.model.playableSyncplayItemId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class SyncplayApi(private val httpClient: OkHttpClient = OkHttpClient()) {
    suspend fun groups(session: AuthSession, participantId: String): List<SyncplayGroup> =
        request(session, participantId, "groups").optJSONArray("groups").toGroups()

    suspend fun group(session: AuthSession, participantId: String, id: String): SyncplayGroup =
        parseSyncplayGroup(request(session, participantId, "groups/${encode(id)}"))

    suspend fun create(session: AuthSession, participantId: String): SyncplayGroup =
        parseSyncplayGroup(request(session, participantId, "groups", "POST"))

    suspend fun join(
        session: AuthSession,
        participantId: String,
        id: String,
        revision: Int,
    ): SyncplayGroup =
        parseSyncplayGroup(
            request(
                session,
                participantId,
                "groups/${encode(id)}/join",
                "POST",
                operation(revision),
            )
        )

    suspend fun leave(session: AuthSession, participantId: String, group: SyncplayGroup) {
        request(
            session,
            participantId,
            "groups/${encode(group.id)}",
            "DELETE",
            operation(group.revision),
        )
    }

    suspend fun setControls(
        session: AuthSession,
        participantId: String,
        group: SyncplayGroup,
        enabled: Boolean,
    ): SyncplayGroup =
        parseSyncplayGroup(
            request(
                session,
                participantId,
                "groups/${encode(group.id)}",
                "PATCH",
                operation(group.revision).put("allowViewerControls", enabled),
            )
        )

    suspend fun removeMember(
        session: AuthSession,
        participantId: String,
        group: SyncplayGroup,
        userId: String,
    ): SyncplayGroup =
        parseSyncplayGroup(
            request(
                session,
                participantId,
                "groups/${encode(group.id)}/members/${encode(userId)}",
                "DELETE",
                operation(group.revision),
            )
        )

    suspend fun participation(
        session: AuthSession,
        participantId: String,
        group: SyncplayGroup,
        watching: Boolean,
    ): SyncplayGroup =
        parseSyncplayGroup(
            request(
                session,
                participantId,
                "groups/${encode(group.id)}/participation",
                "POST",
                operation().put("watchingTogether", watching),
            )
        )

    suspend fun command(
        session: AuthSession,
        participantId: String,
        group: SyncplayGroup,
        action: String,
        position: Double,
        playing: Boolean,
        itemId: String? = null,
        operationId: String = java.util.UUID.randomUUID().toString(),
    ): SyncplayGroup =
        parseSyncplayGroup(
            request(
                session,
                participantId,
                "groups/${encode(group.id)}/command",
                "POST",
                operation(group.revision, operationId)
                    .put("action", action)
                    .put("position", position)
                    .put("playing", playing)
                    .apply { itemId?.let { put("itemId", it) } },
            )
        )

    suspend fun presence(
        session: AuthSession,
        participantId: String,
        group: SyncplayGroup,
        viewing: Boolean,
        loading: Boolean,
        sequence: Int,
    ): SyncplayGroup =
        parseSyncplayGroup(
            request(
                session,
                participantId,
                "groups/${encode(group.id)}/presence",
                "POST",
                operation()
                    .put("viewing", viewing)
                    .put("loading", loading)
                    .put("mediaGeneration", group.mediaGeneration)
                    .put("timelineRevision", group.timelineRevision)
                    .put("presenceSequence", sequence),
            )
        )

    suspend fun socketTicket(session: AuthSession): String =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url("${session.serverUrl}/api/auth/socket-ticket".toHttpUrl())
                    .header("Authorization", "Bearer ${session.token}")
                    .post("{}".toRequestBody(JSON))
                    .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful)
                    throw SyncplayException(response.code, "Socket ticket request failed")
                JSONObject(response.body?.string().orEmpty()).optString("ticket").ifBlank {
                    error("Socket ticket was empty")
                }
            }
        }

    private suspend fun request(
        session: AuthSession,
        participantId: String,
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url("${session.serverUrl}/api/syncplay/$path".toHttpUrl())
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer ${session.token}")
                    .header("X-ZenStream-Username", session.username)
                    .header("X-ZenStream-Participant", participantId)
                    .method(
                        method,
                        if (method == "GET") null
                        else (body?.toString() ?: "{}").toRequestBody(JSON),
                    )
                    .build()
            httpClient.newCall(request).execute().use { response ->
                val content = response.body?.string().orEmpty()
                if (!response.isSuccessful)
                    throw SyncplayException(
                        response.code,
                        JSONObject(content.ifBlank { "{}" }).optString("detail").ifBlank {
                            "Syncplay request failed"
                        },
                        content,
                    )
                JSONObject(content.ifBlank { "{}" })
            }
        }

    private fun operation(revision: Int? = null, operationId: String = java.util.UUID.randomUUID().toString()) =
        JSONObject().put("operationId", operationId).apply {
            revision?.let { put("expectedRevision", it) }
        }

    private fun encode(value: String) = android.net.Uri.encode(value)

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

class SyncplayException(val statusCode: Int, message: String, val payload: String = "") :
    Exception(message)

fun parseSyncplayGroup(value: JSONObject): SyncplayGroup =
    SyncplayGroup(
        id = value.optString("id"),
        name = value.optString("name"),
        hostUserId = value.optString("hostUserId"),
        hostName = value.optString("hostName"),
        allowViewerControls = value.optBoolean("allowViewerControls"),
        itemId = playableSyncplayItemId(value.optString("itemId")),
        position = value.optDouble("position"),
        playing = value.optBoolean("playing"),
        resumeWhenReady = value.optBoolean("resumeWhenReady"),
        revision = value.optInt("revision"),
        timelineRevision = value.optInt("timelineRevision", value.optInt("revision")),
        mediaGeneration = value.optInt("mediaGeneration"),
        anchorPosition = value.optDouble("anchorPosition"),
        anchorServerTime = value.optDouble("anchorServerTime"),
        effectiveAt = value.optDouble("effectiveAt"),
        playbackState =
            value.optString(
                "playbackState",
                if (value.optBoolean("playing")) "playing" else "paused",
            ),
        pauseReason = value.optString("pauseReason").ifBlank { null },
        hostDisconnectedAt =
            value.optDouble("hostDisconnectedAt", Double.NaN).takeIf { it.isFinite() },
        updatedAt = value.optDouble("updatedAt"),
        members = value.optJSONArray("members").toMembers(),
    )

internal fun JSONArray?.toGroups(): List<SyncplayGroup> =
    this?.let { array ->
            List(array.length()) { parseSyncplayGroup(array.optJSONObject(it) ?: JSONObject()) }
        }
        .orEmpty()

private fun JSONArray?.toMembers(): List<SyncplayMember> =
    this?.let { array ->
            List(array.length()) { index ->
                val value = array.optJSONObject(index) ?: JSONObject()
                SyncplayMember(
                    value.optString("userId"),
                    value.optString("participantId"),
                    value.optString("username"),
                    value.optBoolean("watchingTogether", true),
                    value.optBoolean("viewing"),
                    value.optBoolean("loading"),
                    value.optInt("readyGeneration", -1),
                    value.optString("role"),
                )
            }
        }
        .orEmpty()
