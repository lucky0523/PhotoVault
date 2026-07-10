package com.photovault.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.photovault.MainActivity
import com.photovault.R
import com.photovault.data.api.model.StoragePolicyConfig
import com.photovault.data.api.model.UploadProgress
import com.photovault.data.api.model.UploadResult
import com.photovault.data.api.model.UploadState
import com.photovault.data.local.CredentialManager
import com.photovault.data.local.dao.BackupFolderDao
import com.photovault.data.local.dao.BackupHistoryDao
import com.photovault.data.local.dao.PhotoStatusDao
import com.photovault.data.local.entity.BackupHistoryRecord
import com.photovault.data.local.entity.BackupStatus
import com.photovault.ui.main.tabs.applyBackedUpCountDelta
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that manages the backup process.
 *
 * Displays a persistent notification showing:
 * - Current file name being uploaded
 * - Progress (X/Y files completed)
 * - Transfer speed
 *
 * Uses notification channel "backup_progress" for Android O+.
 */
@AndroidEntryPoint
class BackupForegroundService : Service() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "backup_progress"
        const val NOTIFICATION_CHANNEL_NAME = "备份进度"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.photovault.action.START_BACKUP"
        const val ACTION_PAUSE = "com.photovault.action.PAUSE_BACKUP"
        const val ACTION_STOP = "com.photovault.action.STOP_BACKUP"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isPaused: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, BackupForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pause(context: Context) {
            val intent = Intent(context, BackupForegroundService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BackupForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    @Inject
    lateinit var backupQueue: BackupQueue

    @Inject
    lateinit var backupConditionChecker: BackupConditionChecker

    @Inject
    lateinit var chunkUploader: ChunkUploader

    @Inject
    lateinit var backupFolderDao: BackupFolderDao

    @Inject
    lateinit var backupHistoryDao: BackupHistoryDao

    @Inject
    lateinit var photoStatusDao: PhotoStatusDao

    @Inject
    lateinit var credentialManager: CredentialManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var backupJob: Job? = null

    private var currentFileName: String = ""
    private var completedFiles: Int = 0
    private var totalFiles: Int = 0
    private var transferSpeedBytesPerSec: Long = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                isRunning = true
                isPaused = false
                startForeground(NOTIFICATION_ID, buildNotification())
                startBackupProcess()
            }
            ACTION_PAUSE -> {
                isPaused = true
                backupJob?.cancel()
                updateNotification("备份已暂停", "等待条件恢复...")
            }
            ACTION_STOP -> {
                stopBackup()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        isPaused = false
        serviceScope.cancel()
    }

    private fun startBackupProcess() {
        // Guard against launching a second concurrent loop when start() is
        // delivered while a backup is already actively running (e.g. a
        // connectivity broadcast arriving during normal operation).
        if (backupJob?.isActive == true) return

        totalFiles = backupQueue.size()
        completedFiles = 0

        backupJob = serviceScope.launch {
            while (backupQueue.size() > 0 && !isPaused) {
                // Check conditions before each file
                if (backupConditionChecker.shouldPauseBackup()) {
                    isPaused = true
                    updateNotification("备份已暂停", "等待条件恢复...")
                    break
                }

                val fileInfo = backupQueue.dequeue() ?: break
                currentFileName = fileInfo.fileName

                // Fallback guard: if this file's backup folder has been removed,
                // drop it instead of uploading. The queue is normally purged when
                // a folder is removed (LocalTabViewModel.removeFolder); this covers
                // any file already dequeued/in-flight at that moment so a removed
                // folder never keeps backing up. A null folder here means "no
                // longer registered" (findFolder already tolerates URL-encoding).
                val folder = findFolder(fileInfo.folderUri)
                if (folder == null) {
                    android.util.Log.i(
                        "PhotoVaultBackup",
                        "Skipping ${fileInfo.fileName}: its backup folder was removed"
                    )
                    continue
                }

                // Capture the file's status BEFORE uploading (the uploader flips it
                // to `active` via StatusSyncManager.markActive on success). A
                // re-backup of a trashed/purged file must move it out of that
                // bucket, not just add to backed-up — see [incrementBackedUpCount].
                val priorStatus = try {
                    photoStatusDao.getByFileUri(fileInfo.uri)?.status
                } catch (e: Exception) {
                    null
                }

                updateNotification(
                    title = "正在备份: $currentFileName",
                    content = "进度: ${completedFiles + 1}/$totalFiles"
                )

                // Storage policy for this file, from the folder resolved above.
                // (Matched by canonical key so a re-backup's URL-decoded
                // folderUri still resolves to the stored folder row.)
                val storagePolicy = StoragePolicyConfig(
                    useCustomPath = folder.useCustomPath,
                    customPath = folder.customPath,
                    useYearMonthLayer = folder.useYearMonthLayer
                )

                // Track speed across progress callbacks
                var lastBytes = 0L
                var lastTimeMs = System.currentTimeMillis()

                val result = try {
                    chunkUploader.uploadFile(
                        context = applicationContext,
                        fileInfo = fileInfo,
                        storagePolicy = storagePolicy
                    ) { progress ->
                        val now = System.currentTimeMillis()
                        val elapsedMs = now - lastTimeMs
                        if (elapsedMs >= 500) {
                            val deltaBytes = progress.uploadedBytes - lastBytes
                            if (deltaBytes > 0) {
                                transferSpeedBytesPerSec = deltaBytes * 1000 / elapsedMs
                            }
                            lastBytes = progress.uploadedBytes
                            lastTimeMs = now
                        }
                        updateNotificationForProgress(progress)
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    // The job was cancelled mid-upload (e.g. pause() fired because
                    // WiFi dropped). Put the file back on the queue so it resumes
                    // from its persisted chunk progress (断点续传) once conditions
                    // recover, then let the cancellation propagate to end the loop.
                    backupQueue.enqueue(listOf(fileInfo))
                    android.util.Log.i(
                        "PhotoVaultBackup",
                        "Upload of ${fileInfo.fileName} cancelled; re-queued for resume"
                    )
                    throw ce
                } catch (e: Exception) {
                    android.util.Log.e(
                        "PhotoVaultBackup",
                        "Upload failed for ${fileInfo.fileName}: ${e.message}",
                        e
                    )
                    UploadResult.Failed(e.message ?: "unknown error", shouldRetry = false)
                }

                when (result) {
                    is UploadResult.Success -> {
                        android.util.Log.i(
                            "PhotoVaultBackup",
                            "Uploaded ${fileInfo.fileName} -> ${result.storedPath}"
                        )
                        // Save success record and increment backed up count
                        saveHistoryRecord(fileInfo, BackupStatus.SUCCESS)
                        incrementBackedUpCount(fileInfo.folderUri, priorStatus)
                    }
                    is UploadResult.Duplicate -> {
                        android.util.Log.i(
                            "PhotoVaultBackup",
                            "Skipped duplicate ${fileInfo.fileName}"
                        )
                        // Save skipped record with reason "云端已存在" and increment backed up count
                        saveHistoryRecord(fileInfo, BackupStatus.SKIPPED, "云端已存在")
                        incrementBackedUpCount(fileInfo.folderUri, priorStatus)
                    }
                    is UploadResult.Skipped -> {
                        android.util.Log.i(
                            "PhotoVaultBackup",
                            "Skipped ${fileInfo.fileName}: ${result.reason}"
                        )
                        // Record the skip with its reason. Files skipped because they
                        // already exist on the server (recycle bin / purged) still count
                        // as backed up; a deleted source file does not.
                        saveHistoryRecord(fileInfo, BackupStatus.SKIPPED, result.reason)
                        if (result.countsAsBackedUp) {
                            incrementBackedUpCount(fileInfo.folderUri, priorStatus)
                        }
                    }
                    is UploadResult.Failed -> {
                        // Distinguish a transient interruption (network/battery
                        // lost while uploading, chunk retries exhausted) from a
                        // genuine, non-retryable file error. On a transient
                        // interruption, keep the file queued and pause so the
                        // condition-recovery path resumes it later (断点续传)
                        // instead of burning it as a permanent failure.
                        if (result.shouldRetry && backupConditionChecker.shouldPauseBackup()) {
                            backupQueue.enqueue(listOf(fileInfo))
                            isPaused = true
                            android.util.Log.i(
                                "PhotoVaultBackup",
                                "Upload of ${fileInfo.fileName} interrupted by conditions; re-queued for resume"
                            )
                            updateNotification("备份已暂停", "等待条件恢复...")
                            break
                        }
                        android.util.Log.w(
                            "PhotoVaultBackup",
                            "Failed ${fileInfo.fileName}: ${result.error}"
                        )
                        // Save failed record
                        saveHistoryRecord(fileInfo, BackupStatus.FAILED, result.error)
                    }
                }

                completedFiles++
                updateNotification(
                    title = "正在备份",
                    content = "已完成: $completedFiles/$totalFiles"
                )
            }

            if (backupQueue.isEmpty() && !isPaused) {
                updateNotification("备份完成", "已备份 $completedFiles 个文件")
                // Auto-stop after a short delay to let user see the completion notification
                delay(3000)
                stopBackup()
            }
        }
    }

    /**
     * Maps a [UploadProgress] from the uploader into the foreground notification.
     */
    private fun updateNotificationForProgress(progress: UploadProgress) {
        currentFileName = progress.fileName
        val stateText = when (progress.state) {
            UploadState.HASHING -> "计算校验值…"
            UploadState.CHECKING_DUPLICATE -> "检查重复…"
            UploadState.INITIALIZING -> "准备上传…"
            UploadState.UPLOADING -> {
                val speedText = formatSpeed(transferSpeedBytesPerSec)
                val sizeText = "${formatBytes(progress.uploadedBytes)}/${formatBytes(progress.totalBytes)}"
                "$sizeText · $speedText"
            }
            UploadState.COMPLETING -> "完成中…"
            UploadState.COMPLETED -> "已完成"
            UploadState.SKIPPED_DUPLICATE -> "已存在，跳过"
            UploadState.SKIPPED_TRASHED -> "回收站中，跳过"
            UploadState.SKIPPED_PURGED -> "已删除，跳过"
            UploadState.FAILED -> "上传失败"
        }
        updateNotification(
            title = "正在备份: ${progress.fileName}",
            content = "进度: ${completedFiles + 1}/$totalFiles · $stateText"
        )
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
    }

    private fun stopBackup() {
        backupJob?.cancel()
        isRunning = false
        isPaused = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Saves a backup history record to the database.
     */
    private suspend fun saveHistoryRecord(
        fileInfo: FileInfo,
        status: BackupStatus,
        errorMessage: String? = null
    ) {
        try {
            val record = BackupHistoryRecord(
                fileUri = fileInfo.uri,
                fileName = fileInfo.fileName,
                fileSize = fileInfo.fileSize,
                status = status,
                errorMessage = errorMessage,
                folderUri = fileInfo.folderUri
            )
            backupHistoryDao.insert(record)
        } catch (e: Exception) {
            android.util.Log.e(
                "PhotoVaultBackup",
                "Failed to save history record for ${fileInfo.fileName}",
                e
            )
        }
    }

    /**
     * Resolves a [com.photovault.data.local.entity.BackupFolder] for [folderUri],
     * tolerant of URL-encoding differences.
     *
     * A re-backup's folderUri arrives URL-decoded (see [canonicalFolderKey]) and
     * no longer matches the percent-encoded value stored in the DB, so an exact
     * `getByUri` fails. We fall back to matching by canonical key across all
     * folders. Plain backups (folderUri straight from the DB) hit the fast exact
     * match and skip the scan.
     */
    private suspend fun findFolder(folderUri: String): com.photovault.data.local.entity.BackupFolder? {
        backupFolderDao.getByUri(folderUri)?.let { return it }
        val key = canonicalFolderKey(folderUri)
        return backupFolderDao.getAllOnce().firstOrNull { canonicalFolderKey(it.folderUri) == key }
    }

    /**
     * Increments the backed up images count for the given folder, keeping the
     * aggregate status counts consistent.
     *
     * A plain upload of a not-backed-up file simply moves it into the backed-up
     * bucket (backed-up + 1; the not-backed-up count is derived, so no other
     * column changes). A re-backup of a **trashed/purged** file additionally
     * moves it OUT of that bucket, so we decrement the corresponding column —
     * otherwise the 回收站/已删除 chip in LocalTab would keep counting a file that
     * is now backed up. [priorStatus] is the photo_status value captured BEFORE
     * the upload flipped it to `active`.
     */
    private suspend fun incrementBackedUpCount(folderUri: String, priorStatus: String?) {
        try {
            val folder = findFolder(folderUri) ?: return
            backupFolderDao.update(applyBackedUpCountDelta(folder, priorStatus))
        } catch (e: Exception) {
            android.util.Log.e(
                "PhotoVaultBackup",
                "Failed to increment backed up count for $folderUri",
                e
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示图片备份进度"
                setShowBadge(false)
            }

            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        title: String = "准备备份...",
        content: String = "正在检查文件..."
    ): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        val notification = buildNotification(title, content)
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Updates the notification with transfer speed information.
     */
    fun updateProgress(fileName: String, completed: Int, total: Int, speedBytesPerSec: Long) {
        currentFileName = fileName
        completedFiles = completed
        totalFiles = total
        transferSpeedBytesPerSec = speedBytesPerSec

        val speedText = formatSpeed(speedBytesPerSec)
        updateNotification(
            title = "正在备份: $fileName",
            content = "进度: $completed/$total · $speedText"
        )
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> "${bytesPerSec / (1024 * 1024)} MB/s"
            bytesPerSec >= 1024 -> "${bytesPerSec / 1024} KB/s"
            else -> "$bytesPerSec B/s"
        }
    }
}
