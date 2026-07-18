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
import com.photovault.data.local.entity.UploadRecord
import com.photovault.ui.main.tabs.applyBackedUpCountDelta
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Why a [Backup_Task] is currently paused.
 *
 * - [USER]: the user explicitly tapped "暂停" on the 备份任务 Tab. A user pause
 *   takes priority over condition recovery — it must NOT be auto-resumed by
 *   [ConditionCheckWorker] when battery/network recovers (R-24.5). Only the
 *   user tapping "开始" (ACTION_RESUME) clears it.
 * - [CONDITION]: the backup was paused automatically because a [Backup_Condition]
 *   dropped (WiFi lost / battery low). This is the only pause that
 *   [ConditionCheckWorker] auto-resumes once conditions recover.
 */
enum class PauseReason { USER, CONDITION }

/**
 * Live snapshot of the single In_Flight_File's upload progress, published on
 * [BackupForegroundService.uploadProgress] so the 备份任务 Tab can show the real
 * file name, transferred/total bytes, transfer speed, and the current
 * [UploadState]. `null` when nothing is actively uploading (idle / paused /
 * between files after a stop).
 */
data class BackupProgressSnapshot(
    val fileName: String,
    val uploadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val state: UploadState
)

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

        /**
         * Separate, non-ongoing channel/notification used to surface the
         * "自动备份已关闭，有 N 个未完成任务" prompt (R-30.2). Kept distinct from the
         * foreground-progress channel/ID because [stopAuto] tears the foreground
         * service (and its NOTIFICATION_ID notification) down; this reminder must
         * live on independently so the user still sees why uploading stopped and
         * where to continue it.
         */
        const val AUTO_OFF_CHANNEL_ID = "backup_notice"
        const val AUTO_OFF_CHANNEL_NAME = "备份提示"
        const val AUTO_OFF_NOTIFICATION_ID = 1002

        const val ACTION_START = "com.photovault.action.START_BACKUP"
        const val ACTION_PAUSE = "com.photovault.action.PAUSE_BACKUP"
        const val ACTION_RESUME = "com.photovault.action.RESUME_BACKUP"
        const val ACTION_STOP = "com.photovault.action.STOP_BACKUP"

        /** Intent extra carrying the [PauseReason] name for [ACTION_PAUSE]. */
        const val KEY_PAUSE_REASON = "com.photovault.extra.PAUSE_REASON"

        /**
         * Intent extra (boolean) carrying whether the current run was started by
         * an explicit user action (manual) vs an automatic trigger. Consumed by
         * [ACTION_START] and stored in [isManualRun].
         */
        const val KEY_MANUAL_RUN = "com.photovault.extra.MANUAL_RUN"

        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Live upload progress of the current In_Flight_File for the UI, or null
         * when nothing is uploading. Fed from the uploader's progress callback
         * (see [updateNotificationForProgress]) and cleared when the run starts
         * fresh, pauses, stops, or the service is destroyed. Observed by
         * [com.photovault.ui.main.tabs.TasksTabViewModel] to drive the current
         * upload card (file name / bytes / speed / progress / ETA).
         */
        private val _uploadProgress = MutableStateFlow<BackupProgressSnapshot?>(null)
        val uploadProgress: StateFlow<BackupProgressSnapshot?> = _uploadProgress.asStateFlow()

        /**
         * True when the run currently in progress was initiated by an explicit
         * user action (立即备份 FAB, single-photo 重新备份, or the task-page 重试),
         * false when it was started by an automatic trigger (periodic scan,
         * condition recovery, boot reschedule, capability back-fill, etc.).
         *
         * This is the deciding factor for what happens when the user turns OFF
         * the "自动备份" switch mid-run: an automatic run is stopped and its queue
         * cleared (R-3.14), while a manual run is left to finish (R-3.15). See
         * [SettingsViewModel.setAutoBackupEnabled] and [stopAuto].
         */
        @Volatile
        var isManualRun: Boolean = false
            private set

        /** Merged "paused for any reason" flag (user OR condition). */
        @Volatile
        var isPaused: Boolean = false
            private set

        /**
         * True only when the current pause was explicitly requested by the user
         * (ACTION_PAUSE with [PauseReason.USER]). A user pause takes priority over
         * condition recovery: [ConditionCheckWorker] must not auto-resume while
         * this is true (R-24.5).
         */
        @Volatile
        var isUserPaused: Boolean = false
            private set

        /** Why the backup is currently paused, or null when not paused. */
        @Volatile
        var pauseReason: PauseReason? = null
            private set

        /**
         * The SAF URI of the file currently being uploaded, or null when no file
         * is in flight. Because files are uploaded one at a time, there is at most
         * one In_Flight_File at any moment. Set right after [BackupQueue.dequeue]
         * and cleared once that file finishes (success / skip / duplicate /
         * failure / cancellation / re-queue), so a concurrent "自动备份" toggle-off
         * can identify the single In_Flight_File to mark as AUTO_OFF (R-25.1/25.2).
         * When the service has been killed by the system this value is lost, and
         * callers fall back to the persisted Upload_Record criterion below
         * ([selectInFlightForAutoOff]).
         */
        @Volatile
        var currentFileUri: String? = null
            private set

        /**
         * Partitions [records] into the ones that represent an In_Flight_File —
         * i.e. a file for which at least one chunk has already been confirmed —
         * versus the rest. A record belongs to the "mark AUTO_OFF" set if and only
         * if its `uploaded_chunk_index >= 0` (R-25.2/25.5).
         *
         * Pure function so the partition can be unit-tested without a running
         * service or database, mirroring [shouldStopAutoOnDisable]. Higher-level
         * concerns (expiry, folder-still-exists) are applied by the caller in a
         * later step; this only performs the `uploaded_chunk_index` partition.
         *
         * @return the subset of [records] with `uploaded_chunk_index >= 0`, in
         *   input order.
         */
        fun selectInFlightForAutoOff(records: List<UploadRecord>): List<UploadRecord> {
            return records.filter { it.uploadedChunkIndex >= 0 }
        }

        /**
         * Starts the backup service.
         *
         * @param manual true when the run is an explicit user action (立即备份,
         *   单张重新备份, 任务页重试) so it is exempt from the "自动备份" toggle-off
         *   stop logic (R-3.15); false for automatic triggers (R-3.14). Passed via
         *   [KEY_MANUAL_RUN] and stored in [isManualRun].
         */
        fun start(context: Context, manual: Boolean = false) {
            val intent = Intent(context, BackupForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(KEY_MANUAL_RUN, manual)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Pauses the backup. [reason] distinguishes a user-initiated pause (default)
         * from an automatic condition-driven pause; only the latter is auto-resumed
         * by [ConditionCheckWorker].
         */
        fun pause(context: Context, reason: PauseReason = PauseReason.USER) {
            val intent = Intent(context, BackupForegroundService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(KEY_PAUSE_REASON, reason.name)
            }
            context.startService(intent)
        }

        /**
         * Resumes a paused backup at the user's request (ACTION_RESUME): clears the
         * user-pause flag and continues uploading the queue from the persisted
         *断点续传 progress when conditions allow.
         */
        fun resume(context: Context) {
            val intent = Intent(context, BackupForegroundService::class.java).apply {
                action = ACTION_RESUME
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BackupForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * Decides whether turning OFF the "自动备份" switch should stop the backup
         * and clear its queue. Pure function so the gating can be unit-tested
         * without a running service.
         *
         * Returns true (stop the service + clear the queue, R-3.14) in every case
         * EXCEPT when a **manual** run is currently in progress — a manual task is
         * left to finish untouched (R-3.15). When the service isn't running but the
         * queue still holds automatically-enqueued files, [isManualRun] is false so
         * this returns true and the caller clears that residual queue (R-3.14).
         */
        fun shouldStopAutoOnDisable(isRunning: Boolean, isManualRun: Boolean): Boolean {
            return !(isRunning && isManualRun)
        }

        /**
         * Stops the service because the "自动备份" switch was turned off while an
         * **automatic** run was in progress (R-3.14). Resets the run-source marker
         * ([isManualRun]) immediately so a subsequent (re)start after re-enabling
         * the switch isn't mistaken for a manual run, then stops the service.
         *
         * The in-flight file's persisted [UploadRecord] is intentionally left
         * untouched: only the in-memory [BackupQueue] is cleared by the caller
         * ([SettingsViewModel.setAutoBackupEnabled]), so re-enabling auto-backup
         * resumes the interrupted file from its 断点续传 breakpoint rather than
         * restarting it.
         */
        fun stopAuto(context: Context) {
            isManualRun = false
            stop(context)
        }

        /**
         * Posts a standalone (non-foreground) reminder that automatic backup was
         * just turned off while [count] file(s) were still uploading, so those
         * files are now preserved as manually-continuable Paused_Task(s) on the
         * 备份任务 Tab (R-30.2).
         *
         * This is deliberately independent of the foreground-progress notification:
         * [stopAuto] removes the foreground service (and its ongoing notification),
         * so a separate channel/ID is used here to keep the reminder visible. The
         * text is worded distinctly from the USER pause ("已暂停（手动）") and the
         * CONDITION pause ("等待条件恢复") notifications and explicitly names
         * "自动备份已关闭" as the cause. No-op when [count] <= 0 (R-25.5: nothing was
         * left in flight, so there is nothing to remind about).
         *
         * @param count number of In_Flight_File(s) marked AUTO_OFF (the reminder's N).
         */
        fun postAutoOffPausedNotification(context: Context, count: Int) {
            if (count <= 0) return

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    AUTO_OFF_CHANNEL_ID,
                    AUTO_OFF_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "提示因关闭自动备份而暂停的备份任务"
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, AUTO_OFF_CHANNEL_ID)
                .setContentTitle("备份已暂停 · 自动备份已关闭")
                .setContentText("有 $count 个未完成的备份任务，可在“备份任务”页点击“继续”手动续传")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "自动备份已关闭，有 $count 个未完成的备份任务已保留为已暂停任务。它们不会随电量/WiFi 恢复自动续传，可在“备份任务”页点击“继续”手动续传。"
                    )
                )
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            notificationManager.notify(AUTO_OFF_NOTIFICATION_ID, notification)
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

    @Inject
    lateinit var settingsPreferences: com.photovault.data.local.SettingsPreferences

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
        com.photovault.util.FileLogger.log(
            "Service",
            "onStartCommand action=${intent?.action} isRunning=$isRunning isPaused=$isPaused"
        )
        when (intent?.action) {
            ACTION_START -> {
                val manualRun = intent.getBooleanExtra(KEY_MANUAL_RUN, false)
                // All automatic scan paths must honour a persisted manual pause.
                // This is a defence-in-depth gate for callers outside
                // BackgroundScanWorker too: a stale/concurrent automatic START
                // must never bypass the foreground restore confirmation.
                if (!manualRun && settingsPreferences.getUserPausedBackup()) {
                    com.photovault.util.FileLogger.log(
                        "Service",
                        "automatic START ignored; persisted user pause is active"
                    )
                    if (!isRunning) stopSelf(startId)
                    return START_NOT_STICKY
                }

                isRunning = true
                isPaused = false
                isUserPaused = false
                pauseReason = null
                // Record the run source so a concurrent "自动备份" toggle-off can
                // decide whether to stop this run (R-3.13). Defaults to automatic.
                isManualRun = manualRun
                startForeground(NOTIFICATION_ID, buildNotification())
                startBackupProcess()
            }
            ACTION_PAUSE -> {
                val reason = intent.getStringExtra(KEY_PAUSE_REASON)
                    ?.let { runCatching { PauseReason.valueOf(it) }.getOrNull() }
                    ?: PauseReason.USER
                isPaused = true
                pauseReason = reason
                isUserPaused = reason == PauseReason.USER
                // Persist a USER pause so it survives a process kill and is not
                // silently auto-resumed by automatic triggers; only an explicit
                // resume trigger (switch on / FAB / periodic-scan resume) clears it.
                if (reason == PauseReason.USER) {
                    settingsPreferences.setUserPausedBackup(true)
                }
                backupJob?.cancel()
                _uploadProgress.value = null
                if (reason == PauseReason.USER) {
                    updateNotification("备份已暂停（手动）", "点击“开始”继续备份")
                } else {
                    updateNotification("备份已暂停", "等待条件恢复...")
                }
            }
            ACTION_RESUME -> {
                // A resume trigger fired (user tapped "开始", auto-backup switch
                // re-enabled, "立即备份" FAB, or the periodic-scan resume/confirm
                // flow): clear the user pause — including the persisted flag — and
                // resume uploading the queue from persisted 断点续传 progress when
                // conditions allow.
                isRunning = true
                isPaused = false
                isUserPaused = false
                pauseReason = null
                settingsPreferences.setUserPausedBackup(false)
                // Any resume (switch on / FAB / background scan / the dialog's own
                // "恢复") clears an outstanding resume prompt so a stale dialog can't
                // linger — e.g. a background scan silently resumed while the app was
                // away, and the prompt would otherwise reappear on return.
                BackupResumePrompt.consume()
                startForeground(NOTIFICATION_ID, buildNotification())
                startBackupProcess()
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
        isUserPaused = false
        pauseReason = null
        isManualRun = false
        currentFileUri = null
        _uploadProgress.value = null
        serviceScope.cancel()
    }

    private fun startBackupProcess() {
        // Guard against launching a second concurrent loop when start() is
        // delivered while a backup is already actively running (e.g. a
        // connectivity broadcast arriving during normal operation).
        if (backupJob?.isActive == true) return

        // Start from a clean progress snapshot; the first upload callback
        // repopulates it. Prevents the UI card from showing a stale file.
        _uploadProgress.value = null

        completedFiles = 0

        backupJob = serviceScope.launch {
            // Rebuild the queue from persistence first, so files that were only
            // queued (not yet started, hence no UploadRecord) survive a process
            // kill and still get uploaded. Idempotent: files already in the
            // in-memory queue are skipped.
            backupQueue.restoreFromPersistence()
            totalFiles = backupQueue.size()
            com.photovault.util.FileLogger.log(
                "Service",
                "backup loop start; queue=$totalFiles isManualRun=$isManualRun"
            )

            while (backupQueue.size() > 0 && !isPaused) {
                // Check conditions before each file
                if (backupConditionChecker.shouldPauseBackup()) {
                    isPaused = true
                    pauseReason = PauseReason.CONDITION
                    updateNotification("备份已暂停", "等待条件恢复...")
                    break
                }

                val fileInfo = backupQueue.dequeue() ?: break
                currentFileName = fileInfo.fileName
                // Mark this file as the single In_Flight_File; cleared on every
                // path that finishes processing it below (R-25.1/25.2).
                currentFileUri = fileInfo.uri

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
                    currentFileUri = null
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
                    currentFileUri = null
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
                        // Record the skip with its reason. A skipped trashed or
                        // purged record is not active on the server, so it must stay
                        // in its existing status bucket instead of increasing 已备份.
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
                            pauseReason = PauseReason.CONDITION
                            android.util.Log.i(
                                "PhotoVaultBackup",
                                "Upload of ${fileInfo.fileName} interrupted by conditions; re-queued for resume"
                            )
                            updateNotification("备份已暂停", "等待条件恢复...")
                            currentFileUri = null
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

                // This file's processing finished (success / duplicate / skip /
                // non-retryable failure); it is no longer in flight.
                currentFileUri = null
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
        // Publish the live progress for the 备份任务 Tab (speed is the smoothed
        // value computed in the upload callback above).
        _uploadProgress.value = BackupProgressSnapshot(
            fileName = progress.fileName,
            uploadedBytes = progress.uploadedBytes,
            totalBytes = progress.totalBytes,
            speedBytesPerSec = transferSpeedBytesPerSec,
            state = progress.state
        )
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
        isUserPaused = false
        pauseReason = null
        isManualRun = false
        currentFileUri = null
        _uploadProgress.value = null
        // A real stop (user "停止", queue drained to completion, folder removal, or
        // stopAuto on switch-off) resolves any outstanding user pause, so clear the
        // persisted flag. NOTE: onDestroy (process kill) deliberately does NOT
        // clear it, so a kill-while-paused stays paused across the restart.
        settingsPreferences.setUserPausedBackup(false)
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
