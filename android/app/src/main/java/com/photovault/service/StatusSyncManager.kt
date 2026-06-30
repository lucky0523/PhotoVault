package com.photovault.service

import android.util.Log
import com.photovault.data.api.FileApi
import com.photovault.data.local.dao.PhotoStatusDao
import com.photovault.data.local.entity.PhotoStatus
import com.photovault.data.local.entity.PhotoStatusValue
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Synchronizes the local [PhotoStatus] table with the server's view of which
 * files are trashed or purged.
 *
 * The server's `GET /files/status-sync` returns every non-active file record
 * (trashed + purged) for the current user. This manager matches those records
 * to local entries by `file_hash` and updates the local status accordingly.
 *
 * Triggered at app startup and before each backup, so the scan worker can skip
 * trashed/purged files without needing to hash them first.
 */
@Singleton
class StatusSyncManager @Inject constructor(
    private val fileApi: FileApi,
    private val photoStatusDao: PhotoStatusDao
) {

    companion object {
        private const val TAG = "PhotoVaultStatusSync"
    }

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
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
            if (items.isEmpty()) {
                Log.i(TAG, "status-sync: no trashed/purged items")
                return 0
            }

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
            Log.i(TAG, "status-sync: applied ${items.size} server items, updated $updated local records")
            updated
        } catch (e: Exception) {
            Log.e(TAG, "status-sync failed: ${e.message}", e)
            -1
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
