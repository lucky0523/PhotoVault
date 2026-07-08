package com.photovault.service

/**
 * Centralized tuning constants for the backup safety guards (skip-incomplete-media-backup).
 *
 * All thresholds are defined here as named constants so they are easy to adjust and so no
 * magic numbers are scattered across the scan / upload code paths. (R4.1)
 */
object BackupTuning {
    /** R1 quiet period: skip media whose DATE_MODIFIED is within this window (default 120s). */
    const val QUIET_PERIOD_MS = 120_000L

    /** R3.2/R4.2 minimum absolute trailing-zero run (bytes) that flags padding (64KB). */
    const val TRAILING_ZERO_MIN_ABS = 64 * 1024

    /** R3.2/R4.2 minimum trailing-zero run as a ratio of snapshot size that flags padding. */
    const val TRAILING_ZERO_MIN_RATIO = 0.10
}
