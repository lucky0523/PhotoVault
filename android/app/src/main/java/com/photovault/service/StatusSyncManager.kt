package com.photovault.service

import android.util.Log
import com.photovault.data.api.BackupApi
import com.photovault.data.api.FileApi
import com.photovault.data.api.model.DuplicateCheckRequest
import com.photovault.data.api.model.StatusSyncItem
import com.photovault.data.local.dao.PhotoStatusDao
import com.photovault.data.local.entity.PhotoStatus
import com.photovault.data.local.entity.PhotoStatusValue
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Synchronizes the local [PhotoStatus] table with the server's view of which
 * files are trashed or purged.
 *
 * The server's `GET /files/status-sync` returns every non-active file record
 * (trashed + purged) for the current user. This manager matches those records
 * to local entries by `file_hash` and updates the local status accordingly.
 *
 * Reconciliation handling: a restored file drops out of the non-active snapshot,
 * as does a purged tombstone physically cleared by an administrator. After applying
 * the snapshot, [syncStatus] verifies every omitted local non-active hash through
 * `POST /backup/check`: explicit `active` reactivates local rows, while explicit
 * `not_found` removes only local `purged` rows. Transient or unexpected responses
 * never change local state, and missing `trashed` rows remain conservative.
 *
 * Triggered at app startup and before each backup, so the scan worker can skip
 * trashed/purged files without needing to hash them first.
 */
