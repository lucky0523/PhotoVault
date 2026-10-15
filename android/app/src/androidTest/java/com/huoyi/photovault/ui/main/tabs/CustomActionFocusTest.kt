package com.huoyi.photovault.ui.main.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.huoyi.photovault.ui.theme.PhotoVaultTheme
import com.huoyi.photovault.ui.theme.countColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class CustomActionFocusTest {
    @get:Rule val rule = createComposeRule()

    @Test fun settingsBackgroundActionHasPaintedFocusAndEnterActivation() {
        var opens = 0
        rule.setContent {
            PhotoVaultTheme(darkTheme = false) {
                Capture { BackgroundRunActionRow(false, { opens++ }) }
            }
        }
        verifyPaintedFocus("settings_background_run_action")
        rule.onNodeWithTag("settings_background_run_action").performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun allowedSettingsBackgroundStateIsNotFocusable() {
        rule.setContent {
            PhotoVaultTheme(darkTheme = false) {
                Capture { BackgroundRunActionRow(true, {}) }
            }
        }
        val requested = runCatching {
            rule.onNodeWithTag("settings_background_run_action")
                .performSemanticsAction(SemanticsActions.RequestFocus)
        }
        assertTrue("allowed state remains non-focusable", requested.isFailure)
    }

    @Test fun cloudTrashEntryHasPaintedFocusAndEnterActivation() {
        var opens = 0
        rule.setContent {
            PhotoVaultTheme(darkTheme = true) {
                Capture { TrashEntryRow(3, { opens++ }) }
            }
        }
        verifyPaintedFocus("cloud_trash_entry")
        rule.onNodeWithTag("cloud_trash_entry").performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun tasksPausedItemHasPaintedFocusAndKeyboardPath() {
        rule.setContent {
            PhotoVaultTheme(darkTheme = true) {
                Capture {
                    PausedTaskItem(
                        task = PausedTaskUi("content://fixture", "生产任务.jpg", 50, 0),
                        onResume = {},
                        onLongPress = {}
                    )
                }
            }
        }
        verifyPaintedFocus("tasks_paused_item")
        rule.onNodeWithTag("tasks_paused_item").performKeyInput { pressKey(Key.Enter) }
    }

    private fun verifyPaintedFocus(tag: String) {
        val before = rule.onNodeWithTag("custom_action_capture").captureToImage()
        rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        val focused = rule.onNodeWithTag("custom_action_capture").captureToImage()
        assertTrue("$tag white halo", focused.countColor(Color.White) > before.countColor(Color.White))
        assertTrue("$tag black halo", focused.countColor(Color.Black) > before.countColor(Color.Black))
    }

    @androidx.compose.runtime.Composable
    private fun Capture(content: @androidx.compose.runtime.Composable () -> Unit) {
        Box(
            Modifier
                .background(Color(0xFF777777))
                .padding(8.dp)
                .testTag("custom_action_capture")
        ) { content() }
    }
}
