package com.huoyi.photovault.ui.main.tabs

import com.huoyi.photovault.data.local.entity.BackupFolder

/**
 * 本地文件夹对应云端状态的分状态展示数量。
 *
 * 反映本地文件在云端的状态分布，四类计数互斥且均为非负整数。
 *
 * @property backedUp LocalBackedUp_Count，本地已备份数量。
 * @property pending LocalPending_Count，本地未备份（待备份）数量。
 * @property trashed LocalTrashed_Count，本地文件在云端处于回收站的数量。
 * @property purged LocalPurged_Count，本地文件在云端已删除的数量。
 */
data class LocalBackupCounts(
    val backedUp: Int,
    val pending: Int,
    val trashed: Int = 0,
    val purged: Int = 0
)

/**
 * 从 [BackupFolder] 的各列派生本地展示所需的分状态数量（对应云端状态）。
 *
 * - backedUp = max(0, backedUpImages)
 * - trashed  = max(0, trashedImages)
 * - purged   = max(0, purgedImages)
 * - pending  = max(0, totalImages - backedUpImages - trashedImages - purgedImages)
 *
 * 纯函数，无副作用；对任意整数输入（含各计数之和大于 totalImages、totalImages == 0）
 * 都产生非负结果，便于属性测试。
 */
fun deriveLocalCounts(
    totalImages: Int,
    backedUpImages: Int,
    trashedImages: Int = 0,
    purgedImages: Int = 0
): LocalBackupCounts {
    val backedUp = backedUpImages.coerceAtLeast(0)
    val trashed = trashedImages.coerceAtLeast(0)
    val purged = purgedImages.coerceAtLeast(0)
    val pending = (totalImages - backedUpImages - trashedImages - purgedImages).coerceAtLeast(0)
    return LocalBackupCounts(
        backedUp = backedUp,
        pending = pending,
        trashed = trashed,
        purged = purged
    )
}

/**
 * [deriveLocalCounts] 的重载，直接从 [BackupFolder] 的
 * totalImages / backedUpImages / trashedImages / purgedImages 列派生。
 */
fun deriveLocalCounts(folder: BackupFolder): LocalBackupCounts =
    deriveLocalCounts(
        totalImages = folder.totalImages,
        backedUpImages = folder.backedUpImages,
        trashedImages = folder.trashedImages,
        purgedImages = folder.purgedImages
    )

/**
 * 计算一次备份成功后 [folder] 的聚合计数应如何更新（纯函数，便于测试）。
 *
 * - [priorStatus] 为 null（未备份文件首次备份）：文件进入「已备份」桶，`backedUpImages + 1`。
 * - [priorStatus] 为 trashed/purged（回收站/已删除文件的重新备份）：`backedUpImages + 1`，
 *   同时移出原桶 `-1`，否则 LocalTab 的 回收站/已删除 计数会继续统计一个已备份的文件。
 * - [priorStatus] 为 active（本地已备份、内容被修改后的重传）：该文件已经计入「已备份」，
 *   三个计数都不变，避免重复 +1；与扫描/[com.huoyi.photovault.service.FolderStatusCountsRefresher]
 *   按 photo_status 全量重算的结果一致。
 *
 * @param priorStatus 上传把状态翻转为 `active` **之前** 的 photo_status 值。
 */
fun applyBackedUpCountDelta(
    folder: BackupFolder,
    priorStatus: String?
): BackupFolder {
    val withBackedUp = folder.copy(backedUpImages = folder.backedUpImages + 1)
    return when (priorStatus) {
        // 已计入已备份（已修改文件重传），不重复 +1
        com.huoyi.photovault.data.local.entity.PhotoStatusValue.ACTIVE -> folder
        com.huoyi.photovault.data.local.entity.PhotoStatusValue.TRASHED -> withBackedUp.copy(
            trashedImages = (folder.trashedImages - 1).coerceAtLeast(0)
        )
        com.huoyi.photovault.data.local.entity.PhotoStatusValue.PURGED -> withBackedUp.copy(
            purgedImages = (folder.purgedImages - 1).coerceAtLeast(0)
        )
        else -> withBackedUp
    }
}
