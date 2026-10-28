package com.zenstream.zenstreammobile.offline

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
            compose
                .onNodeWithContentDescription(context.getString(R.string.offline_download_settings))
                .performClick()
            compose
                .onNodeWithContentDescription(context.getString(R.string.offline_use_mode))
                .performClick()
            compose.waitUntil(5000) {
                runtime.connection.state.value.mode == ConnectionMode.OFFLINE
            }
            compose
                .onNodeWithText(context.getString(R.string.offline_mode_label))
                .assertIsDisplayed()
            compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            compose
                .onNodeWithText(context.getString(R.string.offline_manage_downloads))
                .performClick()
            compose
                .onNodeWithText(context.getString(R.string.offline_resume_all))
                .assertIsDisplayed()
            compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            compose.waitUntil(5000) {
                runCatching {
                        compose
                            .onNodeWithText(context.getString(R.string.offline_empty))
                            .assertIsDisplayed()
                    }
                    .isSuccess
            }
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
            val remaining = runBlocking { runtime.database.offline().profiles().first() }
            compose.waitUntil(5000) {
                runCatching {
                        if (remaining.any { !it.locked }) {
                            compose
                                .onNodeWithText(context.getString(R.string.offline_locked))
                                .assertDoesNotExist()
                            compose
                                .onNodeWithText(context.getString(R.string.offline_downloads))
                                .assertIsDisplayed()
                        } else {
                            compose
                                .onNodeWithText(
                                    context.getString(
                                        if (remaining.isEmpty()) R.string.offline_empty
                                        else R.string.offline_locked
                                    )
                                )
                                .assertIsDisplayed()
                        }
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

    @Test
    fun groupedTitlesOpenLocallyAndBackRestoresMusicFilterAfterRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = OfflineRuntime.get(context)
        val dao = runtime.database.offline()
        val id = OfflineProfile.identity("offline-navigation-test", "user")
        val previous = runBlocking { runtime.connection.state.first { it.initialized } }
        val restoration = StateRestorationTester(compose)
        var played: String? = null
        val items =
            listOf(
                Triple("series", "series", null),
                Triple("season", "season", "series"),
                Triple("episode", "episode", "season"),
                Triple("album", "release", null),
                Triple("track", "track", "album"),
            )
        runBlocking {
            dao.putProfile(
                OfflineProfile(
                    id,
                    "offline-navigation-test",
                    "user",
                    "https://example.invalid",
                    "Navigation test",
                )
            )
            items.forEach { (item, type, parent) ->
                val payload =
                    JSONObject()
                        .put("id", item)
                        .put("name", item)
                        .put("type", type)
                        .put("parentId", parent)
                        .put("seasonNumber", 1)
                        .put("episodeNumber", 1)
                dao.putItems(
                    listOf(
                        OfflineCatalogItem(
                            id,
                            item,
                            null,
                            parent,
                            type,
                            item,
                            season = 1,
                            episode = 1,
                            payload = payload.toString(),
                        )
                    )
                )
            }
            listOf("episode", "track").forEach { item ->
                dao.putAsset(
                    OfflineAsset(
                        id,
                        "asset-$item",
                        item,
                        "original",
                        "source",
                        "r",
                        1,
                        "hash",
                        "video/mp4",
                        "{}",
                        "path",
                        state = "complete",
                    )
                )
            }
            runtime.connection.selectProfile(id)
            runtime.connection.setMode(ConnectionMode.OFFLINE)
        }
        try {
            restoration.setContent {
                ZenStreamTheme {
                    DownloadsScreen(
                        runtime,
                        onOnline = {},
                        onItem = { _, item -> played = item.id },
                    )
                }
            }
            compose.waitUntil(10000) {
                runCatching { compose.onNodeWithText("series").assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithText("track").assertDoesNotExist()
            compose.onNodeWithText("season").assertDoesNotExist()
            compose.onNodeWithText("series").performClick()
            compose.waitUntil(10000) {
                runCatching {
                        compose
                            .onNodeWithContentDescription(
                                context.getString(R.string.play_description, "episode")
                            )
                            .assertIsDisplayed()
                    }
                    .isSuccess
            }
            compose
                .onNodeWithText(context.getString(R.string.offline_season_number, 1))
                .assertIsDisplayed()
            compose
                .onNodeWithContentDescription(
                    context.getString(R.string.play_description, "episode")
                )
                .performClick()
            compose.runOnIdle { assertEquals("episode", played) }
            compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            compose.onNodeWithText(context.getString(R.string.offline_music)).performClick()
            compose.waitUntil(10000) {
                runCatching { compose.onNodeWithText("album").assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithText("series").assertDoesNotExist()
            compose.onNodeWithText("album").performClick()
            compose.waitUntil(10000) {
                runCatching { compose.onNodeWithText("track").assertIsDisplayed() }.isSuccess
            }
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(10000) {
                runCatching { compose.onNodeWithText("track").assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithText(context.getString(R.string.play_all)).performClick()
            compose.waitUntil(5000) { played == "track" }
            compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            compose.waitUntil(10000) {
                runCatching { compose.onNodeWithText("album").assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithText("series").assertDoesNotExist()
            compose
                .onNodeWithContentDescription(context.getString(R.string.offline_search))
                .performClick()
            compose
                .onNodeWithText(context.getString(R.string.offline_search))
                .performTextInput("track")
            compose.waitUntil(10000) {
                runCatching {
                        compose
                            .onNodeWithContentDescription(
                                context.getString(R.string.play_description, "track")
                            )
                            .assertIsDisplayed()
                    }
                    .isSuccess
            }
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText(context.getString(R.string.offline_search)).assertDoesNotExist()
            compose
                .onNodeWithText(context.getString(R.string.offline_downloads))
                .assertIsDisplayed()
        } finally {
            runBlocking {
                runtime.connection.setMode(previous.mode)
                runtime.connection.selectProfile(previous.profile)
                listOf("episode", "track").forEach { dao.deleteAsset(id, "asset-$it") }
                dao.deleteAllCatalog(id)
                dao.deleteProfileRecord(id)
            }
        }
    }
}
