package com.photovault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for tracking upload progress (断点续传).
 *
 * When an upload is interrupted, this record persists the state so that
 * the upload can be resumed from the last successfully uploaded chunk.
 *
 * Records are automatically invalidated if:
 * - The session has expired (> 7 days since creation)
 * - The source file has been modified (size or modification time changed)
 */
@Entity(tableName = "upload_records")
data class UploadRecord(
    @PrimaryKey
    @ColumnInfo(name = "file_uri")
    val fileUri: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "file_hash")
    val fileHash: String,

    @ColumnInfo(name = "file_name")
    val fileName: String,

    @ColumnInfo(name = "file_size")
    val fileSize: Long,

    @ColumnInfo(name = "file_modified_time")
    val fileModifiedTime: Long,

    @ColumnInfo(name = "total_chunks")
    val totalChunks: Int,

    @ColumnInfo(name = "uploaded_chunk_index")
    val uploadedChunkIndex: Int = -1,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
