package com.zenstream.zenstreammobile.offline

import android.app.NotificationManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.ui.locale.ZenStreamLocale
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Screenshots use production screens; media in the notification is an explicit demo fixture. */
class DownloadNotificationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private val runtime by lazy { OfflineRuntime.get(context) }
    private var previous: ConnectionState? = null
    private val profile = OfflineProfile.identity("notification-ui-test", "user")

    @Before
    fun prepare() = runBlocking {
        previous = runtime.connection.state.first { it.initialized }
        runtime.database
            .offline()
            .putProfile(
                OfflineProfile(
                    profile,
                    "notification-ui-test",
                    "user",
                    "https://example.invalid",
                    "Demo library",
                )
            )
        runtime.connection.selectProfile(profile)
        runtime.connection.setMode(ConnectionMode.OFFLINE)
    }

    @After
    fun restore() = runBlocking {
        context
            .getSystemService(NotificationManager::class.java)
            .cancel(DownloadExecution.NOTIFICATION_ID)
        runtime.database.offline().deleteProfileRecord(profile)
        runtime.connection.selectProfile(previous?.profile)
        previous?.let { runtime.connection.setMode(it.mode) }
        Unit
    }

    @Test
    fun startupExplainsTheBenefitAndCanBeSkipped() {
        var visible by mutableStateOf(true)
        compose.setContent {
            ZenStreamTheme {
                DownloadsScreen(runtime, onOnline = {})
                if (visible) NotificationPermissionDialog(true, true, {}, { visible = false })
            }
        }
        compose
            .onNodeWithText(context.getString(R.string.notification_permission_title))
            .assertIsDisplayed()
        capture("01-startup")
        compose
            .onNodeWithText(context.getString(R.string.notification_permission_not_now))
            .performClick()
        compose
            .onNodeWithText(context.getString(R.string.notification_permission_title))
            .assertDoesNotExist()
    }

    @Test
    fun localizedHostRetainsTheActivityOwnersNeededByPermissionLaunchers() {
        compose.setContent {
            ZenStreamTheme {
                ZenStreamLocale("ja") {
                    OfflineDownloadHost(runtime = null, session = null) { Text("Permission host") }
                }
            }
        }
        compose.onNodeWithText("Permission host").assertIsDisplayed()
    }

    @Test
    fun blockedDownloadExplainsSettingsRecoveryAndShowsPersistentSettingsEntry() {
        var visible by mutableStateOf(true)
        compose.setContent {
            ZenStreamTheme {
                DownloadsScreen(runtime, onOnline = {})
                if (visible) NotificationPermissionDialog(false, false, {}, { visible = false })
            }
        }
        compose
            .onNodeWithText(context.getString(R.string.notification_recovery_settings_message))
            .assertIsDisplayed()
        compose
            .onNodeWithText(context.getString(R.string.download_notification_open_settings))
            .assertIsDisplayed()
        capture("02-recovery-settings")
        compose
            .onNodeWithText(context.getString(R.string.notification_permission_not_now))
            .performClick()
        compose
            .onNodeWithContentDescription(context.getString(R.string.offline_download_settings))
            .performClick()
        compose
            .onNodeWithText(context.getString(R.string.download_notification_settings))
            .performScrollTo()
            .assertIsDisplayed()
        capture("03-download-settings")
    }

    @Test
    fun retryableDenialOffersAnotherPermissionRequest() {
        compose.setContent {
            ZenStreamTheme {
                DownloadsScreen(runtime, onOnline = {})
                NotificationPermissionDialog(false, true, {}, {})
            }
        }
        compose
            .onNodeWithText(context.getString(R.string.notification_recovery_message))
            .assertIsDisplayed()
        compose
            .onNodeWithText(context.getString(R.string.notification_permission_allow))
            .assertIsDisplayed()
        capture("04-recovery-permission")
    }

    @Test
    fun demoPostsTheProductionNotificationToSystemUi() {
        // opt-in because it temporarily opens the notification drawer and grants test-app
        // permission.
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("notificationDemo") == "true"
        )
        if (Build.VERSION.SDK_INT >= 33)
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS,
            )
        compose.setContent { ZenStreamTheme { DownloadsScreen(runtime, onOnline = {}) } }
        val row =
            DownloadNotificationItem(
                "demo",
                "transferring",
                640_000_000,
                1_000_000_000,
                0,
                "Mountains",
                "episode",
                "Season 1",
                "season",
                "Planet Earth",
                1,
                2,
                2,
            )
        val progress =
            DownloadNotificationProgress(
                listOf(row),
                5,
                1,
                DownloadNotificationPhase.DOWNLOADING,
                row.bytes,
                row.total,
                64,
                5_000_000,
                72,
            )
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(
            DownloadExecution.NOTIFICATION_ID,
            DownloadNotifications.build(context, profile, progress),
        )
        shell("cmd statusbar expand-notifications")
        waitForNotification()
        capture("05-progress-style")
        manager.notify(
            DownloadExecution.NOTIFICATION_ID,
            DownloadNotifications.build(context, profile, progress, api = 35),
        )
        waitForNotification()
        capture("06-legacy-progress")
        shell("cmd statusbar collapse")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val file = File(context.getExternalFilesDir(null), "notification-demo/$name.png")
        file.parentFile!!.mkdirs()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun waitForNotification() {
        compose.waitUntil(15_000) {
            context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                it.id == DownloadExecution.NOTIFICATION_ID
            }
        }
        Thread.sleep(4000) // System UI inflation/animations run independently of Compose idling.
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
        }
    }
}
