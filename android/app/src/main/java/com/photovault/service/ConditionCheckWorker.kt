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
            // This is a condition-driven pause, so tag it CONDITION: only such
            // pauses may later be auto-resumed here (R-24.5).
            if (backupConditionChecker.shouldPauseBackup()) {
                BackupForegroundService.pause(applicationContext, PauseReason.CONDITION)
            }
        } else {
            // Either not running, or running-but-paused (interrupted earlier by a
            // lost network/battery). In both cases (re)start when conditions have
            // recovered and there is queued work. start() clears the paused flag
            // and resumes any interrupted file from its persisted UploadRecord
            // (断点续传). Previously the running-but-paused state was skipped, so a
            // reconnect alone never resumed the backup.
            if (shouldAutoResume(
                    autoBackupEnabled = settingsPreferences.getAutoBackupEnabled(),
                    queueSize = backupQueue.size(),
                    conditionsRecovered = backupConditionChecker.shouldResumeBackup(),
                    // Consult BOTH the in-memory flag and the persisted one: after a
                    // process kill the in-memory flag is lost, but a user pause must
                    // still block condition-recovery auto-resume (only the explicit
                    // resume triggers may clear it), R-24.5.
                    isUserPaused = BackupForegroundService.isUserPaused ||
                        settingsPreferences.getUserPausedBackup()
                )
            ) {
                // Condition recovery is an automatic trigger (R-3.14 classifies it
                // as such): mark the run non-manual so turning off "自动备份" stops it.
                BackupForegroundService.start(applicationContext, manual = false)
            }
        }
    }

    companion object {
        /**
         * Decides whether the condition-recovery path may automatically (re)start
         * the backup service. Pure function so the gating rules can be unit-tested
         * without a running service/Worker.
         *
         * A user pause takes priority over condition recovery: when
         * [isUserPaused] is true the backup stays paused until the user explicitly
         * taps "开始" (ACTION_RESUME); condition recovery must not override it
         * (R-24.5). Only condition-driven pauses (and the not-running case) are
         * auto-resumed here.
         *
         * The auto-backup toggle still gates everything: when it's off, backup is
         * only ever started by the user, so condition recovery must not auto-start
         * it (R-AUTO-BACKUP).
         */
        fun shouldAutoResume(
            autoBackupEnabled: Boolean,
            queueSize: Int,
            conditionsRecovered: Boolean,
            isUserPaused: Boolean
        ): Boolean {
            if (isUserPaused) return false
            return autoBackupEnabled && queueSize > 0 && conditionsRecovered
        }
    }
}
