package com.photovault.service

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.photovault.data.api.BackupApi
import com.photovault.data.api.model.CompleteUploadRequest
import com.photovault.data.api.model.DuplicateCheckRequest
import com.photovault.data.api.model.InitUploadRequest
import com.photovault.data.api.model.StoragePolicyConfig
import com.photovault.data.api.model.UploadProgress
import com.photovault.data.api.model.UploadResult
import com.photovault.data.api.model.UploadState
import com.photovault.data.local.dao.UploadRecordDao
import com.photovault.data.local.entity.PhotoStatusValue
import com.photovault.data.local.entity.UploadRecord
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

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
    private val statusSyncManager: StatusSyncManager
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
                return UploadResult.Skipped("源文件已删除", countsAsBackedUp = false)
            }
            return UploadResult.Failed("Failed to compute file hash: ${e.message}", shouldRetry = false)
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

        val duplicateResult = checkDuplicate(precheckHash, fileInfo)
        if (duplicateResult != null) {
            val skipState = when (duplicateResult) {
                is UploadResult.Duplicate -> UploadState.SKIPPED_DUPLICATE
                is UploadResult.Skipped -> {
                    if (duplicateResult.reason.contains("回收站")) UploadState.SKIPPED_TRASHED
                    else UploadState.SKIPPED_PURGED
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
            return UploadResult.Failed("Failed to stage file for upload: ${e.message}", shouldRetry = true)
        }

        try {

        val fileHash: String
        val actualSize: Long
        try {
            val result = fileHasher.computeSha256AndSize(snapshot)
            fileHash = result.hash
            actualSize = result.size
        } catch (e: Exception) {
            return UploadResult.Failed("Failed to compute file hash: ${e.message}", shouldRetry = false)
        }
        android.util.Log.i(
            "PhotoVaultBackup",
            "Prepared ${fileInfo.fileName}: mediaStoreSize=${fileInfo.fileSize}, snapshotSize=$actualSize, precheckHash=$precheckHash, snapshotHash=$fileHash"
        )

        // Authoritative size = snapshot length, which matches the hashed bytes.
        val upload = fileInfo.copy(fileSize = actualSize)

        // Step 3: Determine total chunks
        val totalChunks = calculateTotalChunks(upload.fileSize)

        // Step 4: Check for resume or initialize new session
        onProgress(
            UploadProgress(
                fileName = upload.fileName,
                totalBytes = upload.fileSize,
                uploadedBytes = 0,
                currentChunk = 0,
                totalChunks = totalChunks,
                state = UploadState.INITIALIZING
            )
        )

        val sessionInfo = resolveSession(context, fileUri, upload, fileHash, totalChunks, storagePolicy)
            ?: return UploadResult.Failed("Failed to initialize upload session", shouldRetry = true)

        val sessionId = sessionInfo.sessionId
        val startChunkIndex = sessionInfo.startChunkIndex

        // Step 5: Upload chunks sequentially
        var lastUploadedChunk = startChunkIndex - 1
        for (chunkIndex in startChunkIndex until totalChunks) {
            val uploadedBytes = chunkIndex.toLong() * CHUNK_SIZE
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

            val chunkData = readChunkFromFile(snapshot, chunkIndex, upload.fileSize)
                ?: return UploadResult.Failed("Failed to read chunk $chunkIndex", shouldRetry = true)

            val chunkResult = uploadChunkWithRetry(sessionId, chunkIndex, chunkData)
            if (!chunkResult) {
                // All retries exhausted — mark as failed for later retry
                return UploadResult.Failed(
                    "Failed to upload chunk $chunkIndex after $MAX_RETRIES retries",
                    shouldRetry = true
                )
            }

            // Update local progress record
            lastUploadedChunk = chunkIndex
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

        return UploadResult.Failed("Failed to complete upload", shouldRetry = true)

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
    private suspend fun checkDuplicate(fileHash: String, fileInfo: FileInfo): UploadResult? {
        return try {
            val response = backupApi.checkDuplicate(
                DuplicateCheckRequest(
                    fileHash = fileHash,
                    filePath = fileInfo.uri,
                    deviceName = android.os.Build.MODEL
                )
            )
            if (!response.isSuccessful) return null

            val body = response.body() ?: return null
            val status = body.status ?: "active"

            when {
                body.isDuplicate && status == PhotoStatusValue.ACTIVE -> {
                    // File is already active on the server — record locally and skip
                    statusSyncManager.markActive(fileInfo.uri, fileHash)
                    UploadResult.Duplicate(body.fileId)
                }
                status == PhotoStatusValue.TRASHED -> {
                    // File was moved to recycle bin on the server — skip and record locally
                    statusSyncManager.markTrashed(fileInfo.uri, fileHash, body.expiresAt)
                    UploadResult.Skipped("文件在回收站中")
                }
                status == PhotoStatusValue.PURGED -> {
                    // File was permanently deleted on the server — skip and record locally
                    statusSyncManager.markPurged(fileInfo.uri, fileHash)
                    UploadResult.Skipped("文件已彻底删除")
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
        fileUri: Uri,
        fileInfo: FileInfo,
        fileHash: String,
        totalChunks: Int,
        storagePolicy: StoragePolicyConfig
    ): SessionInfo? {
        // Check for existing upload record (resume case)
        val existingRecord = uploadRecordDao.getByFileUri(fileInfo.uri)

        if (existingRecord != null) {
            // Verify session not expired (7 days)
            val elapsed = System.currentTimeMillis() - existingRecord.createdAt
            if (elapsed > SESSION_EXPIRE_MS) {
                // Session expired — delete record and start fresh
                uploadRecordDao.deleteByFileUri(fileInfo.uri)
                return initNewSession(context, fileInfo, fileHash, totalChunks, storagePolicy)
            }

            // Verify source file not modified (size and modification time)
            val currentModifiedTime = getFileModifiedTime(context, fileUri)
            if (existingRecord.fileSize != fileInfo.fileSize ||
                existingRecord.fileModifiedTime != currentModifiedTime
            ) {
                // File modified — delete record and start fresh
                uploadRecordDao.deleteByFileUri(fileInfo.uri)
                return initNewSession(context, fileInfo, fileHash, totalChunks, storagePolicy)
            }

            // Query server for received chunks
            return try {
                val response = backupApi.getResumeInfo(existingRecord.sessionId)
                if (response.isSuccessful) {
                    val resumeInfo = response.body()!!
                    val receivedChunks = resumeInfo.receivedChunks
                    val startChunk = if (receivedChunks.isEmpty()) 0
                    else receivedChunks.max() + 1

                    SessionInfo(
                        sessionId = existingRecord.sessionId,
                        startChunkIndex = startChunk
                    )
                } else {
                    // Server doesn't recognize session — start fresh
                    uploadRecordDao.deleteByFileUri(fileInfo.uri)
                    initNewSession(context, fileInfo, fileHash, totalChunks, storagePolicy)
                }
            } catch (e: Exception) {
                // Network error querying resume — start fresh
                uploadRecordDao.deleteByFileUri(fileInfo.uri)
                initNewSession(context, fileInfo, fileHash, totalChunks, storagePolicy)
            }
        }

        // No existing record — initialize new session
        return initNewSession(context, fileInfo, fileHash, totalChunks, storagePolicy)
    }

    /**
     * Extracts the EXIF capture time (DateTimeOriginal) from an image, if present.
     * Returns an ISO-8601 formatted string, or null if unavailable/unreadable.
     */
    private fun extractExifTime(context: Context, fileUri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                val exif = androidx.exifinterface.media.ExifInterface(inputStream)
                val dateStr = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME)
                    ?: return null

                // EXIF datetime format: "yyyy:MM:dd HH:mm:ss"
                val exifFormat = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                val date = exifFormat.parse(dateStr) ?: return null

                val isoFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                isoFormat.format(date)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Initializes a new upload session on the server and persists the record locally.
     */
    private suspend fun initNewSession(
        context: Context,
        fileInfo: FileInfo,
        fileHash: String,
        totalChunks: Int,
        storagePolicy: StoragePolicyConfig
    ): SessionInfo? {
        return try {
            val exifTime = extractExifTime(context, Uri.parse(fileInfo.uri))
            val response = backupApi.initUpload(
                InitUploadRequest(
                    fileHash = fileHash,
                    fileName = fileInfo.fileName,
                    fileSize = fileInfo.fileSize,
                    filePath = fileInfo.uri,
                    deviceName = android.os.Build.MODEL,
                    sourceFolder = treeUriToRelativePath(fileInfo.folderUri),
                    storagePolicy = storagePolicy,
                    exifTime = exifTime,
                    fileModifiedTime = fileInfo.createdTime.toString(),
                    mimeType = fileInfo.mimeType
                )
            )

            if (response.isSuccessful) {
                val initResponse = response.body()!!

                // Persist upload record for resume capability
                val record = UploadRecord(
                    fileUri = fileInfo.uri,
                    sessionId = initResponse.sessionId,
                    fileHash = fileHash,
                    fileName = fileInfo.fileName,
                    fileSize = fileInfo.fileSize,
                    fileModifiedTime = fileInfo.createdTime,
                    totalChunks = initResponse.totalChunks,
                    uploadedChunkIndex = -1,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                uploadRecordDao.insertOrUpdate(record)

                SessionInfo(
                    sessionId = initResponse.sessionId,
                    startChunkIndex = 0
                )
            } else {
                val errBody = try { response.errorBody()?.string() } catch (e: Exception) { null }
                android.util.Log.e("PhotoVaultBackup", "initUpload HTTP ${response.code()}: $errBody")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("PhotoVaultBackup", "initUpload exception: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    /**
     * Converts a SAF tree URI string into a clean relative folder path.
     * e.g. content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera
     *      -> "DCIM/Camera"
     * Falls back to the original string if it cannot be parsed.
     */
    private fun treeUriToRelativePath(folderUri: String): String {
        return try {
            val treeUri = android.net.Uri.parse(folderUri)
            val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            // docId looks like "primary:DCIM/Camera"
            docId.substringAfter(':').trim('/')
        } catch (e: Exception) {
            folderUri
        }
    }

    /**
     * Uploads a single chunk with retry logic.
     * Retries up to MAX_RETRIES times with RETRY_DELAY_MS between attempts.
     *
     * @return true if chunk was successfully uploaded, false if all retries exhausted
     */
    private suspend fun uploadChunkWithRetry(
        sessionId: String,
        chunkIndex: Int,
        chunkData: ByteArray
    ): Boolean {
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
                        return true
                    } else {
                        android.util.Log.w("PhotoVaultBackup", "  chunk $chunkIndex upload failed: received=${body?.received}, checksumValid=${body?.checksumValid}")
                    }
                } else {
                    val errBody = try { response.errorBody()?.string() } catch (e: Exception) { null }
                    android.util.Log.w("PhotoVaultBackup", "  chunk $chunkIndex HTTP ${response.code()}: $errBody")
                }
            } catch (e: Exception) {
                android.util.Log.w("PhotoVaultBackup", "  chunk $chunkIndex attempt $attempt failed: ${e.javaClass.simpleName}: ${e.message}", e)
            }

            // Wait before retrying (unless this was the last attempt)
            if (attempt < MAX_RETRIES) {
                delay(RETRY_DELAY_MS)
            }
        }

        return false
    }

    /**
     * Calls the complete upload endpoint after all chunks are uploaded.
     */
    private suspend fun completeUpload(sessionId: String, fileHash: String): UploadResult? {
        return try {
            val response = backupApi.completeUpload(
                CompleteUploadRequest(
                    sessionId = sessionId,
                    fileHash = fileHash
                )
            )

            if (response.isSuccessful) {
                val body = response.body()!!
                // The server only returns success=true AFTER it has verified the
                // merged file's SHA-256 against the expected hash, so success
                // implies integrity is valid. (Older/again-parsed responses may
                // omit integrity_valid; don't fail a genuine success on that.)
                if (body.success) {
                    UploadResult.Success(
                        fileId = body.fileId,
                        storedPath = body.storedPath
                    )
                } else {
                    // Integrity check failed — server will discard the file
                    UploadResult.Failed("File integrity verification failed", shouldRetry = true)
                }
            } else {
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
        context.contentResolver.openInputStream(fileUri)?.use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output, 64 * 1024)
            }
        } ?: throw java.io.IOException("Cannot open source: $fileUri")
        return dest
    }

    /**
     * Reads a specific chunk from a local snapshot file using random access.
     *
     * @return the chunk bytes, or null on failure.
     */
    private fun readChunkFromFile(file: java.io.File, chunkIndex: Int, fileSize: Long): ByteArray? {
        return try {
            val offset = chunkIndex.toLong() * CHUNK_SIZE
            val remaining = fileSize - offset
            if (remaining <= 0) return null
            val chunkLength = minOf(remaining, CHUNK_SIZE.toLong()).toInt()
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
        fileSize: Long
    ): ByteArray? {
        return try {
            val offset = chunkIndex.toLong() * CHUNK_SIZE
            val remaining = fileSize - offset
            val chunkLength = minOf(remaining, CHUNK_SIZE.toLong()).toInt()

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

    /**
     * Internal data class for session resolution result.
     */
    private data class SessionInfo(
        val sessionId: String,
        val startChunkIndex: Int
    )
}
