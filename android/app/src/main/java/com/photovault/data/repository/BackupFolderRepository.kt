package com.photovault.data.repository

import com.photovault.data.local.dao.BackupFolderDao
import com.photovault.data.local.entity.BackupFolder
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing backup folder data.
 * Provides a clean API for the UI layer to interact with the database.
 */
@Singleton
class BackupFolderRepository @Inject constructor(
    private val backupFolderDao: BackupFolderDao
) {

    fun getAllFolders(): Flow<List<BackupFolder>> {
        return backupFolderDao.getAll()
    }

    suspend fun getFolderById(id: Long): BackupFolder? {
        return backupFolderDao.getById(id)
    }

    suspend fun addFolder(
        folderUri: String,
        folderName: String,
        useCustomPath: Boolean = false,
        customPath: String? = null,
        useYearMonthLayer: Boolean = false
    ): Long {
        val folder = BackupFolder(
            folderUri = folderUri,
            folderName = folderName,
            useCustomPath = useCustomPath,
            customPath = customPath,
            useYearMonthLayer = useYearMonthLayer
        )
        return backupFolderDao.insert(folder)
    }

    suspend fun updateFolder(folder: BackupFolder) {
        backupFolderDao.update(folder)
    }

    suspend fun updateStoragePolicy(
        folderId: Long,
        useCustomPath: Boolean,
        customPath: String?,
        useYearMonthLayer: Boolean
    ) {
        val folder = backupFolderDao.getById(folderId) ?: return
        backupFolderDao.update(
            folder.copy(
                useCustomPath = useCustomPath,
                customPath = customPath,
                useYearMonthLayer = useYearMonthLayer
            )
        )
    }

    suspend fun removeFolder(folderId: Long) {
        backupFolderDao.deleteById(folderId)
    }
}
