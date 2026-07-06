package com.photovault.ui.main.tabs

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.photovault.data.local.entity.BackupFolder
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI 组件测试：验证 [FolderRow] 的本地备份状态展示。
 *
 * 覆盖需求：
 * - 1.1 / 1.4：FolderRow 仅展示本地相关的「已备份」「未备份」两枚 chip，
 *   不展示任何云端状态（回收站、已删除）。
 * - 1.6：当 totalImages == 0 时，两项数量均展示为 0。
 *
 * 注意：这些是 Android 仪器化（androidTest）Compose 测试，需要连接的设备/模拟器才能运行。
 */
class FolderRowTest {

    @get:Rule
    val composeRule = createComposeRule()

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

    private fun setFolderRow(folder: BackupFolder) {
        composeRule.setContent {
            MaterialTheme {
                FolderRow(
                    folder = folder,
                    onClick = {},
                    onConfigurePolicy = {},
                    onRemove = {}
                )
            }
        }
    }

    @Test
    fun folderRow_rendersOnlyLocalChips_noCloudStatusChips() {
        // Even when the folder carries cloud-side counts (trashed / purged),
        // FolderRow must not surface them.
        setFolderRow(folder(totalImages = 10, backedUpImages = 6, trashedImages = 2, purgedImages = 1))

        // Local chips are present. StatusChip renders "$label $count" as one node.
        composeRule.onNodeWithText("已备份 6").assertIsDisplayed()
        composeRule.onNodeWithText("未备份 4").assertIsDisplayed()

        // No cloud status chips of any kind.
        composeRule.onNodeWithText("回收站", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("已删除", substring = true).assertDoesNotExist()
    }

    @Test
    fun folderRow_totalImagesZero_showsBothCountsAsZero() {
        setFolderRow(folder(totalImages = 0, backedUpImages = 0))

        composeRule.onNodeWithText("已备份 0").assertIsDisplayed()
        composeRule.onNodeWithText("未备份 0").assertIsDisplayed()
    }
}
