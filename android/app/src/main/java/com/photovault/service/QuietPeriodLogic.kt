package com.photovault.service

/**
 * Pure (Android-free) logic for the "quiet period" scan guard
 * (skip-incomplete-media-backup, Requirement 1).
 *
 * These functions are intentionally free of any Android dependency so they can be
 * exercised directly from JVM unit / property-based tests without instantiating the
 * [BackgroundScanWorker]. The worker wires them into the scan pipeline in task 5.2.
 */
object QuietPeriodLogic {

    /**
     * Decides whether a media item should be skipped this scan because it was modified
     * so recently that the camera may still be writing / finalizing it. (R1.1, R1.5)
     *
     * Both [now] and [dateModified] are epoch milliseconds.
     *
     * - When [dateModified] is `0L` (missing / unavailable timestamp) this returns `false`
     *   so the file is never permanently skipped due to a missing timestamp (R1.5).
     * - Otherwise the file is skipped (returns `true`) only while it is "too recent", i.e.
     *   `(now - dateModified) < QUIET_PERIOD_MS` (R1.1). At exactly the quiet-period
     *   boundary and beyond (`>= QUIET_PERIOD_MS`) the file is considered stable and is
     *   NOT skipped (returns `false`).
     *
     * @param now the current time in epoch milliseconds
     * @param dateModified the media item's DATE_MODIFIED in epoch milliseconds (0 if unknown)
     * @return true if the file should be skipped this scan, false otherwise
     */
    fun shouldSkipForQuietPeriod(now: Long, dateModified: Long): Boolean {
        if (dateModified == 0L) return false
        return (now - dateModified) < BackupTuning.QUIET_PERIOD_MS
    }

    /**
     * Computes the folder's next `lastScanTime` so that files skipped this scan (because
     * they were within the quiet period) are guaranteed to be re-discovered by the next
     * incremental scan. (R1.2)
     *
     * - When no files were skipped ([skippedModifiedTimes] is empty) this returns
     *   [currentTime] unchanged, preserving the normal incremental-scan behavior.
     * - Otherwise it returns `min(currentTime, skippedModifiedTimes.min())`, ensuring the
     *   folder's `lastScanTime` never advances past the earliest skipped file's
     *   DATE_MODIFIED. Because the next scan enqueues items whose DATE_MODIFIED is strictly
     *   greater than `lastScanTime`, keeping `lastScanTime` at or below that earliest value
     *   guarantees the skipped files remain visible next time.
     *
     * Note: skipped items have `dateModified != 0` by construction (a `0` DATE_MODIFIED is
     * never skipped, see [shouldSkipForQuietPeriod]), so the returned value is not pinned to 0.
     *
     * @param currentTime the scan's current time in epoch milliseconds
     * @param skippedModifiedTimes the DATE_MODIFIED (epoch ms) of every file skipped this scan
     * @return the `lastScanTime` to persist for the folder
     */
    fun computeNextScanTime(currentTime: Long, skippedModifiedTimes: List<Long>): Long {
        val earliestSkipped = skippedModifiedTimes.minOrNull() ?: return currentTime
        return minOf(currentTime, earliestSkipped)
    }
}
