package com.zenstream.zenstreammobile.offline

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OfflineMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            OfflineDatabase::class.java,
        )

    @Test
    fun upgradePreservesProfileAndOperationSequence() {
        val name = "offline-migration-test"
        helper.createDatabase(name, 1).apply {
            execSQL(
                "INSERT INTO offline_profiles VALUES('p','instance','account','https://example.invalid','User',0,42,40,3,4,1,0,0,-1)"
            )
            close()
        }
        helper.runMigrationsAndValidate(name, 2, true, OfflineDatabase.MIGRATION_1_2).use { db ->
            db.query(
                    "SELECT nextSequence,historyEpoch,privacyEpoch FROM offline_profiles WHERE id='p'"
                )
                .use {
                    assertTrue(it.moveToFirst())
                    assertEquals(42, it.getInt(0))
                    assertEquals(3, it.getInt(1))
                    assertEquals(4, it.getInt(2))
                }
        }
    }
}
