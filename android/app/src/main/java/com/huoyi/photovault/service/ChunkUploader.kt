package com.huoyi.photovault.service

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.gson.JsonParseException
import com.huoyi.photovault.R
import com.huoyi.photovault.data.api.BackupApi
import com.huoyi.photovault.data.api.model.CompleteUploadRequest
import com.huoyi.photovault.data.api.model.DuplicateCheckRequest
import com.huoyi.photovault.data.api.model.InitUploadRequest
import com.huoyi.photovault.data.api.model.StoragePolicyConfig
import com.huoyi.photovault.data.api.model.UploadProgress
import com.huoyi.photovault.data.api.model.UploadResult
import com.huoyi.photovault.data.api.model.UploadState
import com.huoyi.photovault.data.local.dao.UploadRecordDao
import com.huoyi.photovault.data.local.entity.PhotoStatusValue
import com.huoyi.photovault.data.local.entity.UploadRecord
import com.huoyi.photovault.util.DeviceNameProvider
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

/**
 * Handles chunked file upload with resume capability.
 *
 * Upload flow:
 * 1. Compute SHA-256 hash of the file
 * 2. Check for duplicates on the server (also reveals trashed/purged status)
 * 3. Check for existing upload record (resume case)
 * 4. If resuming: verify file not modified, check session not expired, query server for received chunks
 * 5. If new: call /backup/init to create session
 * 6. Upload chunks sequentially with MD5 checksum per chunk
 * 7. On each chunk success: update Room record
 * 8. On failure: retry up to 3 times with 30s delay
 * 9. After all chunks: call /backup/complete
 * 10. On complete: delete Room upload record
 * 11. Report progress via callback throughout
 *
 * Recycle bin integration:
 * - If /backup/check returns status=trashed or status=purged, the upload is
 *   skipped (UploadResult.Skipped) and the local photo_status is updated so
 *   future scans skip this file. This handles the case where a file was
 *   deleted via the web UI but the client hasn't synced yet.
 * - On successful upload or active duplicate, the local photo_status is
 *   marked active.
 */
