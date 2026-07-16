package com.photovault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.photovault.data.local.entity.QueuedFile

/**
 * Data Access Object for [QueuedFile].
 *
 * Persists the backup queue so the "排队中" list survives an app close/kill. The
 * in-memory [com.photovault.service.BackupQueue] mirrors every mutation here and
 * rebuilds itself from [getAll] on process start.
 */
@Dao
interface QueuedFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: QueuedFile)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(files: List<QueuedFile>)

    @Query("DELETE FROM queued_files WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    @Query("DELETE FROM queued_files WHERE uri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>)

    @Query("DELETE FROM queued_files")
    suspend fun deleteAll()

    /** All queued files, oldest first (by original creation time, then enqueue time). */
    @Query("SELECT * FROM queued_files ORDER BY created_time ASC, enqueued_at ASC")
    suspend fun getAll(): List<QueuedFile>
}
