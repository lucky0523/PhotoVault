package com.photovault.ui.main.tabs

import com.photovault.data.local.entity.BackupFolder

/**
 * 本地文件夹的两项展示数量。
 *
 * @property backedUp LocalBackedUp_Count，本地已备份数量。
 * @property pending LocalPending_Count，本地未备份（待备份）数量。
 */
data class LocalBackupCounts(
    val backedUp: Int,
    val pending: Int
)

/**
 * 从 [BackupFolder] 的现有列派生本地展示所需的两项数量。
 *
 * - backedUp = max(0, backedUpImages)
 * - pending  = max(0, totalImages - backedUpImages)
 *
 * 纯函数，无副作用；对任意整数输入（含 backedUpImages > totalImages、totalImages == 0）
 * 都产生非负结果，便于属性测试。
 */
fun deriveLocalCounts(totalImages: Int, backedUpImages: Int): LocalBackupCounts {
    val backedUp = backedUpImages.coerceAtLeast(0)
    val pending = (totalImages - backedUpImages).coerceAtLeast(0)
    return LocalBackupCounts(backedUp = backedUp, pending = pending)
}

/**
 * [deriveLocalCounts] 的重载，直接从 [BackupFolder] 的 totalImages 与 backedUpImages 列派生。
 */
fun deriveLocalCounts(folder: BackupFolder): LocalBackupCounts =
    deriveLocalCounts(folder.totalImages, folder.backedUpImages)
