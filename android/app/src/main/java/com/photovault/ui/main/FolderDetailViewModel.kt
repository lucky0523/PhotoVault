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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    val isMotionPhoto: Boolean = false,
    /**
     * True while a manual re-backup of this photo is in flight (queued/uploading)
     * and it has not yet converged to `active`. Drives the uploading spinner in
     * the grid. Cleared automatically once the photo becomes backed up, or pruned
     * by [reloadStatuses] when the backup service is no longer running.
     */
    val isUploading: Boolean = false
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

    /**
     * Cached result of the heavy MediaStore scan (`queryMediaStoreImages` +
     * `MotionPhotoDetector`) WITHOUT any `status`. MediaStore is only scanned on
     * first load and on an explicit fallback refresh, so `photo_status` changes
     * never re-run the scan — the association step below is a pure O(n) mapping.
     */
    private val _rawImages = MutableStateFlow<List<FolderImage>>(emptyList())

    /**
     * One-shot `photo_status` snapshots pushed by [reloadStatuses] (the ON_RESUME
     * fallback). Merged with the reactive [PhotoStatusDao.observeAll] Flow so a
     * manual re-align can re-drive the association even when the reactive
     * subscription was stopped in the background.
     */
    private val _manualStatuses = MutableSharedFlow<List<PhotoStatus>>(replay = 0)

    /** Remembered folder so [reloadStatuses] can rescan MediaStore if needed. */
    private var lastFolderUri: String? = null

    /** In-flight motion-photo detection job, cancelled/replaced on each rescan. */
    private var motionDetectJob: Job? = null

    /**
     * URIs of photos with an in-flight manual re-backup. Added by [rebackup];
     * surfaced as [FolderImage.isUploading] via the combine below (only while the
     * photo has not yet converged to `active`); pruned by [reloadStatuses].
     */
    private val _uploadingUris = MutableStateFlow<Set<String>>(emptySet())

    private val statusUpdates: Flow<List<PhotoStatus>> =
        merge(photoStatusDao.observeAll(), _manualStatuses)

    /**
     * `statusUpdates` scoped to the currently displayed folder.
     *
     * `photo_status` is keyed by the MediaStore content URI, which carries no
     * folder path, so the observed query can't be scoped in SQL — `observeAll()`
     * emits the whole table and re-emits on ANY folder's change. On a large
     * library this would re-run the O(n) association over this folder's images
     * on every unrelated upload. So we scope it in memory instead:
     *
     * 1. [debounce] coalesces bursts of upserts (e.g. many uploads completing in
     *    quick succession during a backup) into a single downstream emission.
     * 2. The [combine] keeps only the statuses whose `file_uri` belongs to this
     *    folder's images.
     * 3. [distinctUntilChanged] drops emissions that leave this folder's subset
     *    unchanged — so changes to OTHER folders never re-run the association.
     */
    @OptIn(FlowPreview::class)
    private val folderStatuses: Flow<List<PhotoStatus>> =
        combine(_rawImages, statusUpdates.debounce(200)) { rawImages, statuses ->
            if (rawImages.isEmpty()) {
                emptyList()
            } else {
                val folderUris = rawImages.mapTo(HashSet(rawImages.size)) { it.uri.toString() }
                statuses.filter { it.fileUri in folderUris }
            }
        }.distinctUntilChanged()

    /**
     * Reactive merge of the cached MediaStore images ([_rawImages]) and the
     * folder-scoped `photo_status` subset ([folderStatuses]).
     *
     * On every emission from either source, performs a single O(n)
     * `associateBy { fileUri }` association producing the displayed
     * [FolderImage] list with the latest `status`. This is a pure mapping — it
     * NEVER re-runs `queryMediaStoreImages` or [MotionPhotoDetector]. Because the
     * DAO's `observeAll()` re-emits whenever the table changes (e.g. after
     * `StatusSyncManager.markActive` on a successful upload), the displayed
     * status converges to the table without a manual re-query.
     *
     * Exposed via `stateIn(WhileSubscribed(5000))`: the Flow stays hot for 5s
     * after the last subscriber goes away (covers config changes / brief
     * backgrounding); [reloadStatuses] handles longer background gaps.
     */
    val images: StateFlow<List<FolderImage>> =
        combine(_rawImages, folderStatuses, _uploadingUris) { rawImages, statuses, uploading ->
            associateStatuses(rawImages, statuses, uploading)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /**
     * First-load entry point. Runs ONLY the heavy MediaStore scan
     * (`queryMediaStoreImages` + `MotionPhotoDetector`, without `status`) and
     * writes the result into [_rawImages]. The reactive [images] combine then
     * associates it with the `photo_status` table.
     */
    fun loadImages(folderUri: String) {
        lastFolderUri = folderUri
        viewModelScope.launch {
            _loading.value = true
            try {
                // Only the (fast) MediaStore cursor query runs on the critical
                // path so the grid can render as soon as the file list is known.
                val raw = withContext(Dispatchers.IO) {
                    queryMediaStoreImages(context, folderUri)
                }
                _rawImages.value = raw
            } finally {
                _loading.value = false
            }
            // Motion-photo detection reads the head of every JPEG, so it is far
            // too slow to block first render on large folders. Run it in the
            // background and patch the "LIVE" badges in once ready.
            detectMotionPhotos()
        }
    }

    /**
     * Off-critical-path motion-photo detection. Scans the head of each JPEG
     * candidate in [_rawImages] with bounded parallelism, then patches the
     * `isMotionPhoto` flag in with a single state update (drives the "LIVE"
     * badge). Cancels any previous run so a rescan doesn't stack up work.
     */
    private fun detectMotionPhotos() {
        motionDetectJob?.cancel()
        motionDetectJob = viewModelScope.launch(Dispatchers.IO) {
            val snapshot = _rawImages.value
            val candidates = snapshot.filter {
                it.mimeType == "image/jpeg" || it.mimeType == "image/jpg"
            }
            if (candidates.isEmpty()) return@launch

            // Cap concurrent file reads so we don't thrash disk I/O.
            val semaphore = Semaphore(4)
            val motionUris = coroutineScope {
                candidates.map { img ->
                    async {
                        semaphore.withPermit {
                            if (MotionPhotoDetector.isMotionPhoto(context, img.uri, img.mimeType)) {
                                img.uri.toString()
                            } else null
                        }
                    }
                }.awaitAll()
            }.filterNotNull().toSet()

            if (motionUris.isEmpty()) return@launch
            _rawImages.update { list ->
                list.map { img ->
                    if (img.uri.toString() in motionUris) img.copy(isMotionPhoto = true) else img
                }
            }
        }
    }

    /**
     * ON_RESUME fallback: covers `photo_status` changes that happened while the
     * reactive [images] subscription was stopped in the background.
     *
     * Does a one-shot read of [PhotoStatusDao.getAll] and re-associates it with
     * the cached [_rawImages] by pushing the snapshot through [_manualStatuses].
     * If [_rawImages] is empty (nothing scanned yet), triggers one MediaStore
     * rescan instead.
     */
    fun reloadStatuses() {
        viewModelScope.launch {
            if (_rawImages.value.isEmpty()) {
                lastFolderUri?.let { loadImages(it) }
                return@launch
            }
            val statuses = withContext(Dispatchers.IO) { photoStatusDao.getAll() }
            pruneUploading(statuses)
            _manualStatuses.emit(statuses)
        }
    }

    /**
     * Prunes stale [_uploadingUris] markers. Removes URIs that have converged to
     * `active` (the re-backup succeeded); and if the backup service is no longer
     * running, clears the rest too — the upload finished without success
     * (failed/stopped), so the spinner must stop.
     */
    private fun pruneUploading(statuses: List<PhotoStatus>) {
        val activeUris = statuses
            .filter { it.status == PhotoStatusValue.ACTIVE }
            .map { it.fileUri }
            .toSet()
        _uploadingUris.update { current ->
            if (!BackupForegroundService.isRunning) emptySet() else current - activeUris
        }
    }

    /**
     * Manually re-uploads a single trashed/purged photo.
     *
     * Bypasses the scan worker's skip logic by enqueuing directly into the
     * BackupQueue, then starts the foreground service. On the server side,
     * /backup/complete calls reactivate_record to clear the deletion markers
     * on the existing record (no duplicate record created).
     *
     * Does NOT pre-write `active` / call `markActive`: `photo_status` is only
     * updated by `StatusSyncManager.markActive` after a successful upload, so an
     * incomplete or failed re-backup never shows as "backed up".
     */
    fun rebackup(image: FolderImage, folderUri: String) {
        // Immediately mark the photo as uploading so the grid shows a spinner
        // while the async enqueue + foreground upload runs. Cleared once the
        // photo_status table converges to `active` (or pruned in reloadStatuses).
        _uploadingUris.update { it + image.uri.toString() }
        viewModelScope.launch {
            val fileInfo = FileInfo(
                uri = image.uri.toString(),
                fileName = image.name,
                fileSize = image.fileSize,
                createdTime = image.createdTime,
                mimeType = image.mimeType,
                folderUri = folderUri,
                // Force re-upload so the trashed/purged skip in ChunkUploader is
                // bypassed and the server reactivates the record on complete.
                forceReupload = true
            )
            backupQueue.enqueue(listOf(fileInfo))
            BackupForegroundService.start(context)
        }
    }

    /**
     * Pure O(n) association of cached [rawImages] with the current [statuses]
     * from the `photo_status` table, keyed by `fileUri`. No MediaStore query or
     * motion-photo detection happens here.
     */
    private fun associateStatuses(
        rawImages: List<FolderImage>,
        statuses: List<PhotoStatus>,
        uploadingUris: Set<String>
    ): List<FolderImage> {
        if (rawImages.isEmpty()) return emptyList()
        val statusByUri = statuses.associateBy { it.fileUri }
        return rawImages.map { img ->
            val key = img.uri.toString()
            val status = statusByUri[key]
            img.copy(
                status = status,
                // Show the spinner only until the photo actually converges to
                // active — success then flips it to the "已备份" badge instead.
                isUploading = uploadingUris.contains(key) &&
                    status?.status != PhotoStatusValue.ACTIVE
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
