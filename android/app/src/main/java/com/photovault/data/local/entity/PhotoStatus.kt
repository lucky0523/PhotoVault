package com.photovault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity tracking the server-side status of a photo.
 *
 * Keyed by the local MediaStore content URI ([fileUri]) so the scan worker
 * can look up status without hashing the file. The [fileHash] column is used
 * by StatusSyncManager to match server-reported status changes back to local
 * records.
 *
 * States:
 * - active    — file is backed up and available on the server
 * - trashed   — file was moved to the recycle bin on the server (will be purged after 30 days)
 * - purged    — file was permanently deleted on the server (record retained for sync)
 *
 * A file with no PhotoStatus row is treated as "not_backed_up".
 */
@Entity(tableName = "photo_status")
data class PhotoStatus(
    @PrimaryKey
    @ColumnInfo(name = "file_uri")
    val fileUri: String,

    @ColumnInfo(name = "file_hash")
    val fileHash: String?,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "expires_at")
    val expiresAt: Long? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Status values for [PhotoStatus].
 */
object PhotoStatusValue {
    const val ACTIVE = "active"
    const val TRASHED = "trashed"
    const val PURGED = "purged"
}