@Singleton
class StatusSyncManager @Inject constructor(
    private val fileApi: FileApi,
    private val backupApi: BackupApi,
    private val photoStatusDao: PhotoStatusDao,
    // Optional default preserves lightweight direct construction in existing JVM tests;
    // Hilt supplies the refresher in the app process.
    private val folderStatusCountsRefresher: FolderStatusCountsRefresher? = null
) {

    companion object {
        private const val TAG = "PhotoVaultStatusSync"

        /** Default throttle window for [syncStatusIfStale] (foreground/tab-entry triggers). */
        const val DEFAULT_MIN_SYNC_INTERVAL_MS = 60_000L

        /** [syncStatusIfStale] return value when the call was skipped by the throttle. */
        const val RESULT_SKIPPED_THROTTLED = -2
    }

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** Serializes throttled syncs and guards the stale-check + timestamp read/write. */
    private val staleSyncMutex = Mutex()

    /** Wall-clock time of the last successful [syncStatus], for throttling. */
    @Volatile
    private var lastSuccessfulSyncAtMs: Long = 0L

    /**
     * Throttled variant of [syncStatus] for UI-driven triggers (returning to the
     * foreground / entering the Local or Cloud tab).
     *
     * Skips the sync entirely if a successful one ran within [minIntervalMs], so
     * rapid tab switches or foregrounding don't repeat the full-snapshot fetch and
     * per-record DB work. Serialized by a mutex so concurrent triggers can't launch
     * duplicate in-flight syncs.
     *
     * @return the [syncStatus] result, or [RESULT_SKIPPED_THROTTLED] if throttled.
     */
    suspend fun syncStatusIfStale(minIntervalMs: Long = DEFAULT_MIN_SYNC_INTERVAL_MS): Int {
        return staleSyncMutex.withLock {
            val sinceLast = System.currentTimeMillis() - lastSuccessfulSyncAtMs
            if (sinceLast < minIntervalMs) {
                Log.i(TAG, "status-sync skipped (throttled, ${sinceLast}ms since last)")
                RESULT_SKIPPED_THROTTLED
            } else {
                syncStatus()
            }
        }
    }

    /**
     * Fetches status changes from the server and applies them to the local
     * photo_status table.
     *
     * @return the number of local records updated, or -1 on failure
     */
    suspend fun syncStatus(): Int {
        return try {
            val response = fileApi.getStatusSync()
            if (!response.isSuccessful) {
                Log.w(TAG, "status-sync HTTP ${response.code()}")
                return -1
            }

            val items = response.body()?.items.orEmpty()

            var updated = 0
            for (item in items) {
                val existing = photoStatusDao.getByFileHash(item.fileHash)
                if (existing.isEmpty()) continue

                val deletedMs = parseTimestamp(item.deletedAt ?: item.purgedAt)
                val expiresMs = parseTimestamp(item.expiresAt)
                for (record in existing) {
                    if (record.status == item.status && record.deletedAt == deletedMs) continue
                    photoStatusDao.upsert(
                        record.copy(
                            status = item.status,
                            deletedAt = deletedMs,
                            expiresAt = expiresMs,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    updated++
                }
            }

            // Reconcile local records omitted from the full non-active snapshot.
            // They may have been restored, or a purged tombstone may have been
            // physically cleared by an administrator.
            val reconciled = reconcileMissingServerRecords(items)

            // backup_folders stores denormalized per-folder status counts. Keep
            // that cache aligned even when this sync did not change any rows: an
            // older interrupted sync may already have left a stale aggregate.
            // A refresh failure must not discard an otherwise successful status
            // sync; it will be retried on the next successful sync.
            try {
                folderStatusCountsRefresher?.refresh()
            } catch (e: Exception) {
                Log.w(TAG, "folder status-count refresh failed: ${e.message}", e)
            }

            // Mark success for throttling only after the whole sync completed, so a
            // failure (which returns early above) lets the next trigger retry sooner.
            lastSuccessfulSyncAtMs = System.currentTimeMillis()

            Log.i(
                TAG,
                "status-sync: applied ${items.size} server items, " +
                    "updated $updated local records, reconciled $reconciled omitted records"
            )
            updated + reconciled
        } catch (e: Exception) {
            Log.e(TAG, "status-sync failed: ${e.message}", e)
            -1
        }
    }

    /**
     * Reconciles local non-active records omitted from the server's complete
     * non-active snapshot.
     *
     * An explicit `active` duplicate-check response means that a file was restored,
     * so every local record with that hash becomes active. An explicit `not_found`
     * response means the server has no remaining record for the hash. Only local
     * `purged` rows are removed in that case: Web administrators can deliberately
     * clear purged tombstones, while a missing trashed row remains conservative and
     * is left untouched. Failed or unexpected duplicate checks also leave local
     * state unchanged.
     *
     * @return number of local records reactivated or removed
     */
    private suspend fun reconcileMissingServerRecords(serverItems: List<StatusSyncItem>): Int {
        val serverNonActiveHashes = serverItems.mapNotNull { it.fileHash.ifBlank { null } }.toSet()

        // Candidate hashes: present locally as trashed/purged, absent from the
        // server's non-active payload. Grouped by hash to check each hash once.
        val candidatesByHash = photoStatusDao.getNonActive()
            .filter { !it.fileHash.isNullOrBlank() && it.fileHash !in serverNonActiveHashes }
            .groupBy { it.fileHash!! }

        if (candidatesByHash.isEmpty()) return 0
        Log.i(TAG, "status-sync: ${candidatesByHash.size} omitted hash(es) to verify")

        var reconciled = 0
        for ((hash, records) in candidatesByHash) {
            // Reuse any local URI as the (server-unused) filePath for the check.
            when (checkServerStatus(hash, records.first().fileUri)) {
                PhotoStatusValue.ACTIVE -> {
                    for (record in records) {
                        photoStatusDao.markActive(record.fileUri)
                        reconciled++
                    }
                }
                "not_found" -> {
                    for (record in records) {
                        if (record.status == PhotoStatusValue.PURGED) {
                            photoStatusDao.delete(record.fileUri)
                            reconciled++
                        }
                    }
                }
                else -> Unit // still deleted, request failure, or unknown response
            }
        }
        return reconciled
    }

    /**
     * Reads a single file hash's authoritative server status via `POST /backup/check`.
     *
     * @return `active`, `trashed`, `purged`, or `not_found` for an explicit server
     *         response; `null` for failed or unexpected responses. Callers must
     *         treat `null` as "do not change local state".
     */
    private suspend fun checkServerStatus(fileHash: String, filePath: String): String? {
        return try {
            val response = backupApi.checkDuplicate(
                DuplicateCheckRequest(
                    fileHash = fileHash,
                    filePath = filePath,
                    // deviceName is unused by the hash-based check; fall back to a
                    // constant so a null Build.MODEL can't break the request.
                    deviceName = android.os.Build.MODEL ?: "android"
                )
            )
            if (!response.isSuccessful) return null
            val body = response.body() ?: return null
            val status = body.status ?: return null
            when {
                body.isDuplicate && status == PhotoStatusValue.ACTIVE -> status
                status == PhotoStatusValue.TRASHED ||
                    status == PhotoStatusValue.PURGED ||
                    status == "not_found" -> status
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "status check failed for hash=$fileHash: ${e.message}")
            null
        }
    }

    /**
     * Marks a local file as active after a successful upload or manual re-upload.
     * If no PhotoStatus row exists yet, one is created.
     */
    suspend fun markActive(fileUri: String, fileHash: String) {
        val existing = photoStatusDao.getByFileUri(fileUri)
        if (existing != null) {
            photoStatusDao.markActive(fileUri)
        } else {
            photoStatusDao.upsert(
                PhotoStatus(
                    fileUri = fileUri,
                    fileHash = fileHash,
                    status = PhotoStatusValue.ACTIVE
                )
            )
        }
    }

    /**
     * Marks a local file as trashed after /backup/check reports status=trashed.
     * This happens when the file was deleted via the web UI but the client
     * hadn't synced yet — we learn about it during the backup attempt.
     */
    suspend fun markTrashed(fileUri: String, fileHash: String, expiresAt: String? = null) {
        val expiresMs = parseTimestamp(expiresAt)
        val existing = photoStatusDao.getByFileUri(fileUri)
        val now = System.currentTimeMillis()
        if (existing != null) {
            photoStatusDao.upsert(
                existing.copy(
                    fileHash = fileHash,
                    status = PhotoStatusValue.TRASHED,
                    deletedAt = existing.deletedAt ?: now,
                    expiresAt = expiresMs,
                    updatedAt = now
                )
            )
        } else {
            photoStatusDao.upsert(
                PhotoStatus(
                    fileUri = fileUri,
                    fileHash = fileHash,
                    status = PhotoStatusValue.TRASHED,
                    deletedAt = now,
                    expiresAt = expiresMs
                )
            )
        }
    }

    /**
     * Marks a local file as purged after /backup/check reports status=purged.
     */
    suspend fun markPurged(fileUri: String, fileHash: String) {
        val existing = photoStatusDao.getByFileUri(fileUri)
        val now = System.currentTimeMillis()
        if (existing != null) {
            photoStatusDao.upsert(
                existing.copy(
                    fileHash = fileHash,
                    status = PhotoStatusValue.PURGED,
                    updatedAt = now
                )
            )
        } else {
            photoStatusDao.upsert(
                PhotoStatus(
                    fileUri = fileUri,
                    fileHash = fileHash,
                    status = PhotoStatusValue.PURGED,
                    deletedAt = now
                )
            )
        }
    }

    private fun parseTimestamp(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            isoFormat.parse(value)?.time
        } catch (e: Exception) {
            null
        }
    }
}
