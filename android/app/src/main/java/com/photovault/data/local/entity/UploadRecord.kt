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

    /**
     * The backup folder (SAF tree URI) this file belongs to. Persisted so an
     * interrupted upload can be fully reconstructed into a FileInfo and resumed
     * after the process is killed (the in-memory queue is lost, but this record
     * survives). Empty for records written before this column existed.
     */
    @ColumnInfo(name = "folder_uri")
    val folderUri: String = "",

    /**
     * The file's MIME type, persisted for the same reconstruction reason as
     * [folderUri]. Empty for pre-migration records (callers fall back to
     * guessing from the file extension).
     */
    @ColumnInfo(name = "mime_type")
    val mimeType: String = "",

    @ColumnInfo(name = "total_chunks")
    val totalChunks: Int,

    @ColumnInfo(name = "uploaded_chunk_index")
    val uploadedChunkIndex: Int = -1,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    /**
     * Why this upload is paused. NULL means an ordinary resume record (an
     * interrupted upload that will be retried automatically). "AUTO_OFF" means
     * the record was paused because the user turned off automatic backup while
     * this file was still uploading; such records are kept as "paused" tasks
     * and surfaced to the user instead of being resumed silently. The design
     * also reserves USER/CONDITION semantics for future use, but only NULL and
     * AUTO_OFF are populated today.
     */
    @ColumnInfo(name = "pause_source")
    val pauseSource: String? = null,

    /**
     * Timestamp (epoch millis) at which this record was marked AUTO_OFF, used to
     * order paused tasks by pause time (most recent first). NULL for records
     * that were never AUTO_OFF paused.
     */
    @ColumnInfo(name = "paused_at")
    val pausedAt: Long? = null
)
