package com.zenstream.zenstreammobile.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.zenstream.zenstreammobile.data.CatalogApi
import com.zenstream.zenstreammobile.data.CatalogException
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.SessionStore
import com.zenstream.zenstreammobile.model.PlaylistData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class OfflineRuntime private constructor(context: Context) {
    private val appContext = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val connection = ConnectionCoordinator(context)
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    val database = OfflineDatabase.get(context)
    val sessionStore = SessionStore(context)
    val catalogRevision =
        database.invalidationTracker
            .createFlow(
                "offline_profiles",
                "offline_catalog",
                "offline_assets",
                "offline_states",
                "offline_playback_states",
                "offline_operations",
                "offline_playlists",
                "offline_selections",
            )
            .scan(0L) { revision, _ -> revision + 1 }
            .stateIn(scope, SharingStarted.Eagerly, 0L)
    val storage =
        OfflineStorage(
            database,
            java.io.File(context.filesDir, "offline"),
            scope,
            limitBytes = { connection.downloadLimitBytes.value },
        )
    private val network = CatalogRepository(CatalogApi(), sessionStore)
    val catalog =
        OfflineCatalogRepository(database) { session, path, method, body ->
            check(connection.state.value.mode != ConnectionMode.OFFLINE) {
                "Offline mode is enabled."
            }
            network.offlineRequest(session, path, method, body)
        }
    val downloads =
        DownloadCoordinator(
            catalog,
            storage,
            request = { session, path, method, body ->
                network.offlineRequest(session, path, method, body)
            },
            binary = { session, path, headers, receive ->
                network.offlineBinaryRequest(session, path, headers, receive)
            },
            activeSession = { sessionStore.session.first() },
            sourceDetails = { session, item -> network.offlineSourceDetails(session, item) },
        )

    val audio = OfflineAudioRepository(catalog, storage)
    val playbackState = OfflinePlaybackStateRepository(appContext, database)
    val playlists = OfflinePlaylistRepository(database, playbackState, catalog)
    val accountSync =
        OfflineAccountSyncCoordinator(
            appContext,
            database,
            request = { session, path, method, body ->
                check(connection.state.value.mode != ConnectionMode.OFFLINE) {
                    "Offline mode is enabled."
                }
                network.offlineRequest(session, path, method, body)
            },
            deviceId = { sessionStore.deviceId() },
        )
    val playbackSources =
        PlaybackSourceResolver(
            catalog,
            storage,
            policy = { connection.state.first { it.initialized } },
            remote = { session, item, options -> network.playback(session, item, options) },
        )

    init {
        scope.launch { sessionStore.serverUrl.collect { connection.serverUrl = it } }
        if (ENABLED) {
            runCatching {
                connectivity.registerDefaultNetworkCallback(
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            scope.launch {
                                val policy = connection.state.first { it.initialized }
                                if (
                                    policy.mode == ConnectionMode.AUTOMATIC &&
                                        policy.profile != null
                                )
                                    DownloadExecution.scheduleReconciliationNow(appContext)
                            }
                        }
                    }
                )
            }
        }
        if (ENABLED)
            scope.launch {
                connection.state
                    .distinctUntilChangedBy { Triple(it.mode, it.profile, it.authentication) }
                    .collect { policy ->
                        if (!policy.initialized) return@collect
                        if (policy.mode == ConnectionMode.OFFLINE) {
                            DownloadExecution.stop(appContext)
                            DownloadExecution.cancelReconciliation(appContext)
                        } else if (
                            policy.profile != null &&
                                policy.authentication != OnlineAuthentication.NEEDS_SIGN_IN
                        ) {
                            DownloadExecution.scheduleReconciliation(appContext)
                            DownloadExecution.scheduleReconciliationNow(appContext)
                        }
                    }
            }
    }

    suspend fun sourceDetails(
        session: com.zenstream.zenstreammobile.model.AuthSession,
        item: String,
    ) = network.offlineSourceDetails(session, item)

    suspend fun enqueueDownload(
        context: Context,
        session: com.zenstream.zenstreammobile.model.AuthSession,
        spec: DownloadSpec,
        options: org.json.JSONObject,
    ): Boolean {
        OfflineNetworkPolicy.requireNetwork()
        val (profile, capabilities) = catalog.discover(session)
        require(options.optString("profile", "original") in downloadProfiles(spec, capabilities))
        require(spec.kind in setOf("catalog", "playlist"))
        val frozen = org.json.JSONObject(options.toString()).put("mediaType", spec.type)
        catalog.createSelection(
            session,
            profile.id,
            spec.id,
            spec.title,
            spec.kind,
            frozen,
            frozen.optString("language", "en"),
        )
        connection.selectProfile(profile.id)
        return DownloadExecution.start(context, profile.id)
    }

    suspend fun enqueuePlaylistMetadata(
        context: Context,
        session: com.zenstream.zenstreammobile.model.AuthSession,
        spec: DownloadSpec,
    ): Boolean {
        OfflineNetworkPolicy.requireNetwork()
        val profile = catalog.discover(session).first
        database
            .offline()
            .putSelection(
                OfflineSelection(
                    profile.id,
                    java.util.UUID.randomUUID().toString(),
                    spec.id,
                    "playlist_metadata",
                    spec.title,
                    org.json
                        .JSONObject()
                        .put("language", sessionStore.metadataLanguage.first())
                        .toString(),
                    state = "metadata_only",
                )
            )
        connection.selectProfile(profile.id)
        return DownloadExecution.start(context, profile.id)
    }

    /**
     * Route supported Android account mutations through the same durable ordered outbox used
     * offline. Returns false only when this server has no offline-v1 support, so callers can keep
     * the compatible REST path for older Orchestrators.
     */
    suspend fun submitOnlineMutation(
        session: com.zenstream.zenstreammobile.model.AuthSession,
        target: String,
        kind: String,
        payload: org.json.JSONObject = org.json.JSONObject(),
    ): Boolean {
        if (!ENABLED) return false
        val prepared = mutationProfile(session) ?: return false
        val (profile, online) = prepared
        val before = database.offline().targetOperations(profile.id, target).lastOrNull()
        val applied =
            when (kind) {
                "checkpoint" -> {
                    playbackState.checkpoint(
                        profile.id,
                        target,
                        payload.optDouble("positionSeconds", 0.0),
                        payload.optDouble("durationSeconds", 0.0),
                        profile.historyEnabled,
                    )
                    true
                }
                "complete" -> {
                    playbackState.complete(
                        profile.id,
                        target,
                        payload.optDouble("durationSeconds", 0.0),
                        profile.historyEnabled,
                    )
                    true
                }
                "watched",
                "unwatched" -> playbackState.setPlayed(profile.id, target, kind == "watched")
                "favorite",
                "unfavorite" ->
                    playbackState.setAccountFlag(profile.id, target, "favorite", kind == "favorite")
                "follow",
                "unfollow" ->
                    playbackState.setAccountFlag(profile.id, target, "following", kind == "follow")
                "music_play" ->
                    playbackState.musicPlay(
                        profile.id,
                        target,
                        payload.getString("playbackInstanceId"),
                        profile.historyEnabled,
                    )
                else -> return false
            }
        if (!applied) return true

        val latest = database.offline().targetOperations(profile.id, target).lastOrNull()
        if (
            online &&
                latest != before &&
                connection.state.value.authentication != OnlineAuthentication.NEEDS_SIGN_IN
        )
            DownloadExecution.scheduleAccountSync(appContext)
        return true
    }

    /** Queues edits only when a complete private playlist snapshot is cached locally. */
    suspend fun mutateCachedPlaylist(
        session: com.zenstream.zenstreammobile.model.AuthSession,
        playlistId: String,
        canApply: suspend (String) -> Boolean = { true },
        mutation: suspend (String) -> Boolean,
    ): OfflinePlaylistMutationResult? {
        if (!ENABLED) return null
        val prepared = mutationProfile(session) ?: return null
        val (profile, online) = prepared

        val dao = database.offline()
        val row = dao.playlist(profile.id, playlistId)?.takeUnless { it.deleted } ?: return null
        val metadata = org.json.JSONObject(row.metadata)
        if (
            !row.complete ||
                row.expectedCount > 10_000 ||
                dao.playlistEntryCount(profile.id, playlistId) != row.expectedCount ||
                !metadata.optBoolean("isPrivate", true) ||
                !metadata.optBoolean("isOwner", true) ||
                !canApply(profile.id)
        )
            return null

        val firstSequence = requireNotNull(dao.profile(profile.id)).nextSequence
        val changed = mutation(profile.id)
        if (changed) {
            val hasNewOperations =
                dao.targetOperations(profile.id, playlistId).any { it.sequence >= firstSequence }
            if (
                online &&
                    hasNewOperations &&
                    connection.state.value.authentication != OnlineAuthentication.NEEDS_SIGN_IN
            )
                DownloadExecution.scheduleAccountSync(appContext)
        }
        return OfflinePlaylistMutationResult(playlists.snapshotData(profile.id, playlistId))
    }

    /** Private client-generated playlist IDs make a new online playlist immediately durable. */
    suspend fun createPrivatePlaylist(
        session: com.zenstream.zenstreammobile.model.AuthSession,
        name: String,
        description: String?,
    ): PlaylistData? {
        if (!ENABLED) return null
        val prepared = mutationProfile(session) ?: return null
        val (profile, online) = prepared
        val playlistId = playlists.create(profile.id, name, description)
        val operation = database.offline().targetOperations(profile.id, playlistId).lastOrNull()
        if (
            operation != null &&
                online &&
                connection.state.value.authentication != OnlineAuthentication.NEEDS_SIGN_IN
        )
            DownloadExecution.scheduleAccountSync(appContext)
        return playlists.snapshotData(profile.id, playlistId)
    }

    private suspend fun mutationProfile(
        session: com.zenstream.zenstreammobile.model.AuthSession
    ): Pair<OfflineProfile, Boolean>? {
        val policy = connection.state.first { it.initialized }
        val dao = database.offline()
        if (policy.mode == ConnectionMode.OFFLINE) {
            val profile =
                (policy.profile?.let { dao.profile(it) }
                    ?: dao.profileForServerAccount(session.serverUrl, session.userId))
                    ?: return null
            if (
                profile.account != session.userId ||
                    profile.serverUrl.trimEnd('/') != session.serverUrl.trimEnd('/')
            )
                return null
            return profile to false
        }

        val known =
            dao.profileForServerAccount(session.serverUrl, session.userId)?.takeUnless { it.locked }
        val profile =
            known
                ?: try {
                    catalog.discover(session).first
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (
                        error.message?.contains("does not support downloads", ignoreCase = true) ==
                            true
                    )
                        return null
                    if (error is CatalogException && error.statusCode in setOf(404, 405))
                        return null
                    return null
                }
        connection.selectProfile(profile.id)
        return profile to true
    }

    suspend fun resumeDownloads(profile: String) {
        catalog.requireProfile(profile)
        database.offline().resumeAllTransfers(profile)
        database.offline().resumeEnumeration(profile)
        database.offline().retryPlaylistMetadata(profile)
    }

    var reconcileMetadata:
        (suspend (OfflineProfile, com.zenstream.zenstreammobile.model.AuthSession) -> Unit)? =
        null

    suspend fun reconcileBounded() {
        val policy = connection.state.first { it.initialized }
        if (policy.mode == ConnectionMode.OFFLINE) return
        if (policy.authentication == OnlineAuthentication.NEEDS_SIGN_IN) return
        val id = policy.profile ?: return
        val profile = database.offline().profile(id)?.takeUnless { it.locked } ?: return
        val cursor = connection.maintenanceCursor(id)
        connection.saveMaintenanceCursor(id, storage.repairPage(id, cursor) ?: 0)
        val bucket = connection.maintenanceCursor("$id:orphan-bucket")
        repeat(8) { step ->
            val selected = (bucket + step) % 256
            val key = "$id:orphan-offset:$selected"
            val offset = connection.maintenanceCursor(key)
            connection.saveMaintenanceCursor(key, storage.sweepBucket(id, selected, offset) ?: 0)
        }
        connection.saveMaintenanceCursor("$id:orphan-bucket", (bucket + 8) % 256)
        val session = sessionStore.session.first() ?: return
        if (profile.account != session.userId || profile.serverUrl != session.serverUrl) return
        accountSync.reconcile(profile.id, session)
        reconcileMetadata?.invoke(profile, session)
    }

    companion object {
        val ENABLED: Boolean
            get() =
                com.zenstream.zenstreammobile.BuildConfig.DEBUG &&
                    com.zenstream.zenstreammobile.BuildConfig.OFFLINE_QA

        @Volatile private var instance: OfflineRuntime? = null

        fun get(context: Context): OfflineRuntime =
            instance
                ?: synchronized(this) {
                    instance ?: OfflineRuntime(context.applicationContext).also { instance = it }
                }
    }
}

data class OfflinePlaylistMutationResult(val data: PlaylistData?)
