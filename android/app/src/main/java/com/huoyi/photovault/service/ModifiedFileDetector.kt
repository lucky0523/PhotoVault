package com.huoyi.photovault.service

/**
 * How an already-backed-up (ACTIVE) file's recorded size compares with the
 * MediaStore SIZE seen by the current scan.
 *
 * - [UNKNOWN]: MediaStore SIZE is missing/untrusted (<= 0) — don't compare.
 * - [BACKFILL]: no size recorded yet (row from before v11) — record it, no re-upload.
 * - [UNCHANGED]: sizes match — nothing to do.
 * - [MODIFIED]: sizes differ — the file was changed in place; re-upload it.
 */
enum class SizeCheck { UNKNOWN, BACKFILL, UNCHANGED, MODIFIED }

/**
 * Pure comparison used by the scan to spot ACTIVE files modified in place
 * (e.g. extra data appended to a JPEG by the gallery app).
 *
 * @param storedSize MediaStore SIZE recorded in photo_status at the last backup, or null.
 * @param mediaStoreSize MediaStore SIZE seen by this scan.
 */
fun checkActiveFileSize(storedSize: Long?, mediaStoreSize: Long): SizeCheck = when {
    mediaStoreSize <= 0 -> SizeCheck.UNKNOWN
    storedSize == null -> SizeCheck.BACKFILL
    storedSize == mediaStoreSize -> SizeCheck.UNCHANGED
    else -> SizeCheck.MODIFIED
}
