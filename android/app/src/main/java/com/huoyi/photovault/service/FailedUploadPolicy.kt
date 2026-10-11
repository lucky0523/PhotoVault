package com.huoyi.photovault.service

/** What BackupForegroundService does with an `UploadResult.Failed` (design §5.3). */
enum class FailedUploadAction {
    /** Backup conditions no longer hold: re-queue and pause (existing behavior). */
    PAUSE_CONDITION,

    /** The task was locked LAN→PUBLIC during this file: re-queue once and retry on public. */
    REQUEUE_AFTER_SWITCH,

    /** Server unreachable: re-queue and pause (existing behavior). */
    PAUSE_UNREACHABLE,

    /** Record the file as FAILED (existing behavior). */
    RECORD_FAILED
}

/**
 * Decision order for a failed upload, extracted for JVM tests.
 *
 * [lockChangedDuringFile] is read before and again after [isServerReachable]: the
 * reachability check runs on the same task session and may itself lock the task
 * from LAN to PUBLIC (R2#1). [shouldPause] stays lazy to keep the existing
 * short-circuit (not called when `shouldRetry == false`).
 */
object FailedUploadPolicy {

    suspend fun decide(
        shouldRetry: Boolean,
        shouldPause: () -> Boolean,
        lockChangedDuringFile: () -> Boolean,
        alreadyRetriedAfterSwitch: Boolean,
        isServerReachable: suspend () -> Boolean
    ): FailedUploadAction {
        if (shouldRetry && shouldPause()) return FailedUploadAction.PAUSE_CONDITION
        if (!shouldRetry) return FailedUploadAction.RECORD_FAILED
        // Already switched: retry this file on public without a reachability check.
        if (lockChangedDuringFile() && !alreadyRetriedAfterSwitch) return FailedUploadAction.REQUEUE_AFTER_SWITCH
        val reachable = isServerReachable()
        if (lockChangedDuringFile() && !alreadyRetriedAfterSwitch) return FailedUploadAction.REQUEUE_AFTER_SWITCH
        return if (reachable) FailedUploadAction.RECORD_FAILED else FailedUploadAction.PAUSE_UNREACHABLE
    }
}
