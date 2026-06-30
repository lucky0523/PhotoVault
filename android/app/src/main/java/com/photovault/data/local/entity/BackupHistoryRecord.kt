package com.photovault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for backup history records.
 *
 * Each record represents the result of a backup attempt:
 * - SUCCESS: file was successfully backed up
 * - FAILED: backup failed (may be retried)
 * - SKIPPED: file was a duplicate and skipped
 */
@Entity(tableName = "backup_history")
data class BackupHistoryRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "file_uri")
    val fileUri: String,

    @ColumnInfo(name = "file_name")
    val fileName: String,

    @ColumnInfo(name = "file_size")
    val fileSize: Long,

    @ColumnInfo(name = "status")
    val status: BackupStatus,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,

    @ColumnInfo(name = "folder_uri")
    val folderUri: String,

    @ColumnInfo(name = "completed_at")
    val completedAt: Long = System.currentTimeMillis()
)

/**
 * Status of a backup record.
 */
enum class BackupStatus {
    SUCCESS,
    FAILED,
    SKIPPED
}
