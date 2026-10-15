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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.huoyi.photovault.data.local.entity.BackupFolder
import com.huoyi.photovault.ui.theme.PhotoVaultTheme
import com.huoyi.photovault.ui.theme.countColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class FolderRowTest {
    @get:Rule val composeRule = createComposeRule()

    private fun folder(
        totalImages: Int,
        backedUpImages: Int,
        trashedImages: Int = 0,
        purgedImages: Int = 0
    ): BackupFolder = BackupFolder(
        id = 1L,
        folderUri = "content://test/folder",
        folderName = "测试文件夹",
        totalImages = totalImages,
        backedUpImages = backedUpImages,
        trashedImages = trashedImages,
        purgedImages = purgedImages,
        lastScanTime = 0L
    )

    private fun setFolderRow(folder: BackupFolder, dark: Boolean = false, onClick: () -> Unit = {}) {
        composeRule.setContent {
            PhotoVaultTheme(darkTheme = dark) {
                Box(Modifier.background(Color(0xFF777777)).padding(8.dp).testTag("folder_capture")) {
                    FolderRow(
                        folder = folder,
                        onClick = onClick,
                        onConfigurePolicy = {},
                        onRemove = {}
                    )
                }
            }
        }
    }

    @Test fun folderRowRendersAllFourCloudStatusChips() {
        setFolderRow(folder(totalImages = 10, backedUpImages = 6, trashedImages = 2, purgedImages = 1))
        composeRule.onNodeWithText("已备份 6").assertExists()
        composeRule.onNodeWithText("未备份 1").assertExists()
        composeRule.onNodeWithText("回收站 2").assertExists()
        composeRule.onNodeWithText("已删除 1").assertExists()
    }

    @Test fun folderRowTotalImagesZeroShowsAllCountsAsZero() {
        setFolderRow(folder(totalImages = 0, backedUpImages = 0))
        composeRule.onNodeWithText("已备份 0").assertExists()
        composeRule.onNodeWithText("未备份 0").assertExists()
        composeRule.onNodeWithText("回收站 0").assertExists()
        composeRule.onNodeWithText("已删除 0").assertExists()
    }

    @Test fun lightFolderRowUsesPaintedFocusAndEnter() = verifyFocus(false)

    @Test fun darkFolderRowUsesPaintedFocusAndEnter() = verifyFocus(true)

    private fun verifyFocus(dark: Boolean) {
        var clicks = 0
        setFolderRow(folder(10, 10), dark) { clicks++ }
        val before = composeRule.onNodeWithTag("folder_capture").captureToImage()
        composeRule.onNodeWithTag("local_folder_row")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        val focused = composeRule.onNodeWithTag("folder_capture").captureToImage()
        assertTrue(focused.countColor(Color.White) > before.countColor(Color.White))
        assertTrue(focused.countColor(Color.Black) > before.countColor(Color.Black))
        composeRule.onNodeWithTag("local_folder_row").performKeyInput { pressKey(Key.Enter) }
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }
}
