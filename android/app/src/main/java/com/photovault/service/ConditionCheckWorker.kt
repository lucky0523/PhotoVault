package com.photovault.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
    private val backupQueue: BackupQueue
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
        val serviceRunning = BackupForegroundService.isRunning

        if (serviceRunning) {
            // Service is running — check if we need to pause
            if (backupConditionChecker.shouldPauseBackup()) {
                BackupForegroundService.pause(applicationContext)
            }
        } else {
            // Service is not running — check if we can start or resume
            if (backupQueue.size() > 0 && backupConditionChecker.shouldResumeBackup()) {
                BackupForegroundService.start(applicationContext)
            }
        }
    }
}
