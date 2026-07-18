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
import com.photovault.data.local.SettingsPreferences
import com.photovault.data.local.dao.BackupFolderDao
import com.photovault.data.local.dao.BackupHistoryDao
import com.photovault.data.local.dao.PhotoStatusDao
import com.photovault.data.local.dao.UploadRecordDao
import com.photovault.data.local.entity.BackupStatus
import com.photovault.data.local.entity.PhotoStatusValue
import com.photovault.util.AppForegroundState
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
    private val uploadRecordDao: UploadRecordDao,
    private val backupHistoryDao: BackupHistoryDao,
    private val settingsPreferences: SettingsPreferences
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "background_scan_worker"
        const val DEFAULT_INTERVAL_MINUTES = 15L

        /**
         * What an automatic scan should do about a backup the user manually paused.
         * - [RESUME]: resume it silently (app in background).
         * - [PROMPT]: ask the user to confirm before resuming (app in foreground).
         * - [NONE]: no outstanding user pause — take the normal start path.
         */
        enum class PausedScanAction { RESUME, PROMPT, NONE }

        /**
         * Decides how an automatic scan handles a user-paused backup. Pure so the
         * gating can be unit-tested without a running worker/service (mirrors
         * [BackupForegroundService.shouldStopAutoOnDisable] / [ConditionCheckWorker.shouldAutoResume]).
         *
         * Returns [PausedScanAction.NONE] (defer to the normal start path) unless
         * this is an **automatic** run ([isManualRun] == false) that [allowBackup]
         * permits AND a user pause is outstanding ([userPaused]). Manual runs (the
         * "立即备份" FAB) resume eagerly through their own path and never prompt.
         * For an automatic run with an outstanding user pause, resume silently in
         * the background or ask for confirmation in the foreground so an auto
         * resume never surprises the user mid-use.
         */
        fun decidePausedResumeAction(
            allowBackup: Boolean,
            isManualRun: Boolean,
            userPaused: Boolean,
            isForeground: Boolean
        ): PausedScanAction {
            if (!allowBackup || isManualRun || !userPaused) return PausedScanAction.NONE
            return if (isForeground) PausedScanAction.PROMPT else PausedScanAction.RESUME
        }

        /**
         * Lightweight retry backoff for re-enqueuing a not-yet-backed-up file that
         * has already FAILED before. The periodic scan enqueues every file with no
         * backup status (so previously-missed/failed files self-heal), but a file
         * that keeps failing must not be retried on every scan — this returns how
         * long to wait, from its last failure, before it's eligible again.
         *
         * Grows exponentially from 30 minutes (1st failure) and is capped at 24h:
         * 30m, 1h, 2h, 4h, 8h, 16h, 24h, 24h, … Pure function so it is unit-testable.
         *
         * @param failureCount number of prior FAILED attempts for the file.
         * @return backoff window in ms; 0 when there were no prior failures.
         */
        fun retryBackoffMs(failureCount: Int): Long {
            if (failureCount <= 0) return 0L
            val base = 30L * 60 * 1000        // 30 minutes
            val cap = 24L * 60 * 60 * 1000    // 24 hours
            val shift = (failureCount - 1).coerceIn(0, 20)
            return (base shl shift).coerceIn(base, cap)
        }

        /**
         * Input-data key. When true, the worker ignores each folder's stored
         * lastScanTime and scans ALL images so they are re-enqueued for backup.
         * The server dedups by hash, so already-backed-up files are skipped cheaply.
         */
        const val KEY_FORCE_FULL_SCAN = "force_full_scan"

        /**
         * Input-data key. When true, this run was explicitly requested by the
         * user (the Local tab "立即备份" FAB) and therefore backs up even when
         * automatic backup is disabled in settings. Automatic triggers leave
         * this false so they honor the auto-backup toggle.
         */
        const val KEY_MANUAL_BACKUP = "manual_backup"

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
         *
         * Pass [manual] = true for user-initiated backups (the "立即备份" FAB) so
         * they proceed even when automatic backup is disabled in settings. The
         * one-time media back-fill leaves it false so it honors the toggle.
         */
        fun runNow(context: Context, manual: Boolean = false) {
            val workRequest = androidx.work.OneTimeWorkRequestBuilder<BackgroundScanWorker>()
                .setExpedited(
                    androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
                )
                .setInputData(
                    androidx.work.Data.Builder()
                        .putBoolean(KEY_FORCE_FULL_SCAN, true)
                        .putBoolean(KEY_MANUAL_BACKUP, manual)
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

        // ---- Debug/test: ~10s scan cadence (SettingsPreferences.SCAN_INTERVAL_TEST_10S) ----

        const val TEST_SCAN_WORK_NAME = "background_scan_worker_test"

        /** Input flag marking a run scheduled by the debug 10-second test chain. */
        const val KEY_TEST_INTERVAL_RUN = "test_interval_run"

        private const val TEST_INTERVAL_SECONDS = 10L

        /**
         * Applies a chosen scan interval, switching between the normal periodic
         * worker (real minute intervals) and the debug ~10-second self-rescheduling
         * chain ([SettingsPreferences.SCAN_INTERVAL_TEST_10S]). Exactly one of the
         * two mechanisms is active at a time.
         */
        fun applyScanInterval(context: Context, intervalValue: Int) {
            if (intervalValue == com.photovault.data.local.SettingsPreferences.SCAN_INTERVAL_TEST_10S) {
                // Stop the 15-min periodic worker and start the fast test chain.
                cancel(context)
                enqueueTestScan(context)
            } else {
                // Stop the test chain and (re)schedule the normal periodic worker.
                WorkManager.getInstance(context).cancelUniqueWork(TEST_SCAN_WORK_NAME)
                reschedule(context, intervalValue.toLong())
            }
        }

        /**
         * Enqueues the next debug test scan ~[TEST_INTERVAL_SECONDS] from now.
         *
         * WorkManager periodic work cannot run faster than every 15 minutes, so
         * the sub-minute test cadence is built from delayed one-time work that
         * re-enqueues itself at the end of each run (see [doWork]) as long as the
         * test interval is still selected. Debug-only.
         */
        fun enqueueTestScan(context: Context) {
            val workRequest = androidx.work.OneTimeWorkRequestBuilder<BackgroundScanWorker>()
                .setInitialDelay(TEST_INTERVAL_SECONDS, TimeUnit.SECONDS)
                .setInputData(
                    androidx.work.Data.Builder()
                        .putBoolean(KEY_TEST_INTERVAL_RUN, true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                TEST_SCAN_WORK_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }

        // ---- Background silent resume of a user-paused backup ----

        const val RESUME_WORK_NAME = "background_scan_worker_resume"

        /** Input flag: this run's sole job is to resume a user-paused backup. */
        const val KEY_RESUME_PAUSED = "resume_paused_backup"

        /**
         * Resumes a user-paused backup from an EXPEDITED worker so the
         * [BackupForegroundService] can be (re)started even from the background.
         *
         * A plain (non-expedited) periodic/scan worker cannot call
         * `startForegroundService` from the background on API 31+
         * (`ForegroundServiceStartNotAllowedException`), so the background-resume
         * branch of [decidePausedResumeAction] delegates here instead of resuming
         * inline. This mirrors the FGS-capable expedited path already used by
         * [runOnce] / [runNow], keeping a silent background resume exactly as
         * reliable as any other background-triggered backup (enabling 无感备份).
         */
        fun resumePausedBackup(context: Context) {
            val workRequest = androidx.work.OneTimeWorkRequestBuilder<BackgroundScanWorker>()
                .setExpedited(
                    androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
                )
                .setInputData(
                    androidx.work.Data.Builder()
                        .putBoolean(KEY_RESUME_PAUSED, true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                RESUME_WORK_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }
    }

    override suspend fun doWork(): Result {
        val isTestRun = inputData.getBoolean(KEY_TEST_INTERVAL_RUN, false)
        com.photovault.util.FileLogger.log(
            "Scan",
            "doWork ENTER manual=${inputData.getBoolean(KEY_MANUAL_BACKUP, false)} " +
                "test=$isTestRun resume=${inputData.getBoolean(KEY_RESUME_PAUSED, false)} " +
                "fg=${AppForegroundState.isForeground}"
        )

        // Dedicated expedited run (enqueued by resumePausedBackup) whose only job
        // is to resume a user-paused backup from a foreground-service-capable
        // context. It runs before — and instead of — the normal scan body so the
        // FGS start is legal from the background (API 31+).
        if (inputData.getBoolean(KEY_RESUME_PAUSED, false)) {
            return try {
                // Promote THIS worker to a foreground service first. Starting the
                // BackupForegroundService then counts as a foreground-service ->
                // foreground-service start (always allowed), instead of a
                // restricted *background* foreground-service start (API 31+). This
                // is what lets a silent resume work even after the paused service
                // was reclaimed / the process was killed. Best-effort: if the
                // platform refuses setForeground, we still attempt the resume —
                // which succeeds outright when the paused service is still alive.
                try {
                    setForeground(getForegroundInfo())
                } catch (e: Exception) {
                    android.util.Log.w(
                        "PhotoVaultScan",
                        "setForeground for resume run failed (continuing): ${e.message}"
                    )
                }
                BackupForegroundService.resume(applicationContext)
                com.photovault.util.FileLogger.log("Scan", "resume run: resume() dispatched")
                Result.success()
            } catch (e: Exception) {
                com.photovault.util.FileLogger.log(
                    "Scan",
                    "resume run FAILED ${e.javaClass.simpleName}: ${e.message}"
                )
                android.util.Log.e(
                    "PhotoVaultScan",
                    "Resume-paused backup run failed: ${e.message}",
                    e
                )
                Result.retry()
            }
        }

        val result = try {
            val forceFullScan = inputData.getBoolean(KEY_FORCE_FULL_SCAN, false)
            val manual = inputData.getBoolean(KEY_MANUAL_BACKUP, false)
            // Backup (enqueue + start) only proceeds for an explicit manual run,
            // or when automatic backup is enabled. When auto-backup is off, an
            // automatic trigger still scans (for status/count refresh) but never
            // uploads on its own. (R-AUTO-BACKUP)
            val allowBackup = manual || settingsPreferences.getAutoBackupEnabled()
            android.util.Log.i(
                "PhotoVaultScan",
                "BackgroundScanWorker started (forceFullScan=$forceFullScan, manual=$manual, allowBackup=$allowBackup)"
            )
            com.photovault.util.FileLogger.log(
                "Scan",
                "scan start forceFull=$forceFullScan manual=$manual allowBackup=$allowBackup"
            )

            // Sync photo status from server before scanning, so trashed/purged
            // files (deleted via web UI) are skipped. Only attempt sync when
            // the user has a valid auth token.
            if (credentialManager.hasValidToken()) {
                val synced = statusSyncManager.syncStatus()
                android.util.Log.i("PhotoVaultScan", "Status sync result: $synced records updated")
            }

            // Rebuild the queue after a process kill. Two persisted sources feed
            // it, and order matters:
            //  1. queued_files — files that were queued but had not started
            //     uploading (no UploadRecord). restoreFromPersistence() reloads
            //     them into the in-memory queue.
            //  2. upload_records — interrupted in-flight uploads, re-queued by
            //     requeueResumableUploads() which skips URIs already in the queue
            //     (so a file present in both is not enqueued twice).
            // Both are gated by allowBackup so a disabled auto-backup doesn't
            // silently resume.
            if (allowBackup) {
                backupQueue.restoreFromPersistence()
                requeueResumableUploads()
            }

            scanAllFolders(forceFullScan, allowBackup, manual)

            // After scanning + requeue, decide what to do about queued work.
            // A user manual-pause changes this: instead of silently (re)starting,
            // an automatic run either resumes it (app in background) or asks the
            // user to confirm (app in foreground). See [decidePausedResumeAction].
            if (allowBackup) {
                val action = decidePausedResumeAction(
                    allowBackup = true,
                    isManualRun = manual,
                    userPaused = settingsPreferences.getUserPausedBackup(),
                    isForeground = AppForegroundState.isForeground
                )
                com.photovault.util.FileLogger.log(
                    "Scan",
                    "decision fg=${AppForegroundState.isForeground} " +
                        "paused=${settingsPreferences.getUserPausedBackup()} " +
                        "queue=${backupQueue.size()} action=$action"
                )
                when (action) {
                    PausedScanAction.RESUME ->
                        // Delegate to an expedited, FGS-capable worker so the
                        // foreground service can be (re)started even from the
                        // background (a plain background worker cannot on API 31+).
                        resumePausedBackup(applicationContext)
                    PausedScanAction.PROMPT ->
                        BackupResumePrompt.request()
                    PausedScanAction.NONE ->
                        maybeStartBackupForQueuedWork(
                            manual = manual,
                            forceFullScan = forceFullScan
                        )
                }
            }

            android.util.Log.i("PhotoVaultScan", "BackgroundScanWorker finished successfully")
            com.photovault.util.FileLogger.log("Scan", "doWork SUCCESS")
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("PhotoVaultScan", "BackgroundScanWorker failed: ${e.message}", e)
            com.photovault.util.FileLogger.log(
                "Scan",
                "doWork FAILED ${e.javaClass.simpleName}: ${e.message}"
            )
            Result.retry()
        }

        // Test interval only: keep the ~10-second scan chain going while it is
        // still the selected interval. Switching to a real interval cancels the
        // unique work, and this guard stops re-enqueuing so the chain terminates.
        if (isTestRun &&
            settingsPreferences.getScanIntervalMinutes() ==
            com.photovault.data.local.SettingsPreferences.SCAN_INTERVAL_TEST_10S
        ) {
            enqueueTestScan(applicationContext)
        }
        return result
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

        // Fallback guard: never resume an upload whose backup folder has been
        // removed. Records are normally deleted when the folder is removed
        // (LocalTabViewModel.removeFolder), but this also covers legacy records
        // and any encoding mismatch, keeping "removed folder stops backing up"
        // true even across a process restart.
        val existingFolderKeys = backupFolderDao.getAllOnce()
            .map { canonicalFolderKey(it.folderUri) }
            .toSet()

        val resumable = records
            // Never auto-resume/re-queue an AUTO_OFF Paused_Task: it was kept
            // deliberately when the user turned off "自动备份" and must only be
            // resumed by the user tapping "继续" on the 备份任务 Tab, not silently
            // rebuilt after a process kill or when auto-backup is re-enabled
            // (R-25.4/30.3/31.2).
            .filter { it.pauseSource != "AUTO_OFF" }
            .filter { now - it.createdAt <= sessionExpireMs }
            .filter { it.fileUri !in queuedUris }
            // Blank folderUri = pre-migration record with no folder attribution;
            // leave its resume behavior unchanged. Only skip records whose folder
            // is known AND no longer registered.
            .filter { it.folderUri.isBlank() || canonicalFolderKey(it.folderUri) in existingFolderKeys }
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
     * conditions allow, but the service isn't already running. This is the sole
     * automatic-start gate: scanning itself must only enqueue work so a persisted
     * user pause can be resolved before any service-start request is dispatched.
     * [start] is idempotent — a redundant call while running is a no-op.
     */
    private fun maybeStartBackupForQueuedWork(
        manual: Boolean,
        forceFullScan: Boolean
    ) {
        val queued = backupQueue.size()
        val running = BackupForegroundService.isRunning
        val normalConditionsMet = backupConditionChecker.shouldStartBackup()
        // Preserve the existing manual full-scan policy: when charging, an
        // explicit full scan may proceed even if the normal battery threshold is
        // not met. Ordinary automatic scans retain the normal condition gate.
        val condOk = if (forceFullScan) {
            backupConditionChecker.isNetworkAvailableForBackup() &&
                (backupConditionChecker.isCharging() || normalConditionsMet)
        } else {
            normalConditionsMet
        }
        com.photovault.util.FileLogger.log(
            "Scan",
            "maybeStart queued=$queued running=$running condOk=$condOk manual=$manual"
        )
        if (queued > 0 && !running && condOk) {
            android.util.Log.i(
                "PhotoVaultScan",
                "Starting backup service for $queued queued file(s)"
            )
            com.photovault.util.FileLogger.log("Scan", "-> start() dispatched (queued=$queued)")
            BackupForegroundService.start(applicationContext, manual = manual)
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
    private suspend fun scanAllFolders(
        forceFullScan: Boolean = false,
        allowBackup: Boolean = true,
        manual: Boolean = false
    ) {
        val folders = backupFolderDao.getAllOnce()
        android.util.Log.i("PhotoVaultScan", "Scanning ${folders.size} folder(s) (forceFullScan=$forceFullScan)")
        val currentTime = System.currentTimeMillis()
        val newFiles = mutableListOf<FileInfo>()

        // Get ALL photo statuses once (for efficient lookup)
        val allStatuses = photoStatusDao.getAll()
        val statusMap = allStatuses.associateBy { it.fileUri }

        // Prior FAILED backups → per-file failure count + latest failure time,
        // used for a lightweight retry backoff so a permanently-failing file is
        // not re-enqueued on every scan. Reused from history (no extra storage);
        // a forceFullScan (user tapped "立即备份") ignores the backoff below.
        val failedHistory = try {
            backupHistoryDao.getByStatusOnce(BackupStatus.FAILED)
        } catch (e: Exception) {
            emptyList()
        }
        val failureCountByUri: Map<String, Int> =
            failedHistory.groupingBy { it.fileUri }.eachCount()
        val lastFailureAtByUri: Map<String, Long> =
            failedHistory.groupBy { it.fileUri }
                .mapValues { (_, rows) -> rows.maxOf { it.completedAt } }

        // Files already queued or currently uploading — never enqueue a duplicate
        // (the periodic scan now re-examines ALL files each run, so without this a
        // queued-but-not-yet-uploaded file would be added again every scan).
        val queuedUris = backupQueue.getAll().mapTo(HashSet()) { it.uri }
        val inFlightUri = BackupForegroundService.currentFileUri

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

            // Enqueue EVERY not-yet-backed-up file, not just files newer than
            // lastScanTime. A file has no local status (`status == null`) until it
            // is successfully backed up, so this makes the "未备份" set self-heal:
            // previously-missed / failed / interrupted files get picked up on a
            // later scan instead of only by a manual "立即备份". `allPhotosInFolder`
            // was already enumerated above for counting, so this adds no scan cost.
            //
            // DATE_MODIFIED (epoch ms) of files skipped because they are within the
            // quiet period (camera may still be writing); used to roll lastScanTime
            // back so they are re-examined next scan (R1.1/R1.2).
            val skippedModifiedTimes = mutableListOf<Long>()
            for (fileInfo in allPhotosInFolder) {
                // Only files that have never been backed up (no status record).
                if (statusMap[fileInfo.uri] != null) continue

                // Skip files modified so recently they may still be being written
                // (R1.1); recorded so lastScanTime rolls back and re-examines them.
                if (QuietPeriodLogic.shouldSkipForQuietPeriod(currentTime, fileInfo.createdTime)) {
                    skippedModifiedTimes.add(fileInfo.createdTime)
                    continue
                }

                // Already queued or currently uploading — don't add a duplicate.
                if (fileInfo.uri in queuedUris || fileInfo.uri == inFlightUri) continue

                // Lightweight retry backoff: a file that has FAILED before waits an
                // (exponentially growing) window from its last failure before being
                // retried, so a permanently-failing file isn't re-enqueued every
                // scan. A manual full scan ("立即备份") bypasses the backoff.
                if (!forceFullScan) {
                    val failCount = failureCountByUri[fileInfo.uri] ?: 0
                    if (failCount > 0) {
                        val lastFailAt = lastFailureAtByUri[fileInfo.uri] ?: 0L
                        if (currentTime - lastFailAt < retryBackoffMs(failCount)) {
                            continue
                        }
                    }
                }

                newFiles.add(fileInfo)
            }

            // Roll back lastScanTime past the earliest skipped file so it isn't missed
            // next scan; preserves currentTime when nothing was skipped (R1.2/R1.3).
            //
            // When backup isn't allowed this run (auto-backup off, non-manual),
            // freeze lastScanTime instead of advancing it. Otherwise files that
            // appeared while auto-backup was off would fall behind lastScanTime and
            // never be picked up by an incremental scan once it's re-enabled; the
            // frozen timestamp lets the next allowed scan still discover them. Counts
            // are always refreshed so the UI stays accurate.
            val nextScanTime = if (allowBackup) {
                QuietPeriodLogic.computeNextScanTime(currentTime, skippedModifiedTimes)
            } else {
                folder.lastScanTime
            }

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

        // When backup isn't allowed this run (auto-backup off, non-manual), stop
        // here: folder counts/status were refreshed above, but new files are not
        // enqueued and the backup service is not started. (R-AUTO-BACKUP)
        if (!allowBackup) {
            android.util.Log.i(
                "PhotoVaultScan",
                "Auto-backup disabled; scanned ${newFiles.size} new file(s) without enqueuing"
            )
            return
        }

        // Enqueue newly discovered files only. Starting the service is deliberately
        // deferred to doWork() until after the persisted user-pause decision has
        // been made; otherwise a foreground scan could show a restore prompt after
        // it had already started uploading.
        if (newFiles.isNotEmpty()) {
            backupQueue.enqueue(newFiles)
            android.util.Log.i(
                "PhotoVaultScan",
                "Enqueued ${newFiles.size} file(s). forceFullScan=$forceFullScan; " +
                    "awaiting post-scan pause/start decision"
            )
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
