package com.zenstream.zenstreammobile.offline

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.content.edit
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Rule
import org.junit.Test

class DownloadBatteryPromptTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun firstDownloadPromptsOnceAcrossHostRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("download-battery", Context.MODE_PRIVATE)
        val previous = preferences.getBoolean("prompt-shown", false)
        preferences.edit(commit = true) { remove("prompt-shown") }
        try {
            val mount = mutableIntStateOf(0)
            val continued = mutableIntStateOf(0)
            compose.setContent {
                ZenStreamTheme {
                    key(mount.intValue) {
                        val request = rememberDownloadBatteryPrompt { continued.intValue++ }
                        Column {
                            TextButton(onClick = { if (!request()) continued.intValue++ }) {
                                Text("Queue download")
                            }
                            Text("Continued ${continued.intValue}")
                        }
                    }
                }
            }
            val title = context.getString(R.string.download_battery_title)
            compose.onNodeWithText(title).assertDoesNotExist()
            compose.onNodeWithText("Queue download").performClick()
            compose.onNodeWithText(title).assertIsDisplayed()
            compose
                .onNodeWithText(context.getString(R.string.download_battery_settings))
                .assertIsDisplayed()
            compose
                .onNodeWithText(context.getString(R.string.notification_permission_not_now))
                .performClick()
            compose.onNodeWithText("Continued 1").assertIsDisplayed()
            compose.runOnIdle { mount.intValue++ }
            compose.onNodeWithText("Queue download").performClick()
            compose.onNodeWithText(title).assertDoesNotExist()
            compose.onNodeWithText("Continued 2").assertIsDisplayed()
        } finally {
            preferences.edit(commit = true) { putBoolean("prompt-shown", previous) }
        }
    }
}
