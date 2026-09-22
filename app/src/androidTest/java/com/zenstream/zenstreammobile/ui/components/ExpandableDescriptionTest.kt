package com.zenstream.zenstreammobile.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Rule
import org.junit.Test

class ExpandableDescriptionTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun shortAndExactlyThreeLineDescriptionsHaveNoToggle() {
        composeRule.setContent {
            ZenStreamTheme {
                Column {
                    ExpandableDescription(
                        description = "Short description",
                        modifier = Modifier.width(320.dp),
                    )
                    ExpandableDescription(
                        description = "First line\nSecond line\nThird line",
                        modifier = Modifier.width(320.dp),
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule
            .onAllNodesWithText(composeRule.activity.getString(R.string.show_more))
            .assertCountEquals(0)
    }

    @Test
    fun overflowingDescriptionExpandsAndCollapses() {
        val description =
            List(6) { line ->
                    "Line ${line + 1} contains enough text to remain part of the full description."
                }
                .joinToString("\n")
        composeRule.setContent {
            ZenStreamTheme {
                ExpandableDescription(description = description, modifier = Modifier.width(320.dp))
            }
        }

        val showMore = composeRule.activity.getString(R.string.show_more)
        val showLess = composeRule.activity.getString(R.string.show_less)
        composeRule.onNodeWithText(showMore).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(showLess).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(showMore).assertIsDisplayed()
    }

    @Test
    fun changingDescriptionResetsExpansionState() {
        val longDescription = List(6) { "Long line ${it + 1}" }.joinToString("\n")
        composeRule.setContent {
            ZenStreamTheme {
                var description by remember { mutableStateOf(longDescription) }
                Column(Modifier.width(320.dp)) {
                    ExpandableDescription(description)
                    TextButton(onClick = { description = "Short description" }) {
                        Text("Change description")
                    }
                }
            }
        }

        val showMore = composeRule.activity.getString(R.string.show_more)
        val showLess = composeRule.activity.getString(R.string.show_less)
        composeRule.onNodeWithText(showMore).performClick()
        composeRule.onNodeWithText(showLess).assertIsDisplayed()
        composeRule.onNodeWithText("Change description").performClick()
        composeRule.onAllNodesWithText(showLess).assertCountEquals(0)
        composeRule.onAllNodesWithText(showMore).assertCountEquals(0)
    }
}
