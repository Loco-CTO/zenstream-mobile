package com.zenstream.zenstreammobile.offline

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflineDao {
    @Upsert suspend fun putProfile(value: OfflineProfile)

    @Query("SELECT * FROM offline_profiles WHERE id=:id")
    suspend fun profile(id: String): OfflineProfile?

    @Query("SELECT * FROM offline_profiles ORDER BY displayName")
    fun profiles(): Flow<List<OfflineProfile>>

    @Query("SELECT * FROM offline_profiles WHERE serverInstance=:server AND account=:account")
    suspend fun profileFor(server: String, account: String): OfflineProfile?

    @Upsert suspend fun putItems(values: List<OfflineCatalogItem>)

    @Query("SELECT * FROM offline_catalog WHERE profile=:profile AND id=:id")
    suspend fun item(profile: String, id: String): OfflineCatalogItem?

    @Query(
        "SELECT * FROM offline_catalog WHERE profile=:profile AND parentId=:parent ORDER BY season,episode,disc,track,id"
    )
    suspend fun children(profile: String, parent: String): List<OfflineCatalogItem>

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

    @Upsert suspend fun putAsset(value: OfflineAsset)

    @Query("SELECT * FROM offline_assets WHERE profile=:profile AND id=:id")
    suspend fun asset(profile: String, id: String): OfflineAsset?

    @Query(
        "SELECT * FROM offline_assets WHERE profile=:profile AND item=:item AND state='complete' AND locked=0 ORDER BY verifiedAt DESC,id"
    )
    suspend fun playable(profile: String, item: String): List<OfflineAsset>

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

    @Upsert suspend fun putTransfer(value: OfflineTransfer)

    @Query("SELECT * FROM offline_transfers WHERE profile=:profile AND id=:id")
    suspend fun transfer(profile: String, id: String): OfflineTransfer?

    @Query("SELECT * FROM offline_transfers WHERE profile=:profile ORDER BY priority DESC,id")
    fun transfers(profile: String): Flow<List<OfflineTransfer>>

    @Query(
        "SELECT * FROM offline_transfers WHERE profile=:profile AND state IN ('queued','resolving','preparing','transferring','verifying','publishing') AND nextAttemptMs<=:now ORDER BY priority DESC,id LIMIT :limit"
    )
    suspend fun pendingTransfers(profile: String, now: Long, limit: Int): List<OfflineTransfer>

    @Query(
        "SELECT COALESCE(SUM(reserved),0) FROM offline_transfers WHERE state NOT IN ('complete','cancelled','failed')"
    )
    suspend fun reservedBytes(): Long

    @Upsert suspend fun putSupplements(values: List<OfflineSupplement>)

    @Query("SELECT * FROM offline_supplements WHERE profile=:profile AND asset=:asset")
    suspend fun supplements(profile: String, asset: String): List<OfflineSupplement>

    @Upsert suspend fun putState(value: OfflineUserState)

    @Query("SELECT * FROM offline_states WHERE profile=:profile AND target=:target")
    suspend fun state(profile: String, target: String): OfflineUserState?

    @Query("SELECT * FROM offline_states WHERE profile=:profile")
    fun states(profile: String): Flow<List<OfflineUserState>>

    @Upsert suspend fun putPlaylist(value: OfflinePlaylist)

    @Query("SELECT * FROM offline_playlists WHERE profile=:profile AND id=:id")
    suspend fun playlist(profile: String, id: String): OfflinePlaylist?

    @Query("SELECT * FROM offline_playlists WHERE profile=:profile AND deleted=0")
    fun playlists(profile: String): Flow<List<OfflinePlaylist>>

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

    @Query("DELETE FROM offline_playlist_entries WHERE profile=:profile AND playlist=:playlist")
    suspend fun clearPlaylistEntries(profile: String, playlist: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOperation(value: OfflineOperation)

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

    @Query("DELETE FROM offline_assets WHERE profile=:profile AND id=:asset")
    suspend fun deleteAsset(profile: String, asset: String)

    @Query("DELETE FROM offline_supplements WHERE profile=:profile AND asset=:asset")
    suspend fun deleteSupplements(profile: String, asset: String)
}
