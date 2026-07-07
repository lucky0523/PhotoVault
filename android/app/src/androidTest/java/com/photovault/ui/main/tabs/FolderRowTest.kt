package com.photovault.ui.main.tabs

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.photovault.data.local.entity.BackupFolder
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI 组件测试：验证 [FolderRow] 的本地文件云端状态展示。
 *
 * 覆盖需求：
 * - 1.1 / 1.4：FolderRow 展示本地文件对应云端的「已备份」「未备份」「回收站」「已删除」
 *   四枚 chip，且四项计数从 BackupFolder 现有列派生、互斥。
 * - 1.6：当 totalImages == 0 时，四项数量均展示为 0。
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
    fun folderRow_rendersAllFourCloudStatusChips() {
        // total=10, backedUp=6, trashed=2, purged=1 → pending = 10-6-2-1 = 1
        setFolderRow(folder(totalImages = 10, backedUpImages = 6, trashedImages = 2, purgedImages = 1))

        // All four chips present. StatusChip renders "$label $count" as one node.
        composeRule.onNodeWithText("已备份 6").assertIsDisplayed()
        composeRule.onNodeWithText("未备份 1").assertIsDisplayed()
        composeRule.onNodeWithText("回收站 2").assertIsDisplayed()
        composeRule.onNodeWithText("已删除 1").assertIsDisplayed()
    }

    @Test
    fun folderRow_totalImagesZero_showsAllCountsAsZero() {
        setFolderRow(folder(totalImages = 0, backedUpImages = 0))

        composeRule.onNodeWithText("已备份 0").assertIsDisplayed()
        composeRule.onNodeWithText("未备份 0").assertIsDisplayed()
        composeRule.onNodeWithText("回收站 0").assertIsDisplayed()
        composeRule.onNodeWithText("已删除 0").assertIsDisplayed()
    }
}
