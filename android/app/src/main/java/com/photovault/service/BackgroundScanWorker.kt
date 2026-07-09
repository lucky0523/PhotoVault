package com.photovault.service

import android.content.Context
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.photovault.data.local.CredentialManager
import com.photovault.data.local.dao.BackupFolderDao
import com.photovault.data.local.dao.PhotoStatusDao
import com.photovault.data.local.dao.UploadRecordDao
import com.photovault.data.local.entity.PhotoStatusValue
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Background worker that periodically scans registered backup folders
 * for new image files since the last scan time.
 *
 * Uses WorkManager PeriodicWorkRequest with a 15-minute interval.
 * Scans all registered backup folders using DocumentFile API (SAF).
 * Updates lastScanTime and totalImages in Room after each scan.
 *
 * Files marked as trashed or purged in the local photo_status table are
 * skipped during scanning (both automatic and manual "立即备份" triggers).
 * They can only be re-uploaded via the per-photo "重新备份" action in
 * FolderDetailScreen, which enqueues them directly into the BackupQueue.
 */
@HiltWorker
class BackgroundScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val backupFolderDao: BackupFolderDao,
    private val backupConditionChecker: BackupConditionChecker,
    private val backupQueue: BackupQueue,
    private val photoStatusDao: PhotoStatusDao,
    private val statusSyncManager: StatusSyncManager,
    private val credentialManager: CredentialManager,
    private val uploadRecordDao: UploadRecordDao
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "background_scan_worker"
        const val DEFAULT_INTERVAL_MINUTES = 15L

        /**
         * Input-data key. When true, the worker ignores each folder's stored
         * lastScanTime and scans ALL images so they are re-enqueued for backup.
         * The server dedups by hash, so already-backed-up files are skipped cheaply.
         */
        const val KEY_FORCE_FULL_SCAN = "force_full_scan"

        private val IMAGE_MIME_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif",
            "image/bmp",
            "image/tiff",
            "image/heic",
            "image/heif",
            "image/avif",
            "image/x-adobe-dng",
            "image/x-canon-cr2",
            "image/x-canon-cr3",
            "image/x-nikon-nef",
            "image/x-sony-arw",
            "image/x-olympus-orf",
            "image/x-fuji-raf",
            "image/x-panasonic-rw2"
        )

        private val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "tiff", "tif",
            "heic", "heif", "avif",
            "dng", "cr2", "cr3", "nef", "arw", "orf", "raf", "rw2"
        )

        private val VIDEO_MIME_TYPES = setOf(
            "video/mp4",
            "video/quicktime",
            "video/x-matroska",
            "video/webm",
            "video/3gpp",
            "video/x-msvideo",
            "video/mpeg",
            "video/x-ms-wmv",
            "video/x-flv"
        )

        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mov", "mkv", "webm", "3gp", "avi", "mpeg", "mpg",
            "wmv", "flv", "m4v", "ts"
        )

        /**
         * Schedules the periodic scan worker with WorkManager.
         */
        fun schedule(context: Context, intervalMinutes: Long = DEFAULT_INTERVAL_MINUTES) {
            val workRequest = PeriodicWorkRequestBuilder<BackgroundScanWorker>(
                intervalMinutes, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }

        const val ONE_TIME_WORK_NAME = "background_scan_worker_once"
        const val FULL_SCAN_WORK_NAME = "background_scan_worker_full"

        /**
         * Triggers an immediate one-time scan (e.g. right after a folder is added),
         * so the user doesn't have to wait for the next periodic run.
         *
         * Uses an EXPEDITED work request so the system runs it ASAP instead of
         * batching/deferring it (some OEM ROMs like ColorOS delay normal WorkManager
         * jobs by several minutes). Falls back to a normal request if the app is out
         * of expedited quota.
         */
        fun runOnce(context: Context) {
            val workRequest = androidx.work.OneTimeWorkRequestBuilder<BackgroundScanWorker>()
                .setExpedited(
                    androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }

        /**
         * Triggers an immediate one-time FULL scan (e.g. when the user taps
         * "立即备份"). Behaves like [runOnce] but passes [KEY_FORCE_FULL_SCAN] = true
         * so every image in each folder is re-enqueued regardless of lastScanTime.
         * The server dedups by hash, so already-backed-up files are skipped cheaply.
         *
         * Uses a dedicated unique work name ([FULL_SCAN_WORK_NAME]) so an
         * incremental [runOnce] (e.g. triggered by the MediaStore observer) can't
         * replace/cancel a pending full scan.
         */
        fun runNow(context: Context) {
            val workRequest = androidx.work.OneTimeWorkRequestBuilder<BackgroundScanWorker>()
                .setExpedited(
                    androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
                )
                .setInputData(
                    androidx.work.Data.Builder()
                        .putBoolean(KEY_FORCE_FULL_SCAN, true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                FULL_SCAN_WORK_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }

        /**
         * Cancels the periodic scan worker.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Reschedules the periodic scan worker with a new interval.
         */
        fun reschedule(context: Context, intervalMinutes: Long) {
            val workRequest = PeriodicWorkRequestBuilder<BackgroundScanWorker>(
                intervalMinutes, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val forceFullScan = inputData.getBoolean(KEY_FORCE_FULL_SCAN, false)
            android.util.Log.i("PhotoVaultScan", "BackgroundScanWorker started (forceFullScan=$forceFullScan)")

            // Sync photo status from server before scanning, so trashed/purged
            // files (deleted via web UI) are skipped. Only attempt sync when
            // the user has a valid auth token.
            if (credentialManager.hasValidToken()) {
                val synced = statusSyncManager.syncStatus()
                android.util.Log.i("PhotoVaultScan", "Status sync result: $synced records updated")
            }

            // Rebuild the queue for uploads that were interrupted by a process
            // kill: their persisted UploadRecord survives, but the in-memory
            // queue does not, so nothing would otherwise resume them.
            requeueResumableUploads()

            scanAllFolders(forceFullScan)

            // After scanning + requeue, make sure queued work (including the
            // resumed uploads above) actually starts when conditions allow and
            // the service isn't already running.
            maybeStartBackupForQueuedWork()

            android.util.Log.i("PhotoVaultScan", "BackgroundScanWorker finished successfully")
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("PhotoVaultScan", "BackgroundScanWorker failed: ${e.message}", e)
            Result.retry()
        }
    }

    /**
     * Provides foreground info for expedited execution. Required when running as an
     * expedited work request on Android < 12 (where it runs as a foreground service).
     */
    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo {
        val channelId = "photovault_scan"
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "扫描照片",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("PhotoVault")
            .setContentText("正在扫描新照片…")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        val notificationId = 4201
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            androidx.work.ForegroundInfo(
                notificationId,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            androidx.work.ForegroundInfo(notificationId, notification)
        }
    }

    /**
     * Re-enqueues files that have a persisted, still-valid [com.photovault.data.local.entity.UploadRecord]
     * but are missing from the in-memory [BackupQueue] — the typical aftermath of
     * the process being killed mid-upload. Their chunk progress is persisted both
     * locally and server-side, so [ChunkUploader.uploadFile] resumes them from the
     * last received chunk (断点续传) rather than restarting from zero.
     *
     * Expired sessions (older than 7 days) are skipped here; they'll be
     * rediscovered as ordinary new files by a full scan and re-uploaded fresh.
     */
    private suspend fun requeueResumableUploads() {
        val records = try {
            uploadRecordDao.getAll()
        } catch (e: Exception) {
            android.util.Log.e("PhotoVaultScan", "Failed to load upload records for resume: ${e.message}", e)
            return
        }
        if (records.isEmpty()) return

        val queuedUris = backupQueue.getAll().map { it.uri }.toSet()
        val now = System.currentTimeMillis()
        val sessionExpireMs = 7L * 24 * 60 * 60 * 1000

        val resumable = records
            .filter { now - it.createdAt <= sessionExpireMs }
            .filter { it.fileUri !in queuedUris }
            .map { record ->
                FileInfo(
                    uri = record.fileUri,
                    fileName = record.fileName,
                    fileSize = record.fileSize,
                    // fileModifiedTime was stored from the original FileInfo.createdTime.
                    createdTime = record.fileModifiedTime,
                    mimeType = record.mimeType.ifBlank { guessMimeFromName(record.fileName) },
                    folderUri = record.folderUri
                )
            }

        if (resumable.isNotEmpty()) {
            backupQueue.enqueue(resumable)
            android.util.Log.i(
                "PhotoVaultScan",
                "Re-queued ${resumable.size} interrupted upload(s) for resume"
            )
        }
    }

    /**
     * Starts the backup service if there is queued work (new or resumed) and
     * conditions allow, but the service isn't already running. [start] is
     * idempotent — a redundant call while running is a no-op.
     */
    private fun maybeStartBackupForQueuedWork() {
        if (backupQueue.size() > 0 &&
            !BackupForegroundService.isRunning &&
            backupConditionChecker.shouldStartBackup()
        ) {
            android.util.Log.i("PhotoVaultScan", "Starting backup service for ${backupQueue.size()} queued file(s)")
            BackupForegroundService.start(applicationContext)
        }
    }

    /**
     * Best-effort MIME guess from a file name, used when rebuilding a FileInfo
     * from a pre-migration UploadRecord that has no stored mime_type.
     */
    private fun guessMimeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (ext in VIDEO_EXTENSIONS) "video/*" else "image/*"
    }

    /**
     * Scans all registered backup folders for new images since lastScanTime.
     *
     * Files whose local photo_status is trashed or purged are filtered out
     * so they are not enqueued for backup. This applies to both automatic
     * periodic scans and the manual "立即备份" trigger (forceFullScan=true).
     * These files can only be re-uploaded via the per-photo "重新备份"
     * action in FolderDetailScreen.
     */
    private suspend fun scanAllFolders(forceFullScan: Boolean = false) {
        val folders = backupFolderDao.getAllOnce()
        android.util.Log.i("PhotoVaultScan", "Scanning ${folders.size} folder(s) (forceFullScan=$forceFullScan)")
        val currentTime = System.currentTimeMillis()
        val newFiles = mutableListOf<FileInfo>()

        // Get ALL photo statuses once (for efficient lookup)
        val allStatuses = photoStatusDao.getAll()
        val statusMap = allStatuses.associateBy { it.fileUri }

        for (folder in folders) {
            // Count ALL images in folder (for accurate total)
            val totalImages = countImagesInFolder(folder.folderUri)

            // Get all files in folder (no time filter for counting)
            val allPhotosInFolder = scanFolder(folder.folderUri, 0L)

            // Classify each file by its status from photoStatus table
            var folderBackedUp = 0
            var folderTrashed = 0
            var folderPurged = 0

            for (fileInfo in allPhotosInFolder) {
                val status = statusMap[fileInfo.uri]
                when {
                    status == null -> {
                        // No record = not yet processed (new file)
                    }
                    status.status == PhotoStatusValue.TRASHED -> {
                        folderTrashed++
                    }
                    status.status == PhotoStatusValue.PURGED -> {
                        folderPurged++
                    }
                    else -> {
                        // ACTIVE or other status = backed up
                        folderBackedUp++
                    }
                }
            }

            android.util.Log.i(
                "PhotoVaultScan",
                "Folder '${folder.folderName}': total=$totalImages, backedUp=$folderBackedUp, trashed=$folderTrashed, purged=$folderPurged"
            )

            // Scan for newly modified files (for incremental backup)
            val effectiveScanTime = if (forceFullScan) 0L else folder.lastScanTime
            val folderNewFiles = scanFolder(folder.folderUri, effectiveScanTime)
            // DATE_MODIFIED (epoch ms) of files skipped this round because they are within
            // the quiet period (camera may still be writing). Used to roll back lastScanTime
            // so they are re-discovered next scan (R1.2). Applies to both forceFullScan and
            // periodic scans since both share this result-layer path (R1.4).
            val skippedModifiedTimes = mutableListOf<Long>()
            for (fileInfo in folderNewFiles) {
                val status = statusMap[fileInfo.uri]
                if (status == null) {
                    // No record = truly new file. Skip it this round if it was modified so
                    // recently it may still be being written/finalized (R1.1). A skipped file
                    // is neither enqueued nor treated as "seen" (R1.3). createdTime == 0 is
                    // never skipped (handled inside shouldSkipForQuietPeriod, R1.5).
                    if (QuietPeriodLogic.shouldSkipForQuietPeriod(currentTime, fileInfo.createdTime)) {
                        val secondsAgo = (currentTime - fileInfo.createdTime) / 1000
                        android.util.Log.i(
                            "PhotoVaultScan",
                            "quiet-period skip '${fileInfo.fileName}' modified ${secondsAgo}s ago"
                        )
                        skippedModifiedTimes.add(fileInfo.createdTime)
                    } else {
                        // Truly new and stable file, add to backup queue
                        newFiles.add(fileInfo)
                    }
                }
                // Files with existing status (active/trashed/purged) are NOT re-uploaded
            }

            // Roll back lastScanTime past the earliest skipped file so it isn't missed
            // next scan; preserves currentTime when nothing was skipped (R1.2/R1.3).
            val nextScanTime = QuietPeriodLogic.computeNextScanTime(currentTime, skippedModifiedTimes)

            backupFolderDao.update(
                folder.copy(
                    lastScanTime = nextScanTime,
                    totalImages = totalImages,
                    backedUpImages = folderBackedUp,
                    trashedImages = folderTrashed,
                    purgedImages = folderPurged
                )
            )
        }

        android.util.Log.i("PhotoVaultScan", "Total new files to backup: ${newFiles.size}")

        // Enqueue new files for backup if conditions are met
        if (newFiles.isNotEmpty()) {
            backupQueue.enqueue(newFiles)
            val shouldStart = backupConditionChecker.shouldStartBackup()
            val networkOk = backupConditionChecker.isNetworkAvailableForBackup()
            val isCharging = backupConditionChecker.isCharging()
            android.util.Log.i(
                "PhotoVaultScan",
                "Enqueued ${newFiles.size} file(s). forceFullScan=$forceFullScan, " +
                    "isCharging=$isCharging, shouldStartBackup=$shouldStart " +
                    "(battery=${backupConditionChecker.getBatteryLevel()}, networkOk=$networkOk)"
            )

            // If backup conditions are met, trigger backup service.
            // When forceFullScan is true (user tapped "立即备份"), battery limit is ignored if charging.
            val canStart = if (forceFullScan) {
                networkOk && (isCharging || backupConditionChecker.shouldStartBackup())
            } else {
                shouldStart
            }
            if (canStart) {
                BackupForegroundService.start(applicationContext)
            }
        } else {
            android.util.Log.i("PhotoVaultScan", "No new files to back up")
        }
    }

    /**
     * Scans a single folder for new image and video files since the given timestamp.
     *
     * Uses MediaStore instead of SAF/DocumentFile traversal: on some OEM ROMs
     * (e.g. ColorOS) the ExternalStorageProvider returns empty results for tree-URI
     * child queries even when the folder is readable and contains files. MediaStore
     * reliably indexes all media and supports openInputStream on the returned URIs.
     *
     * Both the image (MediaStore.Images) and video (MediaStore.Video) collections
     * are queried so photos and videos are backed up together.
     */
    private fun scanFolder(folderUri: String, lastScanTime: Long): List<FileInfo> {
        val relativeDir = treeUriToRelativePath(folderUri) ?: return emptyList()
        android.util.Log.i("PhotoVaultScan", "  relativePath='$relativeDir/' querying MediaStore")
        val images = queryMediaStore(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            relativeDir, lastScanTime, folderUri, defaultMime = "image/*"
        )
        val videos = queryMediaStore(
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            relativeDir, lastScanTime, folderUri, defaultMime = "video/*"
        )
        return images + videos
    }

    /**
     * Converts a SAF tree URI string into a MediaStore-style relative directory.
     * e.g. content://.../tree/primary%3APictures%2Fbili  ->  "Pictures/bili"
     */
    private fun treeUriToRelativePath(folderUri: String): String? {
        return try {
            val treeUri = android.net.Uri.parse(folderUri)
            val docId = DocumentsContract.getTreeDocumentId(treeUri) // e.g. "primary:Pictures/bili"
            docId.substringAfter(':').trim('/')
        } catch (e: Exception) {
            android.util.Log.e("PhotoVaultScan", "  cannot parse tree uri: $folderUri", e)
            null
        }
    }

    /**
     * Queries a MediaStore [collection] (Images or Video) for media under the given
     * relative directory (recursively), modified after [lastScanTime] (ms).
     * Returns FileInfo with MediaStore content URIs.
     *
     * Uses the generic [android.provider.MediaStore.MediaColumns] which are shared by
     * both the Images and Video collections, so the same query works for either.
     */
    private fun queryMediaStore(
        collection: android.net.Uri,
        relativeDir: String,
        lastScanTime: Long,
        folderUri: String,
        defaultMime: String
    ): List<FileInfo> {
        val results = mutableListOf<FileInfo>()
        val projection = arrayOf(
            android.provider.MediaStore.MediaColumns._ID,
            android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
            android.provider.MediaStore.MediaColumns.SIZE,
            android.provider.MediaStore.MediaColumns.DATE_MODIFIED,
            android.provider.MediaStore.MediaColumns.MIME_TYPE
        )
        val lastScanSeconds = lastScanTime / 1000

        val selection: String
        val args: Array<String>
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // RELATIVE_PATH matches "Pictures/bili/" and any nested subfolder.
            // IS_PENDING = 0 excludes media the creator (camera/downloader) hasn't
            // finalized yet, so we never pick up files still being written (R2.1).
            selection = "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                "${android.provider.MediaStore.MediaColumns.DATE_MODIFIED} > ?" +
                " AND ${android.provider.MediaStore.MediaColumns.IS_PENDING} = 0"
            args = arrayOf("$relativeDir/%", lastScanSeconds.toString())
        } else {
            @Suppress("DEPRECATION")
            selection = "${android.provider.MediaStore.MediaColumns.DATA} LIKE ? AND " +
                "${android.provider.MediaStore.MediaColumns.DATE_MODIFIED} > ?"
            args = arrayOf("%/$relativeDir/%", lastScanSeconds.toString())
        }

        try {
            applicationContext.contentResolver.query(
                collection, projection, selection, args, null
            )?.use { cursor ->
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

                    results.add(
                        FileInfo(
                            uri = contentUri.toString(),
                            fileName = name,
                            fileSize = size,
                            createdTime = dateModifiedMs,
                            mimeType = mime,
                            folderUri = folderUri
                        )
                    )
                }
            }
            android.util.Log.i("PhotoVaultScan", "  MediaStore($defaultMime) returned ${results.size} item(s)")
        } catch (e: Exception) {
            android.util.Log.e("PhotoVaultScan", "  MediaStore query failed: ${e.message}", e)
        }
        return results
    }

    /**
     * Counts total media files (images + videos) in a folder (including subfolders)
     * via MediaStore.
     */
    private fun countImagesInFolder(folderUri: String): Int {
        val relativeDir = treeUriToRelativePath(folderUri) ?: return 0
        return countInCollection(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, relativeDir
        ) + countInCollection(
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, relativeDir
        )
    }

    /**
     * Counts media files under [relativeDir] within the given MediaStore [collection].
     */
    private fun countInCollection(collection: android.net.Uri, relativeDir: String): Int {
        val projection = arrayOf(android.provider.MediaStore.MediaColumns._ID)

        val selection: String
        val args: Array<String>
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Same IS_PENDING = 0 filter as queryMediaStore so total/backedUp/pending
            // counts share one consistent basis with the scan query (R2.3).
            selection = "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" +
                " AND ${android.provider.MediaStore.MediaColumns.IS_PENDING} = 0"
            args = arrayOf("$relativeDir/%")
        } else {
            @Suppress("DEPRECATION")
            selection = "${android.provider.MediaStore.MediaColumns.DATA} LIKE ?"
            args = arrayOf("%/$relativeDir/%")
        }

        return try {
            applicationContext.contentResolver.query(
                collection, projection, selection, args, null
            )?.use { it.count } ?: 0
        } catch (e: Exception) {
            0
        }
    }
}
