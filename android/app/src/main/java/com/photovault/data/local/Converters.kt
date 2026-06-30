package com.photovault.data.local

import androidx.room.TypeConverter
import com.photovault.data.local.entity.BackupStatus

/**
 * Type converters for Room database.
 * Handles conversion between enum types and their string representations.
 */
class Converters {

    @TypeConverter
    fun fromBackupStatus(status: BackupStatus): String {
        return status.name
    }

    @TypeConverter
    fun toBackupStatus(value: String): BackupStatus {
        return BackupStatus.valueOf(value)
    }
}
