package com.zenstream.zenstreammobile.offline

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.MessageDigest

/** Credentials and signed playback/transfer URLs never belong in this database. */
@Entity(
    tableName = "offline_profiles",
    indices = [Index(value = ["serverInstance", "account"], unique = true)],
)
data class OfflineProfile(
    @PrimaryKey val id: String,
    val serverInstance: String,
    val account: String,
    val serverUrl: String,
    val displayName: String,
    val locked: Boolean = false,
    val nextSequence: Long = 1,
    val acknowledgedSequence: Long = 0,
    val historyEpoch: Long = 0,
    val privacyEpoch: Long = 0,
    val historyEnabled: Boolean = true,
    val anchorServerMs: Long = 0,
    val anchorElapsedMs: Long = 0,
    val anchorBoot: Long = -1,
) {
    companion object {
        fun identity(serverInstance: String, account: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("$serverInstance\u0000$account".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

@Entity(
    tableName = "offline_catalog",
    primaryKeys = ["profile", "id"],
    indices = [Index(value = ["profile", "parentId"]), Index(value = ["profile", "type", "name"])],
)
data class OfflineCatalogItem(
    val profile: String,
    val id: String,
    val libraryId: String?,
    val parentId: String?,
    val type: String,
    val name: String,
    val season: Int = 0,
    val episode: Int = 0,
    val episodeEnd: Int = 0,
    val disc: Int = 0,
    val track: Int = 0,
    val payload: String,
    val presentationRevision: String = "",
    val unavailable: Boolean = false,
    val newSourceAvailable: Boolean = false,
)

@Entity(tableName = "offline_selections", primaryKeys = ["profile", "id"])
data class OfflineSelection(
    val profile: String,
    val id: String,
    val rootId: String,
    val kind: String,
    val title: String,
    val options: String,
    val state: String = "enumerating",
    val cursor: Int = 0,
    val itemCount: Int = 0,
    val error: String? = null,
)

@Entity(
    tableName = "offline_members",
    primaryKeys = ["profile", "selection", "item"],
    indices = [Index(value = ["profile", "item"])],
)
data class OfflineMember(
    val profile: String,
    val selection: String,
    val item: String,
    val ordinal: Int,
)

@Entity(
    tableName = "offline_assets",
    primaryKeys = ["profile", "id"],
    indices = [Index(value = ["profile", "item"])],
)
data class OfflineAsset(
    val profile: String,
    val id: String,
    val item: String,
    val quality: String,
    val sourceId: String,
    val sourceRevision: String,
    val size: Long,
    val sha256: String,
    val mimeType: String,
    val descriptor: String,
    val relativePath: String,
    val state: String = "staged",
    val verifiedAt: Long = 0,
    val locked: Boolean = false,
)

@Entity(
    tableName = "offline_asset_refs",
    primaryKeys = ["profile", "selection", "asset"],
    indices = [Index(value = ["profile", "asset"])],
)
data class OfflineAssetReference(val profile: String, val selection: String, val asset: String)

@Entity(
    tableName = "offline_transfers",
    primaryKeys = ["profile", "id"],
    indices =
        [
            Index(value = ["profile", "state", "priority"]),
            Index(value = ["profile", "selection", "state"]),
        ],
)
data class OfflineTransfer(
    val profile: String,
    val id: String,
    val selection: String,
    val item: String,
    val options: String,
    val state: String = "queued",
    val assetId: String? = null,
    val descriptor: String? = null,
    val bytes: Long = 0,
    val total: Long = 0,
    val etag: String? = null,
    val attempt: Int = 0,
    val nextAttemptMs: Long = 0,
    val priority: Int = 0,
    val error: String? = null,
    val reserved: Long = 0,
)

@Entity(tableName = "offline_supplements", primaryKeys = ["profile", "asset", "key"])
data class OfflineSupplement(
    val profile: String,
    val asset: String,
    val key: String,
    val relativePath: String,
    val revision: String,
    val descriptor: String,
)

@Entity(tableName = "offline_states", primaryKeys = ["profile", "target"])
data class OfflineUserState(
    val profile: String,
    val target: String,
    val canonical: String = "{}",
    val overlay: String = "{}",
    val revisions: String = "{}",
    val localOnlyResume: String? = null,
)

@Entity(
    tableName = "offline_playback_states",
    primaryKeys = ["profile", "target"],
    indices = [Index(value = ["profile", "played", "activityMs"])],
)
data class OfflinePlaybackState(
    val profile: String,
    val target: String,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val played: Boolean = false,
    val activityMs: Long = 0,
    val previousEpisodeId: String? = null,
    val nextEpisodeId: String? = null,
)

@Entity(tableName = "offline_playlists", primaryKeys = ["profile", "id"])
data class OfflinePlaylist(
    val profile: String,
    val id: String,
    val metadata: String,
    val revision: String = "",
    val complete: Boolean = false,
    val expectedCount: Int = 0,
    val cursor: Int = 0,
    val deleted: Boolean = false,
)

@Entity(
    tableName = "offline_playlist_entries",
    primaryKeys = ["profile", "playlist", "track"],
    indices = [Index(value = ["profile", "playlist", "position"])],
)
data class OfflinePlaylistEntry(
    val profile: String,
    val playlist: String,
    val track: String,
    val entryId: String,
    val position: Int,
)

@Entity(
    tableName = "offline_operations",
    primaryKeys = ["profile", "sequence"],
    indices = [Index(value = ["id"], unique = true), Index(value = ["profile", "target"])],
)
data class OfflineOperation(
    val profile: String,
    val sequence: Long,
    val id: String,
    val target: String,
    val kind: String,
    val activityMs: Long,
    val clockTrusted: Boolean,
    val baseRevision: Long,
    val historyEpoch: Long,
    val privacyEpoch: Long,
    val payload: String,
    val inFlight: Boolean = false,
)

@Entity(tableName = "offline_audio_queues")
data class OfflineAudioQueue(
    @PrimaryKey val profile: String,
    val payload: String,
    val updatedAt: Long,
)

@Database(
    entities =
        [
            OfflineProfile::class,
            OfflineCatalogItem::class,
            OfflineSelection::class,
            OfflineMember::class,
            OfflineAsset::class,
            OfflineAssetReference::class,
            OfflineTransfer::class,
            OfflineSupplement::class,
            OfflineUserState::class,
            OfflinePlaybackState::class,
            OfflinePlaylist::class,
            OfflinePlaylistEntry::class,
            OfflineOperation::class,
            OfflineAudioQueue::class,
        ],
    version = 4,
    exportSchema = true,
)
abstract class OfflineDatabase : RoomDatabase() {
    abstract fun offline(): OfflineDao

    companion object {
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_offline_transfers_profile_selection_state ON offline_transfers(profile,selection,state)"
                    )
                }
            }
        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS offline_audio_queues (profile TEXT NOT NULL, payload TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(profile))"
                    )
                }
            }
        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS offline_playback_states (profile TEXT NOT NULL, target TEXT NOT NULL, positionSeconds REAL NOT NULL, durationSeconds REAL NOT NULL, played INTEGER NOT NULL, activityMs INTEGER NOT NULL, previousEpisodeId TEXT, nextEpisodeId TEXT, PRIMARY KEY(profile, target))"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_offline_playback_states_profile_played_activityMs ON offline_playback_states(profile, played, activityMs)"
                    )
                }
            }
        @Volatile private var instance: OfflineDatabase? = null

        fun get(context: Context): OfflineDatabase =
            instance
                ?: synchronized(this) {
                    instance
                        ?: Room.databaseBuilder(
                                context.applicationContext,
                                OfflineDatabase::class.java,
                                "offline.db",
                            )
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                            .build()
                            .also { instance = it }
                }
    }
}
