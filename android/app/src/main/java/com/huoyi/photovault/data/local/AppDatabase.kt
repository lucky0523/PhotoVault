package com.huoyi.photovault.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.huoyi.photovault.data.local.dao.BackupFolderDao
import com.huoyi.photovault.data.local.dao.BackupHistoryDao
import com.huoyi.photovault.data.local.dao.PhotoStatusDao
import com.huoyi.photovault.data.local.dao.QueuedFileDao
import com.huoyi.photovault.data.local.dao.UploadRecordDao
import com.huoyi.photovault.data.local.entity.BackupFolder
import com.huoyi.photovault.data.local.entity.BackupHistoryRecord
import com.huoyi.photovault.data.local.entity.PhotoStatus
import com.huoyi.photovault.data.local.entity.QueuedFile
import com.huoyi.photovault.data.local.entity.UploadRecord

/**
 * Room database for the PhotoVault app.
 * Stores backup folder configurations, storage policies, upload progress records,
 * backup history, and per-file server status (active/trashed/purged) for the
 * recycle bin feature.
 */
@Database(
    entities = [
        BackupFolder::class,
        UploadRecord::class,
        BackupHistoryRecord::class,
        PhotoStatus::class,
        QueuedFile::class
    ],
    version = 9,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun backupFolderDao(): BackupFolderDao
    abstract fun uploadRecordDao(): UploadRecordDao
    abstract fun backupHistoryDao(): BackupHistoryDao
    abstract fun photoStatusDao(): PhotoStatusDao
    abstract fun queuedFileDao(): QueuedFileDao
}
