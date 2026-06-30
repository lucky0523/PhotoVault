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
    private val credentialManager: CredentialManager
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
                ONE_TIME_WORK_NAME,
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

            scanAllFolders(forceFullScan)
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
        var skippedTrashed = 0
        var skippedPurged = 0

        for (folder in folders) {
            // When forcing a full scan, ignore the stored lastScanTime so every image
            // is found and re-enqueued (server dedups by hash).
            val effectiveScanTime = if (forceFullScan) 0L else folder.lastScanTime
            val folderNewFiles = scanFolder(folder.folderUri, effectiveScanTime)
            android.util.Log.i(
                "PhotoVaultScan",
                "Folder '${folder.folderName}' (lastScan=$effectiveScanTime): found ${folderNewFiles.size} new file(s)"
            )

            // Filter out files that are trashed or purged on the server.
            for (fileInfo in folderNewFiles) {
                val status = photoStatusDao.getByFileUri(fileInfo.uri)
                if (status == null) {
                    newFiles.add(fileInfo)
                } else when (status.status) {
                    PhotoStatusValue.TRASHED -> {
                        skippedTrashed++
                        android.util.Log.d(
                            "PhotoVaultScan",
                            "  skipping trashed file: ${fileInfo.fileName}"
                        )
                    }
                    PhotoStatusValue.PURGED -> {
                        skippedPurged++
                        android.util.Log.d(
                            "PhotoVaultScan",
                            "  skipping purged file: ${fileInfo.fileName}"
                        )
                    }
                    else -> {
                        newFiles.add(fileInfo)
                    }
                }
            }

            // Update folder stats in database
            val totalImages = countImagesInFolder(folder.folderUri)
            backupFolderDao.update(
                folder.copy(
                    lastScanTime = currentTime,
                    totalImages = totalImages
                )
            )
        }

        if (skippedTrashed > 0 || skippedPurged > 0) {
            android.util.Log.i(
                "PhotoVaultScan",
                "Skipped $skippedTrashed trashed + $skippedPurged purged file(s)"
            )
        }

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
     * Scans a single folder for new image files since the given timestamp.
     *
     * Uses MediaStore instead of SAF/DocumentFile traversal: on some OEM ROMs
     * (e.g. ColorOS) the ExternalStorageProvider returns empty results for tree-URI
     * child queries even when the folder is readable and contains files. MediaStore
     * reliably indexes all images and supports openInputStream on the returned URIs.
     */
    private fun scanFolder(folderUri: String, lastScanTime: Long): List<FileInfo> {
        val relativeDir = treeUriToRelativePath(folderUri) ?: return emptyList()
        android.util.Log.i("PhotoVaultScan", "  relativePath='$relativeDir/' querying MediaStore")
        return queryMediaStoreImages(relativeDir, lastScanTime, folderUri)
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
     * Queries MediaStore for images under the given relative directory (recursively),
     * modified after [lastScanTime] (ms). Returns FileInfo with MediaStore content URIs.
     */
    private fun queryMediaStoreImages(
        relativeDir: String,
        lastScanTime: Long,
        folderUri: String
    ): List<FileInfo> {
        val results = mutableListOf<FileInfo>()
        val collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            android.provider.MediaStore.Images.Media._ID,
            android.provider.MediaStore.Images.Media.DISPLAY_NAME,
            android.provider.MediaStore.Images.Media.SIZE,
            android.provider.MediaStore.Images.Media.DATE_MODIFIED,
            android.provider.MediaStore.Images.Media.MIME_TYPE
        )
        val lastScanSeconds = lastScanTime / 1000

        val selection: String
        val args: Array<String>
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // RELATIVE_PATH matches "Pictures/bili/" and any nested subfolder
            selection = "${android.provider.MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND " +
                "${android.provider.MediaStore.Images.Media.DATE_MODIFIED} > ?"
            args = arrayOf("$relativeDir/%", lastScanSeconds.toString())
        } else {
            @Suppress("DEPRECATION")
            selection = "${android.provider.MediaStore.Images.Media.DATA} LIKE ? AND " +
                "${android.provider.MediaStore.Images.Media.DATE_MODIFIED} > ?"
            args = arrayOf("%/$relativeDir/%", lastScanSeconds.toString())
        }

        try {
            applicationContext.contentResolver.query(
                collection, projection, selection, args, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "unknown"
                    val size = cursor.getLong(sizeCol)
                    val dateModifiedMs = cursor.getLong(dateCol) * 1000L
                    val mime = cursor.getString(mimeCol) ?: "image/*"
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
            android.util.Log.i("PhotoVaultScan", "  MediaStore returned ${results.size} image(s)")
        } catch (e: Exception) {
            android.util.Log.e("PhotoVaultScan", "  MediaStore query failed: ${e.message}", e)
        }
        return results
    }

    /**
     * Counts total image files in a folder (including subfolders) via MediaStore.
     */
    private fun countImagesInFolder(folderUri: String): Int {
        val relativeDir = treeUriToRelativePath(folderUri) ?: return 0
        val collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(android.provider.MediaStore.Images.Media._ID)

        val selection: String
        val args: Array<String>
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            selection = "${android.provider.MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            args = arrayOf("$relativeDir/%")
        } else {
            @Suppress("DEPRECATION")
            selection = "${android.provider.MediaStore.Images.Media.DATA} LIKE ?"
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
