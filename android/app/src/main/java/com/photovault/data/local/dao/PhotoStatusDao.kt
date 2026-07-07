package com.photovault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.photovault.data.local.entity.PhotoStatus
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [PhotoStatus] entity.
 *
 * Used by:
 * - StatusSyncManager — to upsert status changes received from the server
 * - BackgroundScanWorker — to skip trashed/purged files during scanning
 * - BackupForegroundService — to mark files as active after successful upload
 * - FolderDetailScreen — to display the four-state status of each photo
 */
@Dao
interface PhotoStatusDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(status: PhotoStatus)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(statuses: List<PhotoStatus>)

    @Query("SELECT * FROM photo_status WHERE file_uri = :fileUri")
    suspend fun getByFileUri(fileUri: String): PhotoStatus?

    @Query("SELECT * FROM photo_status WHERE file_hash = :fileHash")
    suspend fun getByFileHash(fileHash: String): List<PhotoStatus>

    @Query("SELECT * FROM photo_status WHERE status = :status")
    suspend fun getByStatus(status: String): List<PhotoStatus>

    /**
     * Returns all non-active records (trashed + purged) in a single indexed query.
     * Used by the restore reconciliation to build the candidate set without
     * two separate status scans.
     */
    @Query("SELECT * FROM photo_status WHERE status != 'active'")
    suspend fun getNonActive(): List<PhotoStatus>

    @Query("SELECT * FROM photo_status")
    suspend fun getAll(): List<PhotoStatus>

    /**
     * Reactive query over the entire photo_status table.
     *
     * Emits the current list on subscription and re-emits whenever any row in
     * photo_status changes (insert / upsert / [markActive] / [updateStatusByHash]
     * / [delete]). Used by FolderDetailViewModel to keep the displayed photo
     * status converged to this authoritative table without a manual re-query.
     */
    @Query("SELECT * FROM photo_status")
    fun observeAll(): Flow<List<PhotoStatus>>

    /**
     * Updates the status of all records matching [fileHash] to [status].
     * Used by StatusSyncManager when applying server-reported status changes.
     */
    @Query(
        """
        UPDATE photo_status
        SET status = :status,
            deleted_at = :deletedAt,
            expires_at = :expiresAt,
            updated_at = :updatedAt
        WHERE file_hash = :fileHash
        """
    )
    suspend fun updateStatusByHash(
        fileHash: String,
        status: String,
        deletedAt: Long?,
        expiresAt: Long?,
        updatedAt: Long = System.currentTimeMillis()
    )

    /**
     * Marks a single file as active (clears deletion markers).
     * Called after a successful upload or manual re-upload.
     */
    @Query(
        """
        UPDATE photo_status
        SET status = 'active',
            deleted_at = NULL,
            expires_at = NULL,
            updated_at = :updatedAt
        WHERE file_uri = :fileUri
        """
    )
    suspend fun markActive(fileUri: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM photo_status WHERE file_uri = :fileUri")
    suspend fun delete(fileUri: String)
}