@Singleton
class ChunkUploader @Inject constructor(
    private val backupApi: BackupApi,
    private val uploadRecordDao: UploadRecordDao,
    private val fileHasher: FileHasher,
    private val statusSyncManager: StatusSyncManager,
    private val mediaBytesReader: MediaBytesReader
) {

    companion object {
        const val CHUNK_SIZE = 2 * 1024 * 1024 // 2MB
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 30_000L // 30 seconds
        const val SESSION_EXPIRE_DAYS = 7
        private const val SESSION_EXPIRE_MS = SESSION_EXPIRE_DAYS * 24 * 60 * 60 * 1000L
    }

    /**
     * Uploads a file using chunked upload with resume support.
     *
     * @param context Application context for content resolver access
     * @param fileInfo Information about the file to upload
     * @param storagePolicy Storage policy configuration for this file
     * @param onProgress Callback for reporting upload progress to the UI
     * @return UploadResult indicating success, duplicate skip, or failure
     */
    suspend fun uploadFile(
        context: Context,
        fileInfo: FileInfo,
        storagePolicy: StoragePolicyConfig,
        onProgress: (UploadProgress) -> Unit
    ): UploadResult {
        val fileUri = Uri.parse(fileInfo.uri)

        // Step 1: Duplicate pre-check using a hash of the current bytes.
        // Cheap (no temp copy) so already-backed-up files are skipped without
        // staging; the server dedups by hash.
        onProgress(
            UploadProgress(
                fileName = fileInfo.fileName,
                totalBytes = fileInfo.fileSize,
                uploadedBytes = 0,
                currentChunk = 0,
                totalChunks = 0,
                state = UploadState.HASHING
            )
        )

        val precheckHash: String
        try {
            precheckHash = fileHasher.computeSha256(context, fileUri)
        } catch (e: Exception) {
            // If the source file no longer exists on the device (user deleted it
            // before it was backed up), skip it with a clear reason instead of
            // reporting a generic failure.
            if (!sourceExists(context, fileUri)) {
                return UploadResult.Skipped(
                    context.getString(R.string.backup_skip_source_deleted),
                    countsAsBackedUp = false
                )
            }
            return UploadResult.Failed(
                context.getString(R.string.backup_error_hash),
                shouldRetry = false
            )
        }

        // Step 2: Check for duplicates
        onProgress(
            UploadProgress(
                fileName = fileInfo.fileName,
                totalBytes = fileInfo.fileSize,
                uploadedBytes = 0,
                currentChunk = 0,
                totalChunks = 0,
                state = UploadState.CHECKING_DUPLICATE
            )
        )

        val duplicateResult = checkDuplicate(precheckHash, fileInfo, context)
        if (duplicateResult != null) {
            val skipState = when (duplicateResult) {
                is UploadResult.Duplicate -> UploadState.SKIPPED_DUPLICATE
                is UploadResult.Skipped -> {
                    if (duplicateResult.reason == context.getString(R.string.backup_skip_in_trash)) {
                        UploadState.SKIPPED_TRASHED
                    } else {
                        UploadState.SKIPPED_PURGED
                    }
                }
                else -> UploadState.SKIPPED_DUPLICATE
            }
            onProgress(
                UploadProgress(
                    fileName = fileInfo.fileName,
                    totalBytes = fileInfo.fileSize,
                    uploadedBytes = fileInfo.fileSize,
                    currentChunk = 0,
                    totalChunks = 0,
                    state = skipState
                )
            )
            return duplicateResult
        }

        // Not a known duplicate: stage a stable on-disk snapshot and compute the
        // hash + chunks from it. This guarantees the uploaded bytes match the
        // hash even if the camera is still finalizing a freshly recorded video
        // (reading the live URI twice can otherwise race and trip the server's
        // "File integrity verification failed" check).
        val snapshot: java.io.File = try {
            createSnapshot(context, fileUri, fileInfo.fileName)
        } catch (e: Exception) {
            android.util.Log.e("PhotoVaultBackup", "snapshot failed for ${fileInfo.fileName}: ${e.message}", e)
            return UploadResult.Failed(
                context.getString(R.string.backup_error_stage),
                shouldRetry = true
            )
        }

        try {

        val fileHash: String
        val actualSize: Long
        try {
            val result = fileHasher.computeSha256AndSize(snapshot)
            fileHash = result.hash
            actualSize = result.size
        } catch (e: Exception) {
            return UploadResult.Failed(
                context.getString(R.string.backup_error_hash),
                shouldRetry = false
            )
        }
        android.util.Log.i(
            "PhotoVaultBackup",
            "Prepared ${fileInfo.fileName}: mediaStoreSize=${fileInfo.fileSize}, snapshotSize=$actualSize, precheckHash=$precheckHash, snapshotHash=$fileHash"
        )

        // Guard against uploading a not-fully-written snapshot (camera still
        // finalizing / pre-allocated but unfilled). Compare the hashed snapshot
        // bytes against the trusted MediaStore.SIZE, check for trailing zero
        // padding, and validate format structure. (R3.1/3.2/3.3/5.5)
        val expected = fileInfo.fileSize.takeIf { it > 0 } // MediaStore.SIZE, null if untrusted (R4.3)
        when (val v = SnapshotValidator.validate(snapshot, actualSize, expected, fileInfo.fileName, fileInfo.mimeType)) {
            is SnapshotValidation.Invalid -> {
                android.util.Log.w(
                    "PhotoVaultBackup",
                    "snapshot invalid ${fileInfo.fileName}: ${v.reason} ${v.detail} mediaStoreSize=${fileInfo.fileSize} snapshotSize=$actualSize"
                )
                // The outer finally{} deletes the snapshot (R3.5); the upload
                // record is left intact so the file is retried later (R3.4).
                val errorRes = when (v.reason) {
                    SnapshotValidation.Reason.TRUNCATED_STRUCTURE ->
                        R.string.backup_error_truncated_structure
                    else -> R.string.backup_error_source_not_ready
                }
                return UploadResult.Failed(
                    context.getString(errorRes),
                    shouldRetry = true
                )
            }
            SnapshotValidation.Valid -> { /* proceed with existing flow */ }
        }

        // Authoritative size = snapshot length, which matches the hashed bytes.
        val upload = fileInfo.copy(fileSize = actualSize)

        // Use a local estimate only while init is in flight. The server response
        // is authoritative for both chunk size and total chunk count.
        val estimatedTotalChunks = calculateTotalChunks(upload.fileSize)
        val fileModifiedTime = getFileModifiedTime(context, fileUri)

        // Step 4: Check for resume or initialize new session
        onProgress(
            UploadProgress(
                fileName = upload.fileName,
                totalBytes = upload.fileSize,
                uploadedBytes = 0,
                currentChunk = 0,
                totalChunks = estimatedTotalChunks,
                state = UploadState.INITIALIZING
            )
        )

        val sessionInfo = when (
            val resolution = resolveSession(
                context,
                upload,
                fileHash,
                fileModifiedTime,
                storagePolicy
            )
        ) {
            is SessionResolution.Success -> resolution.sessionInfo
            is SessionResolution.Duplicate -> {
                uploadRecordDao.deleteByFileUri(upload.uri)
                statusSyncManager.markActive(upload.uri, fileHash)
                onProgress(
                    UploadProgress(
                        fileName = upload.fileName,
                        totalBytes = upload.fileSize,
                        uploadedBytes = upload.fileSize,
                        currentChunk = 0,
                        totalChunks = 0,
                        state = UploadState.SKIPPED_DUPLICATE
                    )
                )
                return UploadResult.Duplicate(resolution.fileId)
            }
            is SessionResolution.Failure -> return UploadResult.Failed(
                error = resolution.error,
                shouldRetry = resolution.shouldRetry
            )
        }

        val sessionId = sessionInfo.sessionId
        val startChunkIndex = sessionInfo.startChunkIndex
        val chunkSize = sessionInfo.chunkSize
        val totalChunks = sessionInfo.totalChunks

        // Step 5: Upload chunks sequentially
        for (chunkIndex in startChunkIndex until totalChunks) {
            val uploadedBytes = minOf(upload.fileSize, chunkIndex.toLong() * chunkSize)
            onProgress(
                UploadProgress(
                    fileName = upload.fileName,
                    totalBytes = upload.fileSize,
                    uploadedBytes = uploadedBytes,
                    currentChunk = chunkIndex,
                    totalChunks = totalChunks,
                    state = UploadState.UPLOADING
                )
            )

            val chunkData = readChunkFromFile(
                file = snapshot,
                chunkIndex = chunkIndex,
                fileSize = upload.fileSize,
                chunkSize = chunkSize
            )
                ?: return UploadResult.Failed(
                    context.getString(R.string.backup_error_read_chunk, chunkIndex),
                    shouldRetry = true
                )

            val chunkResult = uploadChunkWithRetry(sessionId, chunkIndex, chunkData)
            if (chunkResult != ChunkUploadOutcome.SUCCESS) {
                val error = if (chunkResult == ChunkUploadOutcome.RETRYABLE_FAILURE) {
                    context.getString(
                        R.string.backup_error_upload_chunk_retries,
                        chunkIndex,
                        MAX_RETRIES
                    )
                } else {
                    context.getString(R.string.backup_error_upload_chunk_rejected, chunkIndex)
                }
                return UploadResult.Failed(
                    error = error,
                    shouldRetry = chunkResult == ChunkUploadOutcome.RETRYABLE_FAILURE
                )
            }

            // Update local progress record
            uploadRecordDao.updateProgress(upload.uri, chunkIndex)
        }

        // Step 6: Complete upload
        onProgress(
            UploadProgress(
                fileName = upload.fileName,
                totalBytes = upload.fileSize,
                uploadedBytes = upload.fileSize,
                currentChunk = totalChunks,
                totalChunks = totalChunks,
                state = UploadState.COMPLETING
            )
        )

        val completeResult = completeUpload(sessionId, fileHash)
        if (completeResult != null) {
            // Clean up local record on success
            uploadRecordDao.deleteByFileUri(upload.uri)
            // Mark file as active in local photo_status (also handles reactivation
            // after manual re-upload of a previously trashed/purged file)
            statusSyncManager.markActive(upload.uri, fileHash)
            onProgress(
                UploadProgress(
                    fileName = upload.fileName,
                    totalBytes = upload.fileSize,
                    uploadedBytes = upload.fileSize,
                    currentChunk = totalChunks,
                    totalChunks = totalChunks,
                    state = UploadState.COMPLETED
                )
            )
            return completeResult
        }

        return UploadResult.Failed(
            context.getString(R.string.backup_error_complete),
            shouldRetry = true
        )

        } finally {
            if (snapshot.exists() && !snapshot.delete()) {
                android.util.Log.w("PhotoVaultBackup", "could not delete snapshot ${snapshot.absolutePath}")
            }
        }
    }

    /**
     * Checks if the file is a duplicate on the server.
     *
     * Returns:
     * - UploadResult.Duplicate if the file is active on the server (skip upload)
     * - UploadResult.Skipped if the file is trashed or purged on the server
     *   (skip upload, update local photo_status so future scans skip it too)
     * - null if the file is not found (proceed with upload) or the check fails
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun checkDuplicate(
        fileHash: String,
        fileInfo: FileInfo,
        context: Context? = null
    ): UploadResult? {
        return try {
            val response = backupApi.checkDuplicate(
                DuplicateCheckRequest(
                    fileHash = fileHash,
                    filePath = fileInfo.uri,
                    // deviceName is unused by the hash-based check, but send the
                    // same value as the upload path for log consistency.
                    deviceName = DeviceNameProvider.deviceName
                )
            )
            if (!response.isSuccessful) return null

            val body = response.body() ?: return null
            val status = body.status ?: "active"

            when {
                body.isDuplicate && status == PhotoStatusValue.ACTIVE -> {
                    // File is already active on the server — record locally and
                    // clear an obsolete interrupted-upload record before skipping.
                    statusSyncManager.markActive(fileInfo.uri, fileHash)
                    uploadRecordDao.deleteByFileUri(fileInfo.uri)
                    UploadResult.Duplicate(body.fileId)
                }
                status == PhotoStatusValue.TRASHED -> {
                    if (fileInfo.forceReupload) {
                        // Manual re-upload: don't short-circuit. Proceed to upload
                        // so /backup/complete reactivates the trashed record.
                        null
                    } else {
                        // A trashed server record is not backed up/active. It is a
                        // terminal outcome for this queued attempt, so don't let a
                        // stale resume record re-queue it on every app launch.
                        statusSyncManager.markTrashed(fileInfo.uri, fileHash, body.expiresAt)
                        uploadRecordDao.deleteByFileUri(fileInfo.uri)
                        UploadResult.Skipped(
                            context?.getString(R.string.backup_skip_in_trash)
                                ?: PhotoStatusValue.TRASHED,
                            countsAsBackedUp = false
                        )
                    }
                }
                status == PhotoStatusValue.PURGED -> {
                    if (fileInfo.forceReupload) {
                        // Manual re-upload: don't short-circuit. Proceed to upload
                        // so /backup/complete reactivates the purged record.
                        null
                    } else {
                        // A purged server record is not active and must remain in
                        // the 已删除 bucket rather than being counted as backed up.
                        // Delete the stale resume record so it is not retried.
                        statusSyncManager.markPurged(fileInfo.uri, fileHash)
                        uploadRecordDao.deleteByFileUri(fileInfo.uri)
                        UploadResult.Skipped(
                            context?.getString(R.string.backup_skip_purged)
                                ?: PhotoStatusValue.PURGED,
                            countsAsBackedUp = false
                        )
                    }
                }
                else -> {
                    // status == "not_found" — proceed with upload
                    null
                }
            }
        } catch (e: Exception) {
            // If duplicate check fails, proceed with upload (per requirement 6.5: 30s timeout → treat as not backed up)
            null
        }
    }

    /**
     * Resolves the upload session — either resumes an existing one or creates a new one.
     */
    private suspend fun resolveSession(
        context: Context,
        fileInfo: FileInfo,
        fileHash: String,
        fileModifiedTime: Long,
        storagePolicy: StoragePolicyConfig
    ): SessionResolution {
        val existingRecord = try {
            uploadRecordDao.getByFileUri(fileInfo.uri)
        } catch (e: Exception) {
            android.util.Log.e("PhotoVaultBackup", "Unable to read local upload session", e)
            return SessionResolution.Failure(
                context.getString(
                    R.string.backup_error_init_local_state,
                    diagnosticMessage(e)
                ),
                shouldRetry = false
            )
        }

        if (existingRecord != null) {
            // Verify session not expired (7 days)
            val elapsed = System.currentTimeMillis() - existingRecord.createdAt
            if (elapsed > SESSION_EXPIRE_MS) {
                uploadRecordDao.deleteByFileUri(fileInfo.uri)
                return initNewSession(context, fileInfo, fileHash, fileModifiedTime, storagePolicy)
            }

            // Records from before schema v10 have chunkSize=0. Records whose
            // historical file_modified_time was polluted with createdTime also
            // differ here; both cases are discarded and initialized once using
            // the real MediaStore DATE_MODIFIED value.
            if (existingRecord.chunkSize <= 0 ||
                existingRecord.totalChunks <= 0 ||
                existingRecord.fileSize != fileInfo.fileSize ||
                existingRecord.fileModifiedTime != fileModifiedTime
            ) {
                uploadRecordDao.deleteByFileUri(fileInfo.uri)
                return initNewSession(context, fileInfo, fileHash, fileModifiedTime, storagePolicy)
            }

            // A stale or unreachable resume session falls back to a fresh session.
            // If that fresh init also fails, its structured error is returned to UI.
            return try {
                val response = backupApi.getResumeInfo(existingRecord.sessionId)
                val resumeInfo = response.body()
                if (response.isSuccessful && resumeInfo != null &&
                    resumeInfo.totalChunks == existingRecord.totalChunks
                ) {
                    val receivedChunks = resumeInfo.receivedChunks
                    val startChunk = if (receivedChunks.isEmpty()) 0
                    else (receivedChunks.max() + 1).coerceAtMost(existingRecord.totalChunks)

                    SessionResolution.Success(
                        SessionInfo(
                            sessionId = existingRecord.sessionId,
                            startChunkIndex = startChunk,
                            chunkSize = existingRecord.chunkSize,
                            totalChunks = existingRecord.totalChunks
                        )
                    )
                } else {
                    android.util.Log.w(
                        "PhotoVaultBackup",
                        "Resume session rejected: HTTP ${response.code()}, starting fresh"
                    )
                    uploadRecordDao.deleteByFileUri(fileInfo.uri)
                    initNewSession(context, fileInfo, fileHash, fileModifiedTime, storagePolicy)
                }
            } catch (e: Exception) {
                android.util.Log.w(
                    "PhotoVaultBackup",
                    "Resume session failed (${e.javaClass.simpleName}), starting fresh",
                    e
                )
                uploadRecordDao.deleteByFileUri(fileInfo.uri)
                initNewSession(context, fileInfo, fileHash, fileModifiedTime, storagePolicy)
            }
        }

        return initNewSession(context, fileInfo, fileHash, fileModifiedTime, storagePolicy)
    }

    /**
     * Extracts a capture time as an ISO-8601 string.
     *
     * Images use EXIF metadata, while videos normally store their capture time
     * in the media container.  The latter prevents videos from being backed up
     * into the server's `unknown_date` directory.
     */
    private fun extractCaptureTime(context: Context, fileUri: Uri): String? =
        extractExifTime(context, fileUri) ?: extractVideoCaptureTime(context, fileUri)

    private fun extractExifTime(context: Context, fileUri: Uri): String? {
        return try {
            mediaBytesReader.openOriginal(context, fileUri).use { inputStream ->
                val exif = androidx.exifinterface.media.ExifInterface(inputStream)
                val dateStr = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME)
                    ?: return null

                // EXIF datetime format: "yyyy:MM:dd HH:mm:ss"
                val exifFormat = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                val date = exifFormat.parse(dateStr) ?: return null

                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                    .format(date)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractVideoCaptureTime(context: Context, fileUri: Uri): String? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, fileUri)
            val date = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DATE)
                ?: return null
            parseVideoCaptureTime(date)
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Metadata is optional for backup; release failures are non-fatal.
            }
        }
    }

    private fun parseVideoCaptureTime(value: String): String? {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val outputFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = utc }
        val formats = listOf(
            "yyyyMMdd'T'HHmmss.SSSZ",
            "yyyyMMdd'T'HHmmssZ",
            "yyyyMMdd'T'HHmmss.SSS'Z'",
            "yyyyMMdd'T'HHmmss'Z'"
        )

        for (pattern in formats) {
            try {
                val inputFormat = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                    isLenient = false
                    timeZone = utc
                }
                val date = inputFormat.parse(value) ?: continue
                return outputFormat.format(date)
            } catch (e: java.text.ParseException) {
                // Try the next container date format.
            }
        }
        return null
    }

    /**
     * Initializes a new upload session on the server and persists the record locally.
     */
    private suspend fun initNewSession(
        context: Context,
        fileInfo: FileInfo,
        fileHash: String,
        fileModifiedTime: Long,
        storagePolicy: StoragePolicyConfig
    ): SessionResolution {
        val request = InitUploadRequest(
            fileHash = fileHash,
            fileName = fileInfo.fileName,
            fileSize = fileInfo.fileSize,
            filePath = fileInfo.uri,
            // Marketing name where the OEM publishes one, else Build.MODEL.
            // The server uses this verbatim as a storage directory segment.
            deviceName = DeviceNameProvider.deviceName,
            sourceFolder = treeUriToRelativePath(fileInfo.folderUri),
            storagePolicy = storagePolicy,
            exifTime = extractCaptureTime(context, Uri.parse(fileInfo.uri)),
            fileModifiedTime = fileModifiedTime.toString(),
            mimeType = fileInfo.mimeType
        )

        val response = try {
            backupApi.initUpload(request)
        } catch (e: Exception) {
            android.util.Log.e(
                "PhotoVaultBackup",
                "initUpload exception: ${e.javaClass.simpleName}: ${e.message}",
                e
            )
            return SessionResolution.Failure(
                error = formatInitException(context, e),
                shouldRetry = isRetryableInitException(e)
            )
        }

        if (!response.isSuccessful) {
            val errorBody = try {
                response.errorBody()?.string()
            } catch (e: Exception) {
                null
            }
            android.util.Log.e(
                "PhotoVaultBackup",
                "initUpload HTTP ${response.code()}: $errorBody"
            )
            return SessionResolution.Failure(
                error = formatInitHttpError(context, response.code(), errorBody),
                shouldRetry = isRetryableInitHttpCode(response.code())
            )
        }

        val initResponse = response.body()
            ?: return SessionResolution.Failure(
                context.getString(R.string.backup_error_init_empty_response),
                shouldRetry = false
            )

        // /backup/init may deduplicate atomically even when the earlier check
        // missed a concurrent upload. A duplicate has no session and must never
        // create a local resume row or send chunks.
        if (initResponse.isDuplicate) {
            return SessionResolution.Duplicate(initResponse.fileId)
        }

        val sessionId = initResponse.sessionId?.takeIf { it.isNotBlank() }
            ?: return SessionResolution.Failure(
                context.getString(R.string.backup_error_init_empty_response),
                shouldRetry = false
            )
        if (initResponse.chunkSize <= 0 || initResponse.totalChunks <= 0) {
            return SessionResolution.Failure(
                context.getString(
                    R.string.backup_error_init_invalid_chunks,
                    initResponse.chunkSize,
                    initResponse.totalChunks
                ),
                shouldRetry = false
            )
        }

        val record = UploadRecord(
            fileUri = fileInfo.uri,
            sessionId = sessionId,
            fileHash = fileHash,
            fileName = fileInfo.fileName,
            fileSize = fileInfo.fileSize,
            fileModifiedTime = fileModifiedTime,
            // Persist folder + MIME so the upload can be rebuilt and
            // resumed after a process kill (see UploadRecord docs).
            folderUri = fileInfo.folderUri,
            mimeType = fileInfo.mimeType,
            totalChunks = initResponse.totalChunks,
            chunkSize = initResponse.chunkSize,
            uploadedChunkIndex = -1,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        try {
            uploadRecordDao.insertOrUpdate(record)
        } catch (e: Exception) {
            android.util.Log.e("PhotoVaultBackup", "Unable to persist upload session", e)
            return SessionResolution.Failure(
                context.getString(
                    R.string.backup_error_init_local_state,
                    diagnosticMessage(e)
                ),
                shouldRetry = false
            )
        }

        return SessionResolution.Success(
            SessionInfo(
                sessionId = sessionId,
                startChunkIndex = 0,
                chunkSize = initResponse.chunkSize,
                totalChunks = initResponse.totalChunks
            )
        )
    }

    /** Converts a SAF tree URI string into a clean relative folder path. */
    private fun treeUriToRelativePath(folderUri: String): String =
        safTreeUriToRelativePath(folderUri)

    /**
     * Uploads a single chunk with retry logic.
     * Retries up to MAX_RETRIES times with RETRY_DELAY_MS between attempts.
     *
     * @return classified success, retryable failure, or terminal failure
     */
    private suspend fun uploadChunkWithRetry(
        sessionId: String,
        chunkIndex: Int,
        chunkData: ByteArray
    ): ChunkUploadOutcome {
        val md5Checksum = fileHasher.computeMd5(chunkData)

        for (attempt in 1..MAX_RETRIES) {
            try {
                val sessionIdBody = sessionId.toRequestBody("text/plain".toMediaType())
                val chunkIndexBody = chunkIndex.toString().toRequestBody("text/plain".toMediaType())
                val checksumBody = md5Checksum.toRequestBody("text/plain".toMediaType())
                val chunkBody = MultipartBody.Part.createFormData(
                    "file",
                    "chunk_$chunkIndex",
                    chunkData.toRequestBody("application/octet-stream".toMediaType())
                )

                val response = backupApi.uploadChunk(
                    sessionId = sessionIdBody,
                    chunkIndex = chunkIndexBody,
                    checksum = checksumBody,
                    chunkData = chunkBody
                )

                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null && body.received && body.checksumValid) {
                        android.util.Log.d("PhotoVaultBackup", "  chunk $chunkIndex upload success")
                        return ChunkUploadOutcome.SUCCESS
                    } else {
                        android.util.Log.w("PhotoVaultBackup", "  chunk $chunkIndex upload failed: received=${body?.received}, checksumValid=${body?.checksumValid}")
                    }
                } else {
                    val errBody = try { response.errorBody()?.string() } catch (e: Exception) { null }
                    android.util.Log.w("PhotoVaultBackup", "  chunk $chunkIndex HTTP ${response.code()}: $errBody")
                    if (!isRetryableChunkHttpCode(response.code())) {
                        return ChunkUploadOutcome.NON_RETRYABLE_FAILURE
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("PhotoVaultBackup", "  chunk $chunkIndex attempt $attempt failed: ${e.javaClass.simpleName}: ${e.message}", e)
                if (rootCause(e) !is IOException) {
                    return ChunkUploadOutcome.NON_RETRYABLE_FAILURE
                }
            }

            // Wait before retrying (unless this was the last attempt)
            if (attempt < MAX_RETRIES) {
                delay(RETRY_DELAY_MS)
            }
        }

        return ChunkUploadOutcome.RETRYABLE_FAILURE
    }

    private enum class ChunkUploadOutcome {
        SUCCESS,
        RETRYABLE_FAILURE,
        NON_RETRYABLE_FAILURE
    }

    /**
     * Calls the complete upload endpoint after all chunks are uploaded.
     */
    private suspend fun completeUpload(
        sessionId: String,
        fileHash: String
    ): UploadResult.Success? {
        return try {
            val response = backupApi.completeUpload(
                CompleteUploadRequest(
                    sessionId = sessionId,
                    fileHash = fileHash
                )
            )

            if (response.isSuccessful) {
                val body = response.body() ?: return null
                // A 2xx with success=true is authoritative only when the response
                // also contains the stored path needed by the success result.
                if (body.success && body.storedPath != null) {
                    UploadResult.Success(
                        fileId = body.fileId,
                        storedPath = body.storedPath
                    )
                } else {
                    null
                }
            } else {
                // Non-2xx (e.g. HTTP 422 integrity verification failed) — retryable
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Copies the content behind [fileUri] into a private cache file and returns it.
     *
     * This gives a stable, seekable on-disk copy so the SHA-256 hash and the
     * uploaded chunks are guaranteed to be computed from identical bytes, even
     * if the original file is still being finalized by the OS/camera.
     */
    private fun createSnapshot(context: Context, fileUri: Uri, displayName: String): java.io.File {
        val cacheDir = java.io.File(context.cacheDir, "upload_snapshots")
        cacheDir.mkdirs()
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = java.io.File(cacheDir, "${System.nanoTime()}_$safeName")
        mediaBytesReader.openOriginal(context, fileUri).use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output, 64 * 1024)
            }
        }
        return dest
    }

    /**
     * Reads a specific chunk from a local snapshot file using random access.
     *
     * @return the chunk bytes, or null on failure.
     */
    private fun readChunkFromFile(
        file: java.io.File,
        chunkIndex: Int,
        fileSize: Long,
        chunkSize: Int
    ): ByteArray? {
        if (chunkSize <= 0) return null
        return try {
            val offset = chunkIndex.toLong() * chunkSize
            val remaining = fileSize - offset
            if (remaining <= 0) return null
            val chunkLength = minOf(remaining, chunkSize.toLong()).toInt()
            val buffer = ByteArray(chunkLength)
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                raf.readFully(buffer)
            }
            buffer
        } catch (e: Exception) {
            android.util.Log.e("PhotoVaultBackup", "readChunkFromFile failed at chunk $chunkIndex: ${e.message}", e)
            null
        }
    }

    /**
     * Reads a specific chunk from the file.
     *
     * @param context Application context
     * @param fileUri URI of the file
     * @param chunkIndex Zero-based index of the chunk to read
     * @param fileSize Total file size (to handle last chunk correctly)
     * @return Byte array of the chunk data, or null on failure
     */
    private fun readChunk(
        context: Context,
        fileUri: Uri,
        chunkIndex: Int,
        fileSize: Long,
        chunkSize: Int
    ): ByteArray? {
        if (chunkSize <= 0) return null
        return try {
            val offset = chunkIndex.toLong() * chunkSize
            val remaining = fileSize - offset
            val chunkLength = minOf(remaining, chunkSize.toLong()).toInt()

            context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                // Skip to the chunk offset
                var skipped = 0L
                while (skipped < offset) {
                    val s = inputStream.skip(offset - skipped)
                    if (s <= 0) break
                    skipped += s
                }

                if (skipped != offset) return null

                // Read the chunk
                val buffer = ByteArrayOutputStream(chunkLength)
                val readBuffer = ByteArray(8192)
                var totalRead = 0
                while (totalRead < chunkLength) {
                    val toRead = minOf(readBuffer.size, chunkLength - totalRead)
                    val bytesRead = inputStream.read(readBuffer, 0, toRead)
                    if (bytesRead == -1) break
                    buffer.write(readBuffer, 0, bytesRead)
                    totalRead += bytesRead
                }

                if (totalRead == chunkLength) buffer.toByteArray() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns true if the source file still exists in the media store.
     * Used to distinguish "source deleted on device" (skip) from a genuine
     * read/hash failure (fail + retry).
     */
    private fun sourceExists(context: Context, fileUri: Uri): Boolean {
        return try {
            context.contentResolver.query(
                fileUri,
                arrayOf(android.provider.MediaStore.MediaColumns._ID),
                null, null, null
            )?.use { it.moveToFirst() } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Gets the file's last modified time from the content resolver.
     */
    private fun getFileModifiedTime(context: Context, fileUri: Uri): Long {
        return try {
            context.contentResolver.query(
                fileUri,
                arrayOf(android.provider.MediaStore.MediaColumns.DATE_MODIFIED),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(0) * 1000 // Convert seconds to milliseconds
                } else {
                    0L
                }
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Calculates the total number of chunks for a file.
     */
    private fun calculateTotalChunks(fileSize: Long): Int {
        return ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
    }

    private fun formatInitHttpError(
        context: Context,
        code: Int,
        errorBody: String?
    ): String {
        val detail = extractServerDetail(errorBody)
            ?: context.getString(R.string.backup_error_init_no_detail)
        return when (code) {
            401 -> context.getString(R.string.backup_error_init_auth)
            403 -> context.getString(R.string.backup_error_init_forbidden)
            404 -> context.getString(R.string.backup_error_init_endpoint)
            400, 409, 413, 422 -> context.getString(
                R.string.backup_error_init_request,
                code,
                detail
            )
            429 -> context.getString(R.string.backup_error_init_rate_limited)
            in 500..599 -> context.getString(
                R.string.backup_error_init_server,
                code,
                detail
            )
            else -> context.getString(
                R.string.backup_error_init_http,
                code,
                detail
            )
        }
    }

    private fun isRetryableChunkHttpCode(code: Int): Boolean =
        code == 408 || code == 429 || code in 500..599

    private fun isRetryableInitHttpCode(code: Int): Boolean =
        code == 408 || code == 425 || code == 429 || code in 500..599

    private fun formatInitException(context: Context, error: Exception): String {
        val cause = rootCause(error)
        return when (cause) {
            is UnknownHostException -> context.getString(R.string.backup_error_init_dns)
            is ConnectException -> context.getString(R.string.backup_error_init_connect)
            is SocketTimeoutException -> context.getString(R.string.backup_error_init_timeout)
            is SSLException -> context.getString(R.string.backup_error_init_tls)
            is JsonParseException, is EOFException ->
                context.getString(R.string.backup_error_init_invalid_response)
            is IOException -> context.getString(
                R.string.backup_error_init_network,
                diagnosticMessage(cause)
            )
            else -> context.getString(
                R.string.backup_error_init_unexpected,
                cause.javaClass.simpleName,
                diagnosticMessage(cause)
            )
        }
    }

    private fun isRetryableInitException(error: Exception): Boolean {
        val cause = rootCause(error)
        return when (cause) {
            is UnknownHostException, is SSLException, is JsonParseException, is EOFException -> false
            is ConnectException, is SocketTimeoutException, is IOException -> true
            else -> false
        }
    }

    private fun rootCause(error: Throwable): Throwable {
        var current = error
        val visited = HashSet<Throwable>()
        while (current.cause != null && visited.add(current)) {
            current = current.cause!!
        }
        return current
    }

    private fun extractServerDetail(errorBody: String?): String? {
        val raw = errorBody?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsed = runCatching {
            val json = JSONObject(raw)
            sequenceOf("detail", "message", "error")
                .mapNotNull { key ->
                    json.optString(key)
                        .takeIf { it.isNotBlank() && it != "null" }
                }
                .firstOrNull()
        }.getOrNull()
        val detail = parsed ?: raw.takeUnless { it.startsWith("<") } ?: return null
        return sanitizeDiagnostic(detail)
    }

    private fun diagnosticMessage(error: Throwable): String =
        sanitizeDiagnostic(error.message ?: error.javaClass.simpleName)

    private fun sanitizeDiagnostic(value: String): String =
        value.replace(Regex("\\s+"), " ").trim().take(200)

    private sealed class SessionResolution {
        data class Success(val sessionInfo: SessionInfo) : SessionResolution()
        data class Duplicate(val fileId: Int?) : SessionResolution()
        data class Failure(
            val error: String,
            val shouldRetry: Boolean
        ) : SessionResolution()
    }

    /**
     * Internal data class for session resolution result.
     */
    private data class SessionInfo(
        val sessionId: String,
        val startChunkIndex: Int,
        val chunkSize: Int,
        val totalChunks: Int
    )
}


/**
 * Converts a SAF tree URI string into a clean relative folder path, e.g.
 * `content://.../tree/primary%3ADCIM%2FCamera` -> `DCIM/Camera`.
 *
 * Parses from the decoded [android.net.Uri.getPath] rather than
 * [android.provider.DocumentsContract.getTreeDocumentId]: a re-backup's folderUri
 * arrives URL-decoded from the navigation layer (`.../tree/primary:DCIM/Camera`),
 * which splits the single tree doc-id into multiple path segments.
 * getTreeDocumentId then returns only the first segment (`primary:DCIM`), dropping
 * `Camera` and saving the file one directory level too high. Uri.path is already
 * decoded and preserves the full relative path, so this works for BOTH the encoded
 * (normal backup) and decoded (re-backup) forms — mirroring
 * FolderDetailViewModel.queryMediaStoreImages.
 *
 * Falls back to the original string if it cannot be parsed.
 */
internal fun safTreeUriToRelativePath(folderUri: String): String {
    return try {
        val treeUri = android.net.Uri.parse(folderUri)
        val path = treeUri.path ?: return folderUri // "/tree/primary:DCIM/Camera"
        path.removePrefix("/tree/").substringAfter(':').trim('/')
    } catch (e: Exception) {
        folderUri
    }
}
