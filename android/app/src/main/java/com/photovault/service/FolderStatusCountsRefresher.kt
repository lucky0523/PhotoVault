package com.photovault.service

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.photovault.data.local.dao.BackupFolderDao
import com.photovault.data.local.dao.PhotoStatusDao
import com.photovault.data.local.entity.PhotoStatusValue
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Rebuilds each registered folder's persisted status counts from the current
 * MediaStore items and the authoritative local [photo_status] table.
 *
 * Folder-level counts are a denormalized UI/progress cache. Status synchronization
 * changes [photo_status] directly, so refreshing this cache after a successful
 * sync prevents it from showing stale buckets (for example, a file that changed
 * from active to purged still being counted as backed up).
 */
@Singleton
class FolderStatusCountsRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupFolderDao: BackupFolderDao,
    private val photoStatusDao: PhotoStatusDao
) {
    /**
     * Reconciles totals and all status buckets without changing [BackupFolder.lastScanTime].
     * A MediaStore failure leaves that folder unchanged rather than replacing valid counts
     * with zeroes.
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val statusByUri = photoStatusDao.getAll().associateBy { it.fileUri }
        for (folder in backupFolderDao.getAllOnce()) {
            val mediaUris = loadMediaUris(folder.folderUri) ?: continue

            var backedUp = 0
            var trashed = 0
            var purged = 0
            for (uri in mediaUris) {
                when (statusByUri[uri]?.status) {
                    PhotoStatusValue.ACTIVE -> backedUp++
                    PhotoStatusValue.TRASHED -> trashed++
                    PhotoStatusValue.PURGED -> purged++
                }
            }

            if (folder.totalImages != mediaUris.size ||
                folder.backedUpImages != backedUp ||
                folder.trashedImages != trashed ||
                folder.purgedImages != purged
            ) {
                backupFolderDao.update(
                    folder.copy(
                        totalImages = mediaUris.size,
                        backedUpImages = backedUp,
                        trashedImages = trashed,
                        purgedImages = purged
                    )
                )
            }
        }
    }

    private fun loadMediaUris(folderUri: String): List<String>? {
        val relativeDir = try {
            DocumentsContract.getTreeDocumentId(Uri.parse(folderUri))
                .substringAfter(':')
                .trim('/')
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Cannot parse folder URI $folderUri", e)
            null
        } ?: return null

        val images = queryMediaUris(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            relativeDir
        ) ?: return null
        val videos = queryMediaUris(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            relativeDir
        ) ?: return null
        return images + videos
    }

    private fun queryMediaUris(collection: Uri, relativeDir: String): List<String>? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
            "${MediaStore.MediaColumns.IS_PENDING} = 0"
        val selectionArgs = arrayOf("$relativeDir/%")

        return try {
            context.contentResolver.query(collection, projection, selection, selectionArgs, null)
                ?.use { cursor ->
                    buildList(cursor.count) {
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        while (cursor.moveToNext()) {
                            add(ContentUris.withAppendedId(collection, cursor.getLong(idColumn)).toString())
                        }
                    }
                }
                ?: run {
                    android.util.Log.w(TAG, "MediaStore returned no cursor for $collection")
                    null
                }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "MediaStore query failed for $collection", e)
            null
        }
    }

    private companion object {
        const val TAG = "PhotoVaultFolderCounts"
    }
}
