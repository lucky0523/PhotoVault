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
import com.photovault.util.MotionPhotoDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
     * The folder's media, streamed in from MediaStore by [loadImages] WITHOUT any
     * `status`. MediaStore is only queried on first load and on an explicit
     * fallback refresh, so `photo_status` changes never re-run the query — the
     * association step below is a pure O(n) mapping.
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

    /** In-flight folder load job, cancelled/replaced on each (re)load. */
    private var loadJob: Job? = null

    /**
     * Per-URI motion-photo detection cache, filled lazily by [isMotionPhoto] as
     * thumbnails scroll into view. Avoids ever reading every file up-front.
     */
    private val motionPhotoCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Bounds concurrent motion-photo head reads so scrolling doesn't thrash I/O. */
    private val motionDetectSemaphore = Semaphore(4)

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
     * NEVER re-runs the MediaStore query or [MotionPhotoDetector]. Because the
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
     * First-load entry point. Streams the MediaStore results in date-ordered
     * pages into [_rawImages]: the first (small) page publishes almost
     * immediately so the grid paints fast, then the remaining pages append in
     * the background. No motion-photo detection happens here — that is done
     * lazily per visible thumbnail via [isMotionPhoto]. The reactive [images]
     * combine then associates the list with the `photo_status` table.
     */
    fun loadImages(folderUri: String) {
        lastFolderUri = folderUri
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _loading.value = true
            try {
                val accumulated = ArrayList<FolderImage>()
                withContext(Dispatchers.IO) {
                    loadFolderImagesPaged(
                        context = context,
                        folderUri = folderUri,
                        firstPageSize = FIRST_PAGE_SIZE,
                        pageSize = PAGE_SIZE
                    ) { batch ->
                        accumulated.addAll(batch)
                        // Publish a snapshot so the grid fills progressively. The
                        // first batch replaces any previous folder's list.
                        _rawImages.value = ArrayList(accumulated)
                    }
                }
                // Publish an empty list for a media-free folder so the "暂无图片"
                // empty state shows instead of leaving a stale/loading grid.
                if (accumulated.isEmpty()) _rawImages.value = emptyList()
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Lazily detects whether [image] is a motion photo, caching the result per
     * URI. Called from the grid (via `produceState`) as thumbnails scroll into
     * view, so only the JPEGs that actually become visible are ever read —
     * never the whole folder up-front. Reads are bounded by
     * [motionDetectSemaphore] to keep disk I/O calm during fast scrolling.
     */
    suspend fun isMotionPhoto(image: FolderImage): Boolean {
        if (image.mimeType != "image/jpeg" && image.mimeType != "image/jpg") return false
        val key = image.uri.toString()
        motionPhotoCache[key]?.let { return it }
        val result = withContext(Dispatchers.IO) {
            motionDetectSemaphore.withPermit {
                // Re-check under the permit in case a concurrent caller filled it.
                motionPhotoCache[key]
                    ?: MotionPhotoDetector.isMotionPhoto(context, image.uri, image.mimeType)
            }
        }
        motionPhotoCache[key] = result
        return result
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
            // Single-photo 重新备份 is an explicit user action: mark the run manual
            // so turning off "自动备份" mid-run won't stop it (R-3.15).
            BackupForegroundService.start(context, manual = true)
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

    /**
     * Streams the folder's images+videos to [onBatch] in `DATE_MODIFIED`-desc
     * order, in pages. Each MediaStore collection is queried exactly once (both
     * already sorted desc) and 2-way merged so the combined order is correct even
     * though the rows come from two separate cursors. The first page uses
     * [firstPageSize] (kept small so the grid paints quickly); the rest use
     * [pageSize].
     *
     * Note: this is progressive *emission* paging, not SQL `LIMIT`/`OFFSET`.
     * A global date order across the two collections can't be expressed as
     * aligned per-collection SQL pages, so we merge the two date-sorted cursors
     * and hand out pages as we go.
     *
     * Cooperatively cancellable: [ensureActive] between rows lets a folder switch
     * abandon a partial load promptly.
     */
    private suspend fun loadFolderImagesPaged(
        context: Context,
        folderUri: String,
        firstPageSize: Int,
        pageSize: Int,
        onBatch: (List<FolderImage>) -> Unit
    ) {
        val relativeDir = try {
            val treeUri = Uri.parse(folderUri)
            val path = treeUri.path ?: return
            path.removePrefix("/tree/").substringAfter(':').trim('/')
        } catch (e: Exception) {
            return
        }

        val imagesReader = openMediaReader(
            context,
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            relativeDir,
            defaultMime = "image/*"
        )
        val videoReader = openMediaReader(
            context,
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            relativeDir,
            defaultMime = "video/*"
        )

        try {
            imagesReader?.advance()
            videoReader?.advance()

            val buffer = ArrayList<FolderImage>(pageSize)
            var target = firstPageSize.coerceAtLeast(1)

            while (true) {
                currentCoroutineContext().ensureActive()
                val img = imagesReader?.current
                val vid = videoReader?.current
                // Pick the newer of the two cursor heads (desc order), advancing
                // whichever we consumed.
                val next = when {
                    img == null && vid == null -> break
                    img == null -> { videoReader?.advance(); vid!! }
                    vid == null -> { imagesReader?.advance(); img }
                    img.createdTime >= vid.createdTime -> { imagesReader?.advance(); img }
                    else -> { videoReader?.advance(); vid }
                }
                buffer.add(next)
                if (buffer.size >= target) {
                    onBatch(ArrayList(buffer))
                    buffer.clear()
                    target = pageSize
                }
            }
            if (buffer.isNotEmpty()) onBatch(ArrayList(buffer))
        } finally {
            imagesReader?.close()
            videoReader?.close()
        }
    }

    /**
     * Opens a query cursor over a single MediaStore [collection] (Images or
     * Video) for media under [relativeDir], wrapped in a [MediaReader] that reads
     * one [FolderImage] per row on demand. Returns null when the query fails or
     * the provider returns no cursor.
     */
    private fun openMediaReader(
        context: Context,
        collection: Uri,
        relativeDir: String,
        defaultMime: String
    ): MediaReader? {
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

        return try {
            val cursor = context.contentResolver.query(
                collection, projection, selection, args, sortOrder
            ) ?: return null
            MediaReader(cursor, collection, defaultMime)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reads one [FolderImage] per row from a MediaStore cursor on demand, exposing
     * the row at the current position via [current] so two readers can be 2-way
     * merged without materialising either collection in full.
     */
    private class MediaReader(
        private val cursor: android.database.Cursor,
        private val collection: Uri,
        private val defaultMime: String
    ) {
        private val idCol =
            cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID)
        private val nameCol =
            cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
        private val sizeCol =
            cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.SIZE)
        private val dateCol =
            cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DATE_MODIFIED)
        private val mimeCol =
            cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.MIME_TYPE)

        /** The row at the current cursor position, or null once exhausted. */
        var current: FolderImage? = null
            private set

        /** Advances to the next row, updating [current] (null when exhausted). */
        fun advance() {
            current = if (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                FolderImage(
                    uri = android.content.ContentUris.withAppendedId(collection, id),
                    name = cursor.getString(nameCol) ?: "unknown",
                    fileSize = cursor.getLong(sizeCol),
                    createdTime = cursor.getLong(dateCol) * 1000L,
                    mimeType = cursor.getString(mimeCol) ?: defaultMime
                )
            } else {
                null
            }
        }

        fun close() = cursor.close()
    }

    private companion object {
        /** Small first page so the grid paints almost immediately. */
        private const val FIRST_PAGE_SIZE = 300

        /** Subsequent page size while the rest of the folder streams in. */
        private const val PAGE_SIZE = 800
    }
}
