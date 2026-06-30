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
import com.photovault.data.local.entity.BackupHistoryRecord
import com.photovault.data.local.entity.BackupStatus
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

                updateNotification(
                    title = "正在备份: $currentFileName",
                    content = "进度: ${completedFiles + 1}/$totalFiles"
                )

                // Look up the folder's storage policy for this file
                val folder = backupFolderDao.getByUri(fileInfo.folderUri)
                val storagePolicy = StoragePolicyConfig(
                    useCustomPath = folder?.useCustomPath ?: false,
                    customPath = folder?.customPath,
                    useYearMonthLayer = folder?.useYearMonthLayer ?: false
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
                        incrementBackedUpCount(fileInfo.folderUri)
                    }
                    is UploadResult.Duplicate -> {
                        android.util.Log.i(
                            "PhotoVaultBackup",
                            "Skipped duplicate ${fileInfo.fileName}"
                        )
                        // Save skipped record with reason "文件已存在" and increment backed up count
                        saveHistoryRecord(fileInfo, BackupStatus.SKIPPED, "文件已存在")
                        incrementBackedUpCount(fileInfo.folderUri)
                    }
                    is UploadResult.Skipped -> {
                        android.util.Log.i(
                            "PhotoVaultBackup",
                            "Skipped ${fileInfo.fileName}: ${result.reason}"
                        )
                        // Trashed/purged files are skipped but still count as backed up
                        // since they already exist on the server, just in different status
                        saveHistoryRecord(fileInfo, BackupStatus.SKIPPED, result.reason)
                        incrementBackedUpCount(fileInfo.folderUri)
                    }
                    is UploadResult.Failed -> {
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
     * Increments the backed up images count for the given folder.
     */
    private suspend fun incrementBackedUpCount(folderUri: String) {
        try {
            val folder = backupFolderDao.getByUri(folderUri)
            if (folder != null) {
                val updated = folder.copy(backedUpImages = folder.backedUpImages + 1)
                backupFolderDao.update(updated)
            }
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
