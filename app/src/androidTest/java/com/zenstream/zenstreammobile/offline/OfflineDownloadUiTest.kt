package com.zenstream.zenstreammobile.offline

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class OfflineDownloadUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun downloadsRemainNavigableWhenExplicitlyOffline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = OfflineRuntime.get(context)
        val profile = OfflineProfile.identity("offline-ui-test", "user")
        val previous = runBlocking { runtime.connection.state.first { it.initialized } }
        runBlocking {
            runtime.database
                .offline()
                .putProfile(
                    OfflineProfile(
                        profile,
                        "offline-ui-test",
                        "user",
                        "https://example.invalid",
                        "Offline test",
                    )
                )
            runtime.connection.selectProfile(profile)
            runtime.connection.setMode(ConnectionMode.AUTOMATIC)
        }
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                compose.activity.window.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )
            }
            compose.setContent { ZenStreamTheme { DownloadsScreen(runtime, onOnline = {}) } }
            compose
                .onNodeWithText(context.getString(R.string.offline_downloads))
                .assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.settings)).performClick()
            compose
                .onNodeWithContentDescription(context.getString(R.string.offline_use_mode))
                .performClick()
            compose.waitUntil(5000) {
                runtime.connection.state.value.mode == ConnectionMode.OFFLINE
            }
            compose
                .onNodeWithText(context.getString(R.string.offline_mode_active))
                .assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.offline_transfers)).performClick()
            compose
                .onNodeWithText(context.getString(R.string.offline_resume_all))
                .assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.offline_saved)).performClick()
            compose.onNodeWithText(context.getString(R.string.offline_empty)).assertIsDisplayed()
            runBlocking {
                val retained = requireNotNull(runtime.database.offline().profile(profile))
                runtime.database.offline().putProfile(retained.copy(locked = true))
            }
            compose.waitUntil(5000) {
                runCatching {
                        compose
                            .onNodeWithText(context.getString(R.string.offline_locked))
                            .assertIsDisplayed()
                    }
                    .isSuccess
            }
            runBlocking {
                runtime.database.offline().deleteProfileRecord(profile)
            }
            compose.waitUntil(5000) {
                runCatching {
                        compose
                            .onNodeWithText(context.getString(R.string.offline_empty))
                            .assertIsDisplayed()
                    }
                    .isSuccess
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                compose.activity.window.clearFlags(
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )
            }
            runBlocking {
                runtime.connection.setMode(previous.mode)
                runtime.connection.selectProfile(previous.profile)
                runtime.database.offline().deleteProfileRecord(profile)
            }
        }
    }
}
