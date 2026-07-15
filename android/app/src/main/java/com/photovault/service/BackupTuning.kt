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

    /**
     * R3.3 JPEG EOI search window (bytes) scanned backward from EOF when the file does not end
     * exactly on the `FF D9` marker. Modern phone cameras (e.g. OnePlus/OPPO Ultra HDR, MPF
     * multi-picture, or trailing XMP/MakerNote) append data after the primary image's EOI, so a
     * complete JPEG may not end on `FF D9`. The last real `FF D9` (the final embedded image's
     * EOI) is followed only by a small index/metadata trailer, so a 1MB tail window comfortably
     * covers it while still bounding how much of a large file is read (default 1MB).
     */
    const val JPEG_EOI_SEARCH_WINDOW = 1024 * 1024
}
