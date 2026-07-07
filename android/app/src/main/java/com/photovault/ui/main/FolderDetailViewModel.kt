package com.photovault.ui.main

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photovault.data.local.dao.PhotoStatusDao
import com.photovault.data.local.entity.PhotoStatus
import com.photovault.data.local.entity.PhotoStatusValue
import com.photovault.service.BackupForegroundService
import com.photovault.service.BackupQueue
import com.photovault.service.FileInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Image displayed in the folder detail grid, carrying its server-side status.
 *
 * [status] is null when the file has no PhotoStatus row (never uploaded).
 */
data class FolderImage(
    val uri: Uri,
    val name: String,
    val fileSize: Long,
    val createdTime: Long,
    val mimeType: String,
    val status: PhotoStatus? = null,
    val isMotionPhoto: Boolean = false
) {
    val isBackedUp: Boolean get() = status?.status == PhotoStatusValue.ACTIVE
    val isTrashed: Boolean get() = status?.status == PhotoStatusValue.TRASHED
    val isPurged: Boolean get() = status?.status == PhotoStatusValue.PURGED
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}

@HiltViewModel
class FolderDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoStatusDao: PhotoStatusDao,
    private val backupQueue: BackupQueue
) : ViewModel() {

    private val _images = MutableStateFlow<List<FolderImage>>(emptyList())
    val images: StateFlow<List<FolderImage>> = _images.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun loadImages(folderUri: String) {
        viewModelScope.launch {
            _loading.value = true
            try {
                val loaded = withContext(Dispatchers.IO) {
                    loadFolderImagesWithStatus(context, folderUri)
                }
                _images.value = loaded
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Manually re-uploads a single trashed/purged photo.
     *
     * Bypasses the scan worker's skip logic by enqueuing directly into the
     * BackupQueue, then starts the foreground service. On the server side,
     * /backup/complete calls reactivate_record to clear the deletion markers
     * on the existing record (no duplicate record created).
     */
    fun rebackup(image: FolderImage, folderUri: String) {
        viewModelScope.launch {
            val fileInfo = FileInfo(
                uri = image.uri.toString(),
                fileName = image.name,
                fileSize = image.fileSize,
                createdTime = image.createdTime,
                mimeType = image.mimeType,
                folderUri = folderUri
            )
            backupQueue.enqueue(listOf(fileInfo))
            BackupForegroundService.start(context)
        }
    }

    /**
     * Loads images in the given SAF tree folder via MediaStore, then enriches
     * each with its PhotoStatus from the local database.
     */
    private suspend fun loadFolderImagesWithStatus(
        context: Context,
        folderUri: String
    ): List<FolderImage> {
        val rawImages = queryMediaStoreImages(context, folderUri)
        if (rawImages.isEmpty()) return emptyList()

        // Batch-fetch all photo_status rows we might need
        val allStatuses = photoStatusDao.getAll()
        val statusByUri = allStatuses.associateBy { it.fileUri }

        return rawImages.map { img ->
            img.copy(
                status = statusByUri[img.uri.toString()],
                isMotionPhoto = MotionPhotoDetector.isMotionPhoto(context, img.uri, img.mimeType)
            )
        }
    }

    private fun queryMediaStoreImages(
        context: Context,
        folderUri: String
    ): List<FolderImage> {
        val relativeDir = try {
            val treeUri = Uri.parse(folderUri)
            val path = treeUri.path ?: return emptyList()
            val docIdPart = path.removePrefix("/tree/")
            docIdPart.substringAfter(':').trim('/')
        } catch (e: Exception) {
            return emptyList()
        }

        // Query both images and videos, then sort the combined result by date desc.
        val combined = queryCollection(
            context,
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            relativeDir,
            defaultMime = "image/*"
        ) + queryCollection(
            context,
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            relativeDir,
            defaultMime = "video/*"
        )
        return combined.sortedByDescending { it.createdTime }
    }

    /**
     * Queries a single MediaStore [collection] (Images or Video) for media under
     * [relativeDir]. Uses the generic [android.provider.MediaStore.MediaColumns],
     * which are shared by both collections.
     */
    private fun queryCollection(
        context: Context,
        collection: Uri,
        relativeDir: String,
        defaultMime: String
    ): List<FolderImage> {
        val result = mutableListOf<FolderImage>()
        val projection = arrayOf(
            android.provider.MediaStore.MediaColumns._ID,
            android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
            android.provider.MediaStore.MediaColumns.SIZE,
            android.provider.MediaStore.MediaColumns.DATE_MODIFIED,
            android.provider.MediaStore.MediaColumns.MIME_TYPE,
            android.provider.MediaStore.MediaColumns.RELATIVE_PATH
        )

        val selection: String
        val args: Array<String>
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            selection = "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            args = arrayOf("$relativeDir/%")
        } else {
            @Suppress("DEPRECATION")
            selection = "${android.provider.MediaStore.MediaColumns.DATA} LIKE ?"
            args = arrayOf("%/$relativeDir/%")
        }

        val sortOrder = "${android.provider.MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(collection, projection, selection, args, sortOrder)
                ?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DATE_MODIFIED)
                    val mimeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.MIME_TYPE)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "unknown"
                        val size = cursor.getLong(sizeCol)
                        val dateModifiedMs = cursor.getLong(dateCol) * 1000L
                        val mime = cursor.getString(mimeCol) ?: defaultMime
                        val contentUri = android.content.ContentUris.withAppendedId(collection, id)
                        result.add(
                            FolderImage(
                                uri = contentUri,
                                name = name,
                                fileSize = size,
                                createdTime = dateModifiedMs,
                                mimeType = mime
                            )
                        )
                    }
                }
        } catch (e: Exception) {
            return emptyList()
        }
        return result
    }
}
