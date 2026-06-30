package com.photovault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a backup folder configuration.
 * Stores folder URI, storage policy settings, and backup progress.
 */
@Entity(tableName = "backup_folders")
data class BackupFolder(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "folder_uri")
    val folderUri: String,

    @ColumnInfo(name = "folder_name")
    val folderName: String,

    @ColumnInfo(name = "use_custom_path")
    val useCustomPath: Boolean = false,

    @ColumnInfo(name = "custom_path")
    val customPath: String? = null,

    @ColumnInfo(name = "use_year_month_layer")
    val useYearMonthLayer: Boolean = false,

    @ColumnInfo(name = "total_images")
    val totalImages: Int = 0,

    @ColumnInfo(name = "backed_up_images")
    val backedUpImages: Int = 0,

    @ColumnInfo(name = "last_scan_time")
    val lastScanTime: Long = 0L
)
