package com.zenstream.zenstreammobile.offline

import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DownloadBatteryPromptTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun transfersRequireTheSystemBatteryExemption() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val previouslyExempt = downloadBatteryExempt(context)
        fun setExempt(exempt: Boolean) {
            val change = if (exempt) "+" else "-"
            val command = "cmd deviceidle whitelist $change${context.packageName}"
            ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(command)
                )
                .use { it.readBytes() }
        }
        try {
            setExempt(false)
            assertFalse(downloadBatteryExempt(context))
            val continued = mutableIntStateOf(0)
            val played = mutableIntStateOf(0)
            compose.setContent {
                ZenStreamTheme {
                    val requireBatteryExemption = rememberDownloadBatteryGate()
                    Column {
                        TextButton(
                            onClick = { if (requireBatteryExemption()) continued.intValue++ }
                        ) {
                            Text("Queue download")
                        }
                        TextButton(onClick = { played.intValue++ }) {
                            Text("Play downloaded media")
                        }
                        Text("Continued ${continued.intValue}")
                        Text("Played ${played.intValue}")
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
                .onNodeWithText(context.getString(R.string.download_battery_not_now))
                .assertIsDisplayed()
            val scenario = compose.activityRule.scenario
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithText(title).assertIsDisplayed()
            compose
                .onNodeWithText(context.getString(R.string.download_battery_not_now))
                .performClick()
            compose.onNodeWithText(title).assertDoesNotExist()
            compose.onNodeWithText("Continued 0").assertIsDisplayed()
            compose.onNodeWithText("Play downloaded media").performClick()
            compose.onNodeWithText("Played 1").assertIsDisplayed()

            // The required gate appears again only after another explicit download attempt.
            compose.onNodeWithText("Queue download").performClick()
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.onNodeWithText("Continued 0").assertIsDisplayed()
            scenario.moveToState(Lifecycle.State.STARTED)
            setExempt(true)
            assertTrue(downloadBatteryExempt(context))
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithText(title).assertDoesNotExist()
            compose.onNodeWithText("Continued 0").assertIsDisplayed()
            compose.onNodeWithText("Queue download").performClick()
            compose.onNodeWithText("Continued 1").assertIsDisplayed()

            setExempt(false)
            assertFalse(downloadBatteryExempt(context))
            compose.onNodeWithText("Queue download").performClick()
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.onNodeWithText("Continued 1").assertIsDisplayed()
            compose
                .onNodeWithText(context.getString(R.string.download_battery_not_now))
                .performClick()
            compose.onNodeWithText(title).assertDoesNotExist()
            compose.onNodeWithText("Continued 1").assertIsDisplayed()
        } finally {
            setExempt(previouslyExempt)
        }
    }
}
