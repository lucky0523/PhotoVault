package com.photovault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.photovault.data.local.entity.BackupHistoryRecord
import com.photovault.data.local.entity.BackupStatus
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for BackupHistoryRecord entity.
 * Provides queries for listing and filtering backup history.
 */
@Dao
interface BackupHistoryDao {

    @Insert
    suspend fun insert(record: BackupHistoryRecord): Long

    /**
     * Get all history records ordered by completion time (newest first).
     */
    @Query("SELECT * FROM backup_history ORDER BY completed_at DESC")
    fun getAll(): Flow<List<BackupHistoryRecord>>

    /**
     * Get history records filtered by status, ordered by completion time (newest first).
     */
    @Query("SELECT * FROM backup_history WHERE status = :status ORDER BY completed_at DESC")
    fun getByStatus(status: BackupStatus): Flow<List<BackupHistoryRecord>>

    /**
     * Get a single record by ID.
     */
    @Query("SELECT * FROM backup_history WHERE id = :id")
    suspend fun getById(id: Long): BackupHistoryRecord?

    /**
     * Update a record's status (e.g., after a retry succeeds).
     */
    @Query("UPDATE backup_history SET status = :status, error_message = :errorMessage, completed_at = :completedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: BackupStatus, errorMessage: String? = null, completedAt: Long = System.currentTimeMillis())

    /**
     * Delete a single history record by ID.
     */
    @Query("DELETE FROM backup_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Delete all history records.
     */
    @Query("DELETE FROM backup_history")
    suspend fun deleteAll()

    /**
     * Delete old history records (older than the specified timestamp).
     */
    @Query("DELETE FROM backup_history WHERE completed_at < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)

    /**
     * Get the count of records by status.
     */
    @Query("SELECT COUNT(*) FROM backup_history WHERE status = :status")
    suspend fun getCountByStatus(status: BackupStatus): Int
}
