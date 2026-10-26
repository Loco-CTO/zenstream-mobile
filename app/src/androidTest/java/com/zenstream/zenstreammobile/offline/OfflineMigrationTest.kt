package com.zenstream.zenstreammobile.offline

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflineMigrationTest {
    @Test
    fun upgradePreservesProfileAndOperationSequence() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "offline-migration-test"
        context.deleteDatabase(name)
        // Read the immutable export with Android JSON; Room validates the upgrade.
        val schema =
            instrumentation.context.assets
                .open(requireNotNull(OfflineDatabase::class.java.canonicalName) + "/1.json")
                .bufferedReader()
                .use { JSONObject(it.readText()).getJSONObject("database") }
        val previous = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                fun sql(value: String): String =
                    value.replace('$'.toString() + "{TABLE_NAME}", entity.getString("tableName"))
                previous.execSQL(sql(entity.getString("createSql")))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (position in 0 until indices.length()) previous.execSQL(
                    sql(indices.getJSONObject(position).getString("createSql"))
                )
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) previous.execSQL(setup.getString(index))
            previous.execSQL(
                "INSERT INTO offline_profiles VALUES('p','instance','account','https://example.invalid','User',0,42,40,3,4,1,0,0,-1)"
            )
            previous.version = 1
        } finally {
            previous.close()
        }
        val current =
            Room.databaseBuilder(context, OfflineDatabase::class.java, name)
                .addMigrations(OfflineDatabase.MIGRATION_1_2, OfflineDatabase.MIGRATION_2_3)
                .build()
        try {
            val profile = current.offline().profile("p")
            assertNotNull(profile)
            assertEquals(42L, profile?.nextSequence)
            assertEquals(3L, profile?.historyEpoch)
            assertEquals(4L, profile?.privacyEpoch)
            assertEquals(3, current.openHelper.readableDatabase.version)
        } finally {
            current.close()
            context.deleteDatabase(name)
        }
    }
}
