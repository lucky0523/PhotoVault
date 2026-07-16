package com.photovault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity that persists the backup queue (the "排队中" list) so it survives
 * the app being closed or killed by the system.
 *
 * The in-memory [com.photovault.service.BackupQueue] is the authoritative queue
 * while the process is alive; this table is a durable mirror of it. Each row maps
 * one-to-one to a [com.photovault.service.FileInfo]. On process start the queue
 * is rebuilt from these rows (see `BackupQueue.restoreFromPersistence`), so files
 * that were only queued — but had not yet started uploading and therefore have no
 * [UploadRecord] — are no longer lost across a restart.
 *
 * Rows are removed as files finish/leave the queue (dequeue, folder removal, or a
 * full clear when automatic backup is turned off), keeping this table in sync
 * with the in-memory queue.
 */
@Entity(tableName = "queued_files")
data class QueuedFile(
    /** SAF URI of the queued file; stable per-file key (matches FileInfo.uri). */
    @PrimaryKey
    @ColumnInfo(name = "uri")
    val uri: String,

    @ColumnInfo(name = "file_name")
    val fileName: String,

    @ColumnInfo(name = "file_size")
    val fileSize: Long,

    /**
     * Original file creation time. Drives the queue ordering (oldest first),
     * mirroring the in-memory [java.util.PriorityQueue] comparator.
     */
    @ColumnInfo(name = "created_time")
    val createdTime: Long,

    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    /** SAF tree URI of the backup folder this file belongs to. */
    @ColumnInfo(name = "folder_uri")
    val folderUri: String,

    /**
     * Whether this is a forced re-upload of a trashed/purged photo (the uploader
     * must not short-circuit on the server's trashed/purged status).
     */
    @ColumnInfo(name = "force_reupload")
    val forceReupload: Boolean = false,

    /**
     * When this file was enqueued (epoch millis). Used only as a stable tiebreaker
     * for rows sharing the same [createdTime].
     */
    @ColumnInfo(name = "enqueued_at")
    val enqueuedAt: Long = System.currentTimeMillis()
)
