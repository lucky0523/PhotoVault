package com.photovault.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.photovault.data.local.SettingsPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One-time worker triggered by ConditionBroadcastReceiver when battery
 * or network conditions change.
 *
 * Evaluates current backup conditions and starts/pauses/resumes
 * the backup foreground service accordingly.
 */
@HiltWorker
class ConditionCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val backupConditionChecker: BackupConditionChecker,
    private val backupQueue: BackupQueue,
    private val settingsPreferences: SettingsPreferences
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            evaluateConditions()
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private fun evaluateConditions() {
        val activelyBackingUp =
            BackupForegroundService.isRunning && !BackupForegroundService.isPaused

        if (activelyBackingUp) {
            // Actively uploading — pause if conditions dropped (e.g. WiFi lost).
            if (backupConditionChecker.shouldPauseBackup()) {
                BackupForegroundService.pause(applicationContext)
            }
        } else {
            // Either not running, or running-but-paused (interrupted earlier by a
            // lost network/battery). In both cases (re)start when conditions have
            // recovered and there is queued work. start() clears the paused flag
            // and resumes any interrupted file from its persisted UploadRecord
            // (断点续传). Previously the running-but-paused state was skipped, so a
            // reconnect alone never resumed the backup.
            //
            // Gated by the auto-backup toggle: when it's off, backup is only ever
            // started by the user via the Local tab FAB, so condition-recovery must
            // not auto-(re)start it. (R-AUTO-BACKUP)
            if (settingsPreferences.getAutoBackupEnabled() &&
                backupQueue.size() > 0 &&
                backupConditionChecker.shouldResumeBackup()
            ) {
                BackupForegroundService.start(applicationContext)
            }
        }
    }
}
