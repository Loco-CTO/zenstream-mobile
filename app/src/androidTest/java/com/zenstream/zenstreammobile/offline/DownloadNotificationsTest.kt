package com.zenstream.zenstreammobile.offline

import android.app.Notification
import android.os.Build
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DownloadNotificationsTest {
    private companion object {
        const val MEDIA_TITLE = "Mountains"
        const val MEDIA_TYPE = "episode"
        const val SEASON_ID = "season"
        const val SERIES_TITLE = "Planet Earth"
        const val NOTIFICATION_PROFILE = "test-profile"
        const val SERIES_ID = "series"
        const val QUEUED_STATE = "queued"
    }

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val item =
        DownloadNotificationItem(
            "transfer",
            "transferring",
            640_000_000,
            1_000_000_000,
            0,
            MEDIA_TITLE,
            MEDIA_TYPE,
            "Season 1",
            SEASON_ID,
            SERIES_TITLE,
            1,
            2,
            2,
        )

    @Test
    fun modernAndLegacyNotificationsKeepMediaDetailsAndActions() {
        val progress =
            DownloadNotificationProgress(
                listOf(item),
                5,
                1,
                DownloadNotificationPhase.DOWNLOADING,
                item.bytes,
                item.total,
                64,
                5_000_000,
                72,
            )
        val notification = DownloadNotifications.build(context, NOTIFICATION_PROFILE, progress)
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        assertTrue(title.contains(SERIES_TITLE))
        assertTrue(title.contains(MEDIA_TITLE))
        assertTrue(
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("64")
        )
        assertEquals(2, notification.actions.size)
        if (Build.VERSION.SDK_INT >= 31)
            assertTrue(notification.actions.all { it.actionIntent.isImmutable })
        assertEquals(Notification.CATEGORY_PROGRESS, notification.category)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        if (Build.VERSION.SDK_INT >= 36)
            assertEquals(
                "android.app.Notification\$ProgressStyle",
                notification.extras.getString(Notification.EXTRA_TEMPLATE),
            )
        val legacy = DownloadNotifications.build(context, NOTIFICATION_PROFILE, progress, api = 35)
        assertEquals(64, legacy.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(100, legacy.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertFalse(legacy.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertTrue(
            legacy.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("64")
        )
        val preparing = DownloadNotifications.build(context, NOTIFICATION_PROFILE, api = 35)
        assertTrue(preparing.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test
    fun notificationQueriesStayBoundedAndExcludeHistoricalOrOtherAccountTransfers() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        try {
            val dao = database.offline()
            val transfer =
                OfflineTransfer(
                    "p",
                    "first",
                    "selection",
                    MEDIA_TYPE,
                    "{}",
                    state = "transferring",
                    bytes = 640,
                    total = 1000,
                )
            dao.putItems(
                listOf(
                    OfflineCatalogItem(
                        "p",
                        SERIES_ID,
                        null,
                        null,
                        SERIES_ID,
                        SERIES_TITLE,
                        payload = "{}",
                    ),
                    OfflineCatalogItem(
                        "p",
                        SEASON_ID,
                        null,
                        SERIES_ID,
                        SEASON_ID,
                        "Season 1",
                        payload = "{}",
                    ),
                    OfflineCatalogItem(
                        "p",
                        MEDIA_TYPE,
                        null,
                        SEASON_ID,
                        MEDIA_TYPE,
                        MEDIA_TITLE,
                        season = 1,
                        episode = 2,
                        payload = "{}",
                    ),
                )
            )
            dao.putTransfer(transfer)
            dao.putTransfer(transfer.copy(id = "second", state = "verifying"))
            dao.putTransfer(transfer.copy(id = QUEUED_STATE, state = QUEUED_STATE))
            dao.putTransfer(transfer.copy(id = "retry", state = QUEUED_STATE, nextAttemptMs = 5000))
            for (state in
                listOf("complete", "cancelled", "paused", "failed", "identity_paused")) dao
                .putTransfer(transfer.copy(id = state, state = state))
            dao.putTransfer(transfer.copy(profile = "other", id = "other"))
            assertEquals(DownloadNotificationQueue(4, 2), dao.notificationQueue("p", 1000))
            val rows = dao.notificationItems("p", 1000)
            assertEquals(listOf("first", "second"), rows.map { it.id })
            assertEquals(SERIES_TITLE, rows.first().seriesName)
            assertEquals(MEDIA_TITLE, rows.first().name)
            assertEquals(DownloadNotificationQueue(0, 0), dao.notificationQueue("empty", 1000))
        } finally {
            database.close()
        }
    }
}
