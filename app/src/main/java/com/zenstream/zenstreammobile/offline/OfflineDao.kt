package com.zenstream.zenstreammobile.offline

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflineDao {
    @Upsert suspend fun putAudioQueue(value: OfflineAudioQueue)

    @Query("SELECT * FROM offline_audio_queues WHERE profile=:profile")
    suspend fun audioQueue(profile: String): OfflineAudioQueue?

    @Query("DELETE FROM offline_audio_queues WHERE profile=:profile")
    suspend fun clearAudioQueue(profile: String)

    @Query(
        "SELECT s.* FROM offline_supplements s JOIN offline_assets a ON a.profile=s.profile AND a.id=s.asset WHERE s.profile=:profile AND s.key=:key AND a.state='complete' AND a.locked=0 ORDER BY a.verifiedAt DESC,a.id LIMIT 1"
    )
    suspend fun artwork(profile: String, key: String): OfflineSupplement?

    @Query(
        "SELECT EXISTS(SELECT 1 FROM offline_supplements s JOIN offline_assets a ON a.profile=s.profile AND a.id=s.asset WHERE s.profile=:profile AND s.relativePath=:path AND s.key LIKE 'art:%' AND a.state='complete' AND a.locked=0)"
    )
    suspend fun hasArtworkFile(profile: String, path: String): Boolean

    @Query(
        "SELECT c.* FROM offline_catalog c WHERE c.profile=:profile AND c.type IN ('release','track') AND (c.parentId=:root OR c.id IN (SELECT m.item FROM offline_members m JOIN offline_selections s ON s.profile=m.profile AND s.id=m.selection WHERE m.profile=:profile AND s.rootId=:root) OR c.id IN (SELECT e.parentId FROM offline_catalog e JOIN offline_members m ON m.profile=e.profile AND m.item=e.id JOIN offline_selections s ON s.profile=m.profile AND s.id=m.selection WHERE e.profile=:profile AND s.rootId=:root)) ORDER BY CASE WHEN c.type='release' THEN 0 ELSE 1 END,COALESCE((SELECT MIN(m.ordinal) FROM offline_members m JOIN offline_selections s ON s.profile=m.profile AND s.id=m.selection WHERE m.profile=c.profile AND m.item=c.id AND s.rootId=:root),2147483647),c.parentId,c.disc,c.track,c.name COLLATE NOCASE,c.id LIMIT :limit OFFSET :offset"
    )
    suspend fun musicChildren(
        profile: String,
        root: String,
        limit: Int,
        offset: Int,
    ): List<OfflineCatalogItem>

    @Query(
        "WITH RECURSIVE saved(id) AS (SELECT item FROM offline_assets WHERE profile=:profile AND state='complete' AND locked=0 UNION SELECT s.rootId FROM offline_selections s JOIN offline_asset_refs r ON r.profile=s.profile AND r.selection=s.id JOIN offline_assets a ON a.profile=r.profile AND a.id=r.asset WHERE s.profile=:profile AND a.state='complete' AND a.locked=0 UNION SELECT c.parentId FROM offline_catalog c JOIN saved d ON c.id=d.id WHERE c.profile=:profile AND c.parentId IS NOT NULL) SELECT c.* FROM offline_catalog c WHERE c.profile=:profile AND c.type=:type AND c.id IN (SELECT id FROM saved) ORDER BY c.name COLLATE NOCASE,c.disc,c.track,c.id LIMIT :limit OFFSET :offset"
    )
    suspend fun savedMusicType(
        profile: String,
        type: String,
        limit: Int,
        offset: Int,
    ): List<OfflineCatalogItem>

    @Upsert suspend fun putProfile(value: OfflineProfile)

    @Query("DELETE FROM offline_profiles WHERE id=:id") suspend fun deleteProfileRecord(id: String)

    @Query("SELECT * FROM offline_profiles WHERE id=:id")
    suspend fun profile(id: String): OfflineProfile?

    @Query("SELECT * FROM offline_profiles ORDER BY displayName")
    fun profiles(): Flow<List<OfflineProfile>>

    @Query("SELECT * FROM offline_profiles WHERE serverInstance=:server AND account=:account")
    suspend fun profileFor(server: String, account: String): OfflineProfile?

    @Query("SELECT * FROM offline_profiles WHERE serverUrl=:serverUrl AND account=:account LIMIT 1")
    suspend fun profileForServerAccount(serverUrl: String, account: String): OfflineProfile?

    @Upsert suspend fun putItems(values: List<OfflineCatalogItem>)

    @Query(
        "SELECT * FROM offline_catalog WHERE profile=:profile ORDER BY id LIMIT :limit OFFSET :offset"
    )
    suspend fun catalogPage(profile: String, limit: Int, offset: Int): List<OfflineCatalogItem>

    @Query("SELECT * FROM offline_catalog WHERE profile=:profile AND id=:id")
    suspend fun item(profile: String, id: String): OfflineCatalogItem?

    @Query(
        "SELECT * FROM offline_catalog WHERE profile=:profile AND type='track' AND name LIKE :query ESCAPE '!' ORDER BY name COLLATE NOCASE,disc,track,id LIMIT :limit OFFSET :offset"
    )
    suspend fun knownTracks(
        profile: String,
        query: String,
        limit: Int,
        offset: Int,
    ): List<OfflineCatalogItem>

    @Query(
        "SELECT * FROM offline_catalog WHERE profile=:profile AND type IN ('artist','release') AND name LIKE :query ESCAPE '!' ORDER BY type,name COLLATE NOCASE,id LIMIT :limit OFFSET :offset"
    )
    suspend fun knownMusicGroups(
        profile: String,
        query: String,
        limit: Int,
        offset: Int,
    ): List<OfflineCatalogItem>

    @Query(
        "SELECT * FROM offline_catalog WHERE profile=:profile AND parentId=:parent ORDER BY season,episode,disc,track,id"
    )
    suspend fun children(profile: String, parent: String): List<OfflineCatalogItem>

    @Query(
        "WITH current_series(id) AS (SELECT CASE WHEN parent.type='season' THEN parent.parentId ELSE parent.id END FROM offline_catalog focus JOIN offline_catalog parent ON parent.profile=focus.profile AND parent.id=focus.parentId WHERE focus.profile=:profile AND focus.id=:item AND focus.type='episode') SELECT candidate.* FROM offline_catalog candidate WHERE candidate.profile=:profile AND candidate.type='episode' AND (candidate.parentId IN (SELECT id FROM current_series) OR candidate.parentId IN (SELECT season.id FROM offline_catalog season WHERE season.profile=:profile AND season.type='season' AND season.parentId IN (SELECT id FROM current_series))) ORDER BY candidate.season,candidate.episode,candidate.episodeEnd,candidate.id"
    )
    suspend fun episodesInSeries(profile: String, item: String): List<OfflineCatalogItem>

    @Query(
        "SELECT * FROM offline_catalog WHERE profile=:profile AND (:type='' OR type=:type) AND name LIKE :query ESCAPE '!' ORDER BY name COLLATE NOCASE,id LIMIT :limit OFFSET :offset"
    )
    suspend fun search(
        profile: String,
        type: String,
        query: String,
        limit: Int,
        offset: Int,
    ): List<OfflineCatalogItem>

    @Upsert suspend fun putSelection(value: OfflineSelection)

    @Query("SELECT * FROM offline_selections WHERE profile=:profile AND id=:id")
    suspend fun selection(profile: String, id: String): OfflineSelection?

    @Query("SELECT * FROM offline_selections WHERE profile=:profile ORDER BY title,id")
    fun selections(profile: String): Flow<List<OfflineSelection>>

    @Query(
        "SELECT * FROM offline_selections WHERE profile=:profile AND state!='removal_pending' ORDER BY title,id LIMIT :limit OFFSET :offset"
    )
    fun selectionPage(profile: String, limit: Int, offset: Int): Flow<List<OfflineSelection>>

    @Query(
        "SELECT s.* FROM offline_selections s WHERE s.profile=:profile AND s.state!='removal_pending' ORDER BY CASE WHEN s.state NOT IN ('complete','metadata_complete','cancelled') OR s.id IN (SELECT t.selection FROM offline_transfers t WHERE t.profile=:profile AND t.state NOT IN ('complete','cancelled')) THEN 0 ELSE 1 END,s.title COLLATE NOCASE,s.id LIMIT :limit OFFSET :offset"
    )
    fun managedSelectionPage(profile: String, limit: Int, offset: Int): Flow<List<OfflineSelection>>

    @Query(
        "SELECT * FROM offline_transfers WHERE profile=:profile ORDER BY priority DESC,id LIMIT :limit OFFSET :offset"
    )
    fun transferPage(profile: String, limit: Int, offset: Int): Flow<List<OfflineTransfer>>

    @Query(
        "SELECT * FROM offline_transfers WHERE profile=:profile ORDER BY id LIMIT :limit OFFSET :offset"
    )
    suspend fun profileTransferPage(profile: String, limit: Int, offset: Int): List<OfflineTransfer>

    @Query(
        "SELECT s.* FROM offline_selections s WHERE s.profile=:profile AND (s.state='metadata_only' OR (s.kind='playlist' AND s.state IN ('queued','complete','partial') AND s.error IS NULL AND NOT EXISTS (SELECT 1 FROM offline_playlists p WHERE p.profile=s.profile AND p.id=s.rootId AND p.complete=1))) LIMIT 1"
    )
    suspend fun playlistMetadataWork(profile: String): List<OfflineSelection>

    @Query(
        "UPDATE offline_transfers SET state='queued',error=NULL,nextAttemptMs=0 WHERE profile=:profile AND state IN ('paused','failed')"
    )
    suspend fun resumeAllTransfers(profile: String)

    @Query(
        "UPDATE offline_selections SET state=substr(state,8) WHERE profile=:profile AND state IN ('paused_enumerating','paused_ready','paused_queueing','paused_metadata_only')"
    )
    suspend fun resumeEnumeration(profile: String)

    @Query(
        "UPDATE offline_selections SET state=CASE WHEN state='metadata_failed' THEN 'metadata_only' ELSE state END,error=NULL WHERE profile=:profile AND error='playlist_metadata_unavailable'"
    )
    suspend fun retryPlaylistMetadata(profile: String)

    @Query(
        "SELECT * FROM offline_selections WHERE profile=:profile AND state='enumerating' LIMIT 20"
    )
    suspend fun pendingSelections(profile: String): List<OfflineSelection>

    @Upsert suspend fun putMembers(values: List<OfflineMember>)

    @Query(
        "SELECT * FROM offline_members WHERE profile=:profile AND selection=:selection ORDER BY ordinal,item LIMIT :limit OFFSET :offset"
    )
    suspend fun members(
        profile: String,
        selection: String,
        limit: Int,
        offset: Int,
    ): List<OfflineMember>

    @Query(
        "SELECT * FROM offline_members WHERE profile=:profile AND selection=:selection AND item=:item"
    )
    suspend fun member(profile: String, selection: String, item: String): OfflineMember?

    @Query(
        "SELECT COUNT(*) FROM offline_members WHERE profile=:profile AND selection=:selection AND (ordinal<:ordinal OR (ordinal=:ordinal AND item<:item))"
    )
    suspend fun memberPosition(
        profile: String,
        selection: String,
        ordinal: Int,
        item: String,
    ): Int

    @Upsert suspend fun putAsset(value: OfflineAsset)

    @Query("SELECT * FROM offline_assets WHERE profile=:profile AND id=:id")
    suspend fun asset(profile: String, id: String): OfflineAsset?

    @Query(
        "SELECT * FROM offline_assets WHERE profile=:profile AND state='complete' AND locked=0 ORDER BY id LIMIT :limit OFFSET :offset"
    )
    suspend fun metadataAssetPage(profile: String, limit: Int, offset: Int): List<OfflineAsset>

    @Query(
        "SELECT * FROM offline_assets WHERE profile=:profile AND item=:item AND state='complete' AND locked=0 ORDER BY verifiedAt DESC,id LIMIT 1"
    )
    suspend fun latestCompleteAsset(profile: String, item: String): OfflineAsset?

    @Query("UPDATE offline_assets SET locked=1 WHERE profile=:profile AND item=:item")
    suspend fun lockAssetsForItem(profile: String, item: String)

    @Query(
        "SELECT * FROM offline_assets WHERE profile=:profile AND item=:item AND state='complete' AND locked=0 ORDER BY verifiedAt DESC,id"
    )
    suspend fun playable(profile: String, item: String): List<OfflineAsset>

    @Query("SELECT * FROM offline_assets WHERE profile=:profile AND item=:item")
    suspend fun assetsForItem(profile: String, item: String): List<OfflineAsset>

    @Query(
        "SELECT * FROM offline_assets WHERE profile=:profile AND item=:item ORDER BY (state='complete' AND locked=0) DESC,verifiedAt DESC,id LIMIT :limit"
    )
    suspend fun playbackCandidates(profile: String, item: String, limit: Int): List<OfflineAsset>

    @Query("SELECT * FROM offline_assets WHERE profile=:profile")
    suspend fun assets(profile: String): List<OfflineAsset>

    @Query(
        "SELECT COALESCE(SUM(size),0) FROM offline_assets WHERE profile=:profile AND state='complete'"
    )
    fun storageUsed(profile: String): Flow<Long>

    @Upsert suspend fun putReference(value: OfflineAssetReference)

    @Query("SELECT COUNT(*) FROM offline_asset_refs WHERE profile=:profile AND asset=:asset")
    suspend fun references(profile: String, asset: String): Int

    @Query("DELETE FROM offline_asset_refs WHERE profile=:profile AND selection=:selection")
    suspend fun removeReferences(profile: String, selection: String)

    @Query(
        "UPDATE offline_assets SET state='pending_delete' WHERE profile=:profile AND id IN (SELECT asset FROM offline_asset_refs WHERE profile=:profile AND selection=:selection) AND NOT EXISTS (SELECT 1 FROM offline_asset_refs retained WHERE retained.profile=offline_assets.profile AND retained.asset=offline_assets.id AND retained.selection!=:selection)"
    )
    suspend fun markSelectionAssetsPendingDelete(profile: String, selection: String)

    @Query(
        "DELETE FROM offline_asset_refs WHERE profile=:profile AND selection=:selection AND asset IN (SELECT id FROM offline_assets WHERE profile=:profile AND item=:item)"
    )
    suspend fun removeSelectionItemReferences(profile: String, selection: String, item: String)

    @Query(
        "UPDATE offline_assets SET state='pending_delete' WHERE profile=:profile AND id IN (SELECT asset FROM offline_asset_refs WHERE profile=:profile AND selection=:selection) AND item=:item AND NOT EXISTS (SELECT 1 FROM offline_asset_refs retained WHERE retained.profile=offline_assets.profile AND retained.asset=offline_assets.id AND retained.selection!=:selection)"
    )
    suspend fun markSelectionItemAssetsPendingDelete(
        profile: String,
        selection: String,
        item: String,
    )

    @Upsert suspend fun putTransfer(value: OfflineTransfer)

    @Query("SELECT * FROM offline_transfers WHERE profile=:profile AND id=:id")
    suspend fun transfer(profile: String, id: String): OfflineTransfer?

    @Query(
        "SELECT * FROM offline_transfers WHERE profile=:profile AND selection=:selection AND item=:item"
    )
    suspend fun transfersForSelectionItem(
        profile: String,
        selection: String,
        item: String,
    ): List<OfflineTransfer>

    @Query("SELECT * FROM offline_transfers WHERE profile=:profile ORDER BY priority DESC,id")
    fun transfers(profile: String): Flow<List<OfflineTransfer>>

    @Query(
        "SELECT * FROM offline_transfers WHERE profile=:profile AND state IN ('queued','resolving','preparing','transferring','verifying','publishing') AND nextAttemptMs<=:now AND (assetId IS NOT NULL OR (SELECT COUNT(*) FROM offline_transfers p WHERE p.profile=:profile AND p.state='preparing')<2) AND EXISTS(SELECT 1 FROM offline_selections s WHERE s.profile=offline_transfers.profile AND s.id=offline_transfers.selection AND s.state IN ('queued','downloading','complete','partial')) ORDER BY priority DESC,id LIMIT :limit"
    )
    suspend fun pendingTransfers(profile: String, now: Long, limit: Int): List<OfflineTransfer>

    @Query(
        "SELECT COALESCE(SUM(reserved),0) FROM offline_transfers WHERE state NOT IN ('complete','cancelled','failed')"
    )
    suspend fun reservedBytes(): Long

    @Upsert suspend fun putSupplements(values: List<OfflineSupplement>)

    @Query("SELECT * FROM offline_supplements WHERE profile=:profile AND asset=:asset")
    suspend fun supplements(profile: String, asset: String): List<OfflineSupplement>

    @Query(
        "SELECT * FROM offline_supplements WHERE profile=:profile ORDER BY asset,key LIMIT :limit OFFSET :offset"
    )
    suspend fun supplementPage(profile: String, limit: Int, offset: Int): List<OfflineSupplement>

    @Upsert suspend fun putState(value: OfflineUserState)

    @Upsert suspend fun putPlaybackState(value: OfflinePlaybackState)

    @Query("SELECT * FROM offline_playback_states WHERE profile=:profile AND target=:target")
    suspend fun playbackState(profile: String, target: String): OfflinePlaybackState?

    @Query(
        "SELECT p.* FROM offline_playback_states p JOIN offline_catalog c ON c.profile=p.profile AND c.id=p.target WHERE p.profile=:profile AND p.played=0 AND p.positionSeconds>0 AND c.type IN ('movie','episode') AND EXISTS(SELECT 1 FROM offline_assets a WHERE a.profile=p.profile AND a.item=p.target AND a.state='complete' AND a.locked=0) ORDER BY p.activityMs DESC,p.target LIMIT :limit"
    )
    suspend fun continueWatching(profile: String, limit: Int): List<OfflinePlaybackState>

    @Query(
        "SELECT p.* FROM offline_playback_states p JOIN offline_catalog c ON c.profile=p.profile AND c.id=p.target WHERE p.profile=:profile AND p.played=1 AND c.type='episode' AND EXISTS(SELECT 1 FROM offline_assets a WHERE a.profile=p.profile AND a.item=p.target AND a.state='complete' AND a.locked=0) ORDER BY p.activityMs DESC,c.season DESC,c.episode DESC,c.episodeEnd DESC,p.target DESC LIMIT :limit"
    )
    suspend fun completedDownloadedEpisodes(
        profile: String,
        limit: Int,
    ): List<OfflinePlaybackState>

    @Query(
        "SELECT CASE WHEN parent.type='season' THEN parent.parentId ELSE parent.id END FROM offline_catalog focus JOIN offline_catalog parent ON parent.profile=focus.profile AND parent.id=focus.parentId WHERE focus.profile=:profile AND focus.id=:episode AND focus.type='episode'"
    )
    suspend fun episodeSeriesId(profile: String, episode: String): String?

    @Query(
        "SELECT c.id FROM offline_catalog c WHERE c.profile=:profile AND c.type='episode' AND EXISTS(SELECT 1 FROM offline_assets a WHERE a.profile=c.profile AND a.item=c.id AND a.state='complete' AND a.locked=0) AND c.id IN (WITH RECURSIVE descendants(id) AS (SELECT :root UNION SELECT child.id FROM offline_catalog child JOIN descendants parent ON child.parentId=parent.id WHERE child.profile=:profile) SELECT id FROM descendants) ORDER BY c.season,c.episode,c.episodeEnd,c.id LIMIT :limit OFFSET :offset"
    )
    suspend fun downloadedEpisodeIds(
        profile: String,
        root: String,
        limit: Int,
        offset: Int,
    ): List<String>

    @Query(
        "SELECT COUNT(DISTINCT c.id) AS total,COUNT(DISTINCT CASE WHEN p.played=1 THEN c.id END) AS watched FROM offline_catalog c JOIN offline_assets a ON a.profile=c.profile AND a.item=c.id AND a.state='complete' AND a.locked=0 LEFT JOIN offline_playback_states p ON p.profile=c.profile AND p.target=c.id WHERE c.profile=:profile AND c.type='episode' AND c.id IN (WITH RECURSIVE descendants(id) AS (SELECT :root UNION SELECT child.id FROM offline_catalog child JOIN descendants parent ON child.parentId=parent.id WHERE child.profile=:profile) SELECT id FROM descendants)"
    )
    suspend fun downloadedEpisodeWatchCounts(profile: String, root: String): OfflineWatchedCounts

    @Query("SELECT * FROM offline_states WHERE profile=:profile AND target=:target")
    suspend fun state(profile: String, target: String): OfflineUserState?

    @Query("SELECT * FROM offline_states WHERE profile=:profile")
    fun states(profile: String): Flow<List<OfflineUserState>>

    @Upsert suspend fun putPlaylist(value: OfflinePlaylist)

    @Query(
        "SELECT * FROM offline_playlists WHERE profile=:profile AND complete=1 AND deleted=0 AND id NOT LIKE '%#offline-staging' ORDER BY id LIMIT :limit OFFSET :offset"
    )
    suspend fun playlistRefreshPage(profile: String, limit: Int, offset: Int): List<OfflinePlaylist>

    @Query("SELECT * FROM offline_playlists WHERE profile=:profile AND id=:id")
    suspend fun playlist(profile: String, id: String): OfflinePlaylist?

    @Query("SELECT * FROM offline_playlists WHERE profile=:profile AND deleted=0")
    fun playlists(profile: String): Flow<List<OfflinePlaylist>>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM offline_operations WHERE profile=:profile AND target=:playlist AND kind LIKE 'playlist_%')"
    )
    suspend fun hasPendingPlaylistOperations(profile: String, playlist: String): Boolean

    @Upsert suspend fun putPlaylistEntries(values: List<OfflinePlaylistEntry>)

    @Query(
        "SELECT * FROM offline_playlist_entries WHERE profile=:profile AND playlist=:playlist ORDER BY position,track LIMIT :limit OFFSET :offset"
    )
    suspend fun playlistEntries(
        profile: String,
        playlist: String,
        limit: Int,
        offset: Int,
    ): List<OfflinePlaylistEntry>

    @Query(
        "SELECT * FROM offline_playlist_entries WHERE profile=:profile AND playlist=:playlist AND track=:track LIMIT 1"
    )
    suspend fun playlistEntry(
        profile: String,
        playlist: String,
        track: String,
    ): OfflinePlaylistEntry?

    @Query("DELETE FROM offline_playlist_entries WHERE profile=:profile AND playlist=:playlist")
    suspend fun clearPlaylistEntries(profile: String, playlist: String)

    @Query(
        "DELETE FROM offline_playlist_entries WHERE profile=:profile AND playlist=:playlist AND track=:track"
    )
    suspend fun deletePlaylistEntry(profile: String, playlist: String, track: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOperation(value: OfflineOperation)

    @Upsert suspend fun putOperation(value: OfflineOperation)

    @Query("SELECT * FROM offline_operations WHERE profile=:profile AND id=:id LIMIT 1")
    suspend fun operation(profile: String, id: String): OfflineOperation?

    @Query(
        "SELECT EXISTS(SELECT 1 FROM offline_operations WHERE profile=:profile AND target=:target AND kind IN ('favorite','unfavorite','follow','unfollow'))"
    )
    suspend fun hasPendingFlagOperations(profile: String, target: String): Boolean

    @Query("SELECT * FROM offline_operations WHERE profile=:profile ORDER BY sequence LIMIT :limit")
    suspend fun operations(profile: String, limit: Int): List<OfflineOperation>

    @Query(
        "SELECT * FROM offline_operations WHERE profile=:profile AND target=:target ORDER BY sequence"
    )
    suspend fun targetOperations(profile: String, target: String): List<OfflineOperation>

    @Query("UPDATE offline_operations SET inFlight=1 WHERE profile=:profile AND sequence<=:through")
    suspend fun freezeOperations(profile: String, through: Long)

    @Query("DELETE FROM offline_operations WHERE profile=:profile AND sequence<=:through")
    suspend fun acknowledge(profile: String, through: Long)

    @Query("DELETE FROM offline_selections WHERE profile=:profile AND id=:selection")
    suspend fun deleteSelection(profile: String, selection: String)

    @Query("DELETE FROM offline_members WHERE profile=:profile AND selection=:selection")
    suspend fun deleteMembers(profile: String, selection: String)

    @Query(
        "DELETE FROM offline_members WHERE profile=:profile AND selection=:selection AND item=:item"
    )
    suspend fun deleteSelectionMember(profile: String, selection: String, item: String)

    @Query("DELETE FROM offline_assets WHERE profile=:profile AND id=:asset")
    suspend fun deleteAsset(profile: String, asset: String)

    @Query("SELECT COUNT(*) FROM offline_assets WHERE profile=:profile")
    suspend fun assetCount(profile: String): Int

    @Query("DELETE FROM offline_supplements WHERE profile=:profile AND asset=:asset")
    suspend fun deleteSupplements(profile: String, asset: String)

    @Query(
        "WITH RECURSIVE saved(id) AS (SELECT item FROM offline_assets WHERE profile=:profile AND state='complete' AND locked=0 UNION SELECT s.rootId FROM offline_selections s JOIN offline_asset_refs r ON r.profile=s.profile AND r.selection=s.id JOIN offline_assets a ON a.profile=r.profile AND a.id=r.asset WHERE s.profile=:profile AND a.state='complete' AND a.locked=0 UNION SELECT c.parentId FROM offline_catalog c JOIN saved d ON c.id=d.id WHERE c.profile=:profile AND c.parentId IS NOT NULL) SELECT c.* FROM offline_catalog c WHERE c.profile=:profile AND c.id IN (SELECT id FROM saved) AND (:category='' OR (:category='movies' AND c.type='movie') OR (:category='tv' AND c.type IN ('series','season','episode')) OR (:category='music' AND c.type IN ('artist','release','track'))) AND c.name LIKE :query ESCAPE '!' ORDER BY c.name COLLATE NOCASE,c.disc,c.track,c.id LIMIT :limit OFFSET :offset"
    )
    suspend fun browse(
        profile: String,
        category: String,
        query: String,
        limit: Int,
        offset: Int,
    ): List<OfflineCatalogItem>

    @Query(
        """WITH RECURSIVE saved(id) AS (
            SELECT item FROM offline_assets WHERE profile=:profile AND state='complete' AND locked=0
            UNION SELECT c.parentId FROM offline_catalog c JOIN saved d ON c.id=d.id
                WHERE c.profile=:profile AND c.parentId IS NOT NULL
        ), candidates AS (SELECT c.* FROM offline_catalog c WHERE c.profile=:profile AND c.id IN (SELECT id FROM saved))
        SELECT c.* FROM candidates c WHERE
            (:category='' OR (:category='movies' AND c.type='movie') OR
             (:category='tv' AND c.type IN ('series','season','episode')) OR
             (:category='music' AND c.type IN ('release','track')))
            AND (c.type IN ('movie','series','release') OR
                (c.type='season' AND NOT EXISTS(SELECT 1 FROM candidates p WHERE p.id=c.parentId AND p.type='series')) OR
                (c.type='episode' AND NOT EXISTS(SELECT 1 FROM candidates p WHERE p.id=c.parentId AND p.type IN ('series','season'))) OR
                (c.type='track' AND NOT EXISTS(SELECT 1 FROM candidates p WHERE p.id=c.parentId AND p.type='release')))
        ORDER BY c.name COLLATE NOCASE,c.id LIMIT :limit OFFSET :offset"""
    )
    suspend fun downloadRoots(
        profile: String,
        category: String,
        limit: Int,
        offset: Int,
    ): List<OfflineCatalogItem>

    @Query(
        """WITH RECURSIVE descendants(id) AS (
            SELECT :root UNION SELECT c.id FROM offline_catalog c JOIN descendants p ON c.parentId=p.id WHERE c.profile=:profile
        ), saved(id) AS (
            SELECT item FROM offline_assets WHERE profile=:profile AND state='complete' AND locked=0
            UNION SELECT c.parentId FROM offline_catalog c JOIN saved d ON c.id=d.id WHERE c.profile=:profile AND c.parentId IS NOT NULL
        ), member_ids(id) AS (
            SELECT m.item FROM offline_members m JOIN offline_selections s ON s.profile=m.profile AND s.id=m.selection WHERE m.profile=:profile AND s.rootId=:root
            UNION SELECT c.parentId FROM offline_catalog c JOIN member_ids m ON c.id=m.id WHERE c.profile=:profile AND c.parentId IS NOT NULL
        ), candidates AS (
            SELECT c.* FROM offline_catalog c WHERE c.profile=:profile AND c.id<>:root AND c.id IN (SELECT id FROM saved)
                AND (c.id IN (SELECT id FROM descendants) OR c.id IN (SELECT id FROM member_ids))
        ) SELECT c.* FROM candidates c WHERE c.name LIKE :query ESCAPE '!' AND (
            (:kind IN ('series','season') AND c.type='episode') OR
            (:kind='release' AND c.type='track') OR
            (:kind='artist' AND (c.type='release' OR (c.type='track' AND NOT EXISTS(SELECT 1 FROM candidates p WHERE p.id=c.parentId AND p.type='release')))) OR
            (:kind='collection' AND (c.type IN ('movie','series','release') OR
                (c.type='season' AND NOT EXISTS(SELECT 1 FROM candidates p WHERE p.id=c.parentId AND p.type='series')) OR
                (c.type='episode' AND NOT EXISTS(SELECT 1 FROM candidates p WHERE p.id=c.parentId AND p.type IN ('series','season'))) OR
                (c.type='track' AND NOT EXISTS(SELECT 1 FROM candidates p WHERE p.id=c.parentId AND p.type='release')))))
        ORDER BY c.season,c.episode,c.episodeEnd,c.disc,c.track,c.name COLLATE NOCASE,c.id LIMIT :limit OFFSET :offset"""
    )
    suspend fun downloadChildren(
        profile: String,
        root: String,
        kind: String,
        query: String,
        limit: Int,
        offset: Int,
    ): List<OfflineCatalogItem>

    @Query(
        "SELECT (SELECT COUNT(*) FROM offline_transfers WHERE profile=:profile AND state NOT IN ('complete','cancelled')) + (SELECT COUNT(*) FROM offline_selections s WHERE s.profile=:profile AND s.state IN ('enumerating','ready','queueing','metadata_only','paused_enumerating','paused_ready','paused_queueing','paused_metadata_only') AND s.id NOT IN (SELECT t.selection FROM offline_transfers t WHERE t.profile=:profile AND t.state NOT IN ('complete','cancelled')))"
    )
    fun pendingDownloadCount(profile: String): Flow<Int>

    @Query(
        "SELECT * FROM offline_transfers WHERE profile=:profile AND selection=:selection ORDER BY priority DESC,id LIMIT :limit OFFSET :offset"
    )
    fun selectionTransferPage(
        profile: String,
        selection: String,
        limit: Int,
        offset: Int,
    ): Flow<List<OfflineTransfer>>

    @Query(
        "SELECT COUNT(DISTINCT e.track) FROM offline_playlist_entries e JOIN offline_assets a ON a.profile=e.profile AND a.item=e.track AND a.state='complete' AND a.locked=0 WHERE e.profile=:profile AND e.playlist=:playlist"
    )
    suspend fun playlistDownloadedCount(profile: String, playlist: String): Int

    @Query(
        "WITH RECURSIVE descendants(id) AS (SELECT :item UNION SELECT e.id FROM offline_catalog e JOIN descendants d ON e.parentId=d.id WHERE e.profile=:profile) SELECT COUNT(DISTINCT c.id) AS total,COUNT(DISTINCT CASE WHEN a.id IS NOT NULL THEN c.id END) AS downloaded,COUNT(DISTINCT CASE WHEN c.newSourceAvailable=1 THEN c.id END) AS newSourceAvailable FROM offline_catalog c LEFT JOIN offline_assets a ON a.profile=c.profile AND a.item=c.id AND a.state='complete' AND a.locked=0 WHERE c.profile=:profile AND c.type IN ('movie','episode','track') AND (c.id IN (SELECT id FROM descendants) OR c.id IN (SELECT m.item FROM offline_members m JOIN offline_selections s ON s.profile=m.profile AND s.id=m.selection WHERE m.profile=:profile AND s.rootId=:item))"
    )
    suspend fun availability(profile: String, item: String): OfflineAvailability

    @Query("DELETE FROM offline_playlists WHERE profile=:profile AND id=:id")
    suspend fun deletePlaylist(profile: String, id: String)

    @Query(
        "INSERT INTO offline_playlist_entries(profile,playlist,track,entryId,position) SELECT profile,:target,track,entryId,position FROM offline_playlist_entries WHERE profile=:profile AND playlist=:staging"
    )
    suspend fun publishPlaylistEntries(profile: String, staging: String, target: String)

    @Query(
        "SELECT COUNT(*) FROM offline_playlist_entries WHERE profile=:profile AND playlist=:playlist"
    )
    suspend fun playlistEntryCount(profile: String, playlist: String): Int

    @Query(
        "SELECT COALESCE(SUM(reserved),0) FROM offline_transfers WHERE NOT (profile=:profile AND id=:id) AND state NOT IN ('complete','cancelled','failed')"
    )
    suspend fun reservedExcept(profile: String, id: String): Long

    @Query(
        "SELECT COALESCE(SUM(size),0) FROM offline_assets WHERE state IN ('complete','staged','pending_delete')"
    )
    suspend fun totalAssetBytes(): Long

    @Query(
        "SELECT COALESCE(SUM(bytes),0) FROM offline_transfers WHERE state NOT IN ('complete','cancelled')"
    )
    suspend fun totalPartialBytes(): Long

    @Query(
        "SELECT * FROM offline_assets WHERE profile=:profile ORDER BY id LIMIT :limit OFFSET :offset"
    )
    suspend fun assetPage(profile: String, limit: Int, offset: Int): List<OfflineAsset>

    @Query(
        "SELECT * FROM offline_assets WHERE profile=:profile AND id > :afterId ORDER BY id LIMIT :limit"
    )
    suspend fun assetPageAfter(
        profile: String,
        afterId: String,
        limit: Int,
    ): List<OfflineAsset>

    @Query(
        "UPDATE offline_transfers SET state='cancelled',reserved=0 WHERE profile=:profile AND selection=:selection"
    )
    suspend fun cancelSelectionTransfers(profile: String, selection: String)

    @Query(
        "SELECT (EXISTS(SELECT 1 FROM offline_assets WHERE profile=:profile AND relativePath=:path) OR EXISTS(SELECT 1 FROM offline_supplements WHERE profile=:profile AND relativePath=:path) OR EXISTS(SELECT 1 FROM offline_transfers WHERE profile=:profile AND assetId=:id AND state NOT IN ('complete','cancelled')))"
    )
    suspend fun fileReferences(profile: String, path: String, id: String): Int

    @Query(
        "SELECT * FROM offline_selections WHERE profile=:profile AND state IN ('ready','queueing') LIMIT 3"
    )
    suspend fun queueableSelections(profile: String): List<OfflineSelection>

    @Query(
        "SELECT * FROM offline_selections WHERE profile=:profile AND state='removal_pending' LIMIT 20"
    )
    suspend fun cleanupSelections(profile: String): List<OfflineSelection>

    @Query("DELETE FROM offline_transfers WHERE profile=:profile AND selection=:selection")
    suspend fun deleteSelectionTransfers(profile: String, selection: String)

    @Query(
        "DELETE FROM offline_transfers WHERE profile=:profile AND selection=:selection AND item=:item"
    )
    suspend fun deleteSelectionItemTransfers(profile: String, selection: String, item: String)

    @Query(
        "SELECT COUNT(*) AS total,COALESCE(SUM(CASE WHEN state='complete' THEN 1 ELSE 0 END),0) AS complete,COALESCE(SUM(total),0) AS totalBytes,COALESCE(SUM(bytes),0) AS downloadedBytes FROM offline_transfers WHERE profile=:profile AND state!='cancelled'"
    )
    suspend fun downloadTotals(profile: String): DownloadTotals

    @Query(
        "SELECT COUNT(*) AS total,COALESCE(SUM(CASE WHEN state='complete' THEN 1 ELSE 0 END),0) AS complete,COALESCE(SUM(total),0) AS totalBytes,COALESCE(SUM(bytes),0) AS downloadedBytes FROM offline_transfers WHERE profile=:profile AND selection=:selection AND state!='cancelled'"
    )
    fun selectionTotals(profile: String, selection: String): Flow<DownloadTotals>

    @Query(
        "SELECT (SELECT COUNT(*) FROM offline_selections s WHERE profile=:profile AND (state IN ('enumerating','ready','queueing','removal_pending','metadata_only') OR (kind='playlist' AND state IN ('queued','complete','partial') AND error IS NULL AND NOT EXISTS(SELECT 1 FROM offline_playlists p WHERE p.profile=s.profile AND p.id=s.rootId AND p.complete=1)))) + (SELECT COUNT(*) FROM offline_transfers WHERE profile=:profile AND state IN ('queued','resolving','preparing','transferring','verifying','publishing'))"
    )
    suspend fun pendingWorkCount(profile: String): Int

    @Query(
        "UPDATE offline_transfers SET state='paused',reserved=0 WHERE profile=:profile AND state NOT IN ('complete','cancelled','failed')"
    )
    suspend fun pauseAllTransfers(profile: String)

    @Query(
        "UPDATE offline_transfers SET state='cancelled',reserved=0 WHERE profile=:profile AND state!='complete'"
    )
    suspend fun cancelAllTransfers(profile: String)

    @Query(
        "UPDATE offline_transfers SET state='cancelled',reserved=0,error='access_revoked' WHERE profile=:profile AND item=:item AND state NOT IN ('complete','cancelled')"
    )
    suspend fun cancelTransfersForItem(profile: String, item: String)

    @Query(
        "UPDATE offline_transfers SET state='identity_paused',reserved=0 WHERE profile=:profile AND state NOT IN ('complete','cancelled','failed','paused','identity_paused')"
    )
    suspend fun pauseTransfersForIdentityChange(profile: String)

    @Query(
        "UPDATE offline_transfers SET state='queued' WHERE profile=:profile AND state='identity_paused'"
    )
    suspend fun resumeTransfersAfterIdentityChange(profile: String)

    @Query(
        "UPDATE offline_selections SET state='identity_paused_'||state WHERE profile=:profile AND state IN ('enumerating','ready','queueing','metadata_only')"
    )
    suspend fun pauseSelectionsForIdentityChange(profile: String)

    @Query(
        "UPDATE offline_selections SET state=substr(state,17) WHERE profile=:profile AND state LIKE 'identity_paused_%'"
    )
    suspend fun resumeSelectionsAfterIdentityChange(profile: String)

    @Query(
        "UPDATE offline_selections SET state='paused_'||state WHERE profile=:profile AND state IN ('enumerating','ready','queueing','metadata_only')"
    )
    suspend fun pauseEnumeration(profile: String)

    @Query(
        "UPDATE offline_selections SET state='partial' WHERE profile=:profile AND state IN ('enumerating','ready','queueing','queued','downloading','metadata_only')"
    )
    suspend fun cancelEnumeration(profile: String)

    @Query("DELETE FROM offline_asset_refs WHERE profile=:profile")
    suspend fun deleteAllAssetReferences(profile: String)

    @Query("DELETE FROM offline_transfers WHERE profile=:profile")
    suspend fun deleteAllTransfers(profile: String)

    @Query("DELETE FROM offline_selections WHERE profile=:profile")
    suspend fun deleteAllSelections(profile: String)

    @Query("DELETE FROM offline_members WHERE profile=:profile")
    suspend fun deleteAllMembers(profile: String)

    @Query("DELETE FROM offline_catalog WHERE profile=:profile")
    suspend fun deleteAllCatalog(profile: String)

    @Query("DELETE FROM offline_states WHERE profile=:profile")
    suspend fun deleteAllStates(profile: String)

    @Query("DELETE FROM offline_playback_states WHERE profile=:profile")
    suspend fun deleteAllPlaybackStates(profile: String)

    @Query("DELETE FROM offline_playlists WHERE profile=:profile")
    suspend fun deleteAllPlaylists(profile: String)

    @Query("DELETE FROM offline_playlist_entries WHERE profile=:profile")
    suspend fun deleteAllPlaylistEntries(profile: String)

    @Query("DELETE FROM offline_operations WHERE profile=:profile")
    suspend fun deleteAllOperations(profile: String)

    @Query("DELETE FROM offline_audio_queues WHERE profile=:profile")
    suspend fun deleteAllAudioQueues(profile: String)

    @Query(
        "SELECT (SELECT COUNT(*) FROM offline_selections WHERE profile=:profile AND state IN ('enumerating','ready','queueing','removal_pending','metadata_only')) + (SELECT COUNT(*) FROM offline_selections s WHERE s.profile=:profile AND s.kind='playlist' AND s.state IN ('queued','complete','partial') AND s.error IS NULL AND NOT EXISTS(SELECT 1 FROM offline_playlists p WHERE p.profile=s.profile AND p.id=s.rootId AND p.complete=1))"
    )
    suspend fun pendingSelectionWorkCount(profile: String): Int

    @Query("SELECT COUNT(*) FROM offline_transfers WHERE profile=:profile AND state='preparing'")
    suspend fun preparingCount(profile: String): Int

    @Query(
        "SELECT COALESCE(MIN(nextAttemptMs),0) FROM offline_transfers WHERE profile=:profile AND state='preparing'"
    )
    suspend fun nextPreparationAttempt(profile: String): Long

    @Query(
        "SELECT COALESCE(MIN(nextAttemptMs),0) FROM offline_transfers WHERE profile=:profile AND state IN ('queued','resolving','preparing','transferring','verifying','publishing')"
    )
    suspend fun nextTransferAttempt(profile: String): Long
}

data class OfflineAvailability(val total: Int, val downloaded: Int, val newSourceAvailable: Int = 0)

data class OfflineWatchedCounts(val total: Int, val watched: Int)

data class DownloadTotals(
    val total: Int,
    val complete: Int,
    val totalBytes: Long,
    val downloadedBytes: Long,
)
