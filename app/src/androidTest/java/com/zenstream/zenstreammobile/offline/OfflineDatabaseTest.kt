package com.zenstream.zenstreammobile.offline

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OfflineDatabaseTest {
    @Test
    fun transactionsAndAccountIsolationSurviveReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "offline-schema-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, OfflineDatabase::class.java, name).build()
        var db = open()
        try {
            db.offline()
                .putProfile(OfflineProfile("p", "s", "a", "https://example.invalid", "Local"))
            db.offline().putState(OfflineUserState("p", "item", overlay = "{\"played\":true}"))
            assertNull(db.offline().state("other-profile", "item"))
            try {
                db.withTransaction {
                    db.offline().putState(OfflineUserState("p", "rolled-back"))
                    error("rollback")
                }
            } catch (_: IllegalStateException) {}
            assertNull(db.offline().state("p", "rolled-back"))
            db.close()
            db = open()
            assertEquals("{\"played\":true}", db.offline().state("p", "item")?.overlay)
            assertEquals(1L, db.offline().profile("p")?.nextSequence)
            assertEquals(4, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
