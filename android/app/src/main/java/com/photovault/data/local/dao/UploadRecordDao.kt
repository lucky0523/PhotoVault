package com.photovault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.photovault.data.local.entity.UploadRecord

/**
 * Data Access Object for UploadRecord entity.
 * Manages persistence of upload progress for resume capability.
 */
@Dao
interface UploadRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(record: UploadRecord)

    @Query("SELECT * FROM upload_records WHERE file_uri = :fileUri")
    suspend fun getByFileUri(fileUri: String): UploadRecord?

    @Query("SELECT * FROM upload_records WHERE session_id = :sessionId")
    suspend fun getBySessionId(sessionId: String): UploadRecord?

    @Query("DELETE FROM upload_records WHERE file_uri = :fileUri")
    suspend fun deleteByFileUri(fileUri: String)

    /**
     * Deletes all resume records belonging to a backup folder. Called when the
     * folder is removed so interrupted uploads are not resumed / re-queued on
     * the next scan or after a process kill.
     */
    @Query("DELETE FROM upload_records WHERE folder_uri = :folderUri")
    suspend fun deleteByFolderUri(folderUri: String)

    @Query("UPDATE upload_records SET uploaded_chunk_index = :chunkIndex, updated_at = :updatedAt WHERE file_uri = :fileUri")
    suspend fun updateProgress(fileUri: String, chunkIndex: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM upload_records WHERE created_at < :expiryTime")
    suspend fun deleteExpired(expiryTime: Long)

    @Query("SELECT * FROM upload_records")
    suspend fun getAll(): List<UploadRecord>
}
