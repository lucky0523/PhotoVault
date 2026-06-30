package com.photovault.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.photovault.data.local.entity.BackupFolder
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for BackupFolder entity.
 * Provides CRUD operations and reactive queries via Flow.
 */
@Dao
interface BackupFolderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: BackupFolder): Long

    @Update
    suspend fun update(folder: BackupFolder)

    @Delete
    suspend fun delete(folder: BackupFolder)

    @Query("SELECT * FROM backup_folders ORDER BY folder_name ASC")
    fun getAll(): Flow<List<BackupFolder>>

    @Query("SELECT * FROM backup_folders ORDER BY folder_name ASC")
    suspend fun getAllOnce(): List<BackupFolder>

    @Query("SELECT * FROM backup_folders WHERE id = :id")
    suspend fun getById(id: Long): BackupFolder?

    @Query("SELECT * FROM backup_folders WHERE folder_uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): BackupFolder?

    @Query("DELETE FROM backup_folders WHERE id = :id")
    suspend fun deleteById(id: Long)
}
