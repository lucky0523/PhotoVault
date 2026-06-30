package com.photovault.data.api.model

import com.google.gson.annotations.SerializedName

/**
 * Request/Response models for the backup API endpoints.
 */

// --- Duplicate Check ---

data class DuplicateCheckRequest(
    @SerializedName("file_hash") val fileHash: String,
    @SerializedName("file_path") val filePath: String,
    @SerializedName("device_name") val deviceName: String
)

data class DuplicateCheckResponse(
    @SerializedName("is_duplicate") val isDuplicate: Boolean,
    @SerializedName("file_id") val fileId: String?,
    @SerializedName("status") val status: String? = "active",
    @SerializedName("expires_at") val expiresAt: String? = null
)

// --- Init Upload ---

data class StoragePolicyConfig(
    @SerializedName("use_custom_path") val useCustomPath: Boolean,
    @SerializedName("custom_path") val customPath: String?,
    @SerializedName("use_year_month_layer") val useYearMonthLayer: Boolean
)

data class InitUploadRequest(
    @SerializedName("file_hash") val fileHash: String,
    @SerializedName("file_name") val fileName: String,
    @SerializedName("file_size") val fileSize: Long,
    @SerializedName("file_path") val filePath: String,
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("source_folder") val sourceFolder: String,
    @SerializedName("storage_policy") val storagePolicy: StoragePolicyConfig,
    @SerializedName("exif_time") val exifTime: String?,
    @SerializedName("file_modified_time") val fileModifiedTime: String
)

data class InitUploadResponse(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("total_chunks") val totalChunks: Int,
    @SerializedName("chunk_size") val chunkSize: Int
)

// --- Chunk Upload ---

data class ChunkUploadResponse(
    @SerializedName("chunk_index") val chunkIndex: Int,
    @SerializedName("received") val received: Boolean,
    @SerializedName("checksum_valid") val checksumValid: Boolean
)

// --- Complete Upload ---

data class CompleteUploadRequest(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("file_hash") val fileHash: String
)

data class CompleteUploadResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("file_id") val fileId: String,
    @SerializedName("integrity_valid") val integrityValid: Boolean,
    @SerializedName("stored_path") val storedPath: String
)

// --- Resume Info ---

data class ResumeInfoResponse(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("received_chunks") val receivedChunks: List<Int>,
    @SerializedName("total_chunks") val totalChunks: Int,
    @SerializedName("file_hash") val fileHash: String,
    @SerializedName("expires_at") val expiresAt: String
)

// --- Upload Progress (for UI) ---

data class UploadProgress(
    val fileName: String,
    val totalBytes: Long,
    val uploadedBytes: Long,
    val currentChunk: Int,
    val totalChunks: Int,
    val state: UploadState
)

enum class UploadState {
    HASHING,
    CHECKING_DUPLICATE,
    INITIALIZING,
    UPLOADING,
    COMPLETING,
    COMPLETED,
    SKIPPED_DUPLICATE,
    SKIPPED_TRASHED,
    SKIPPED_PURGED,
    FAILED
}

// --- Upload Result ---

sealed class UploadResult {
    data class Success(val fileId: String, val storedPath: String) : UploadResult()
    data class Duplicate(val fileId: String?) : UploadResult()
    data class Skipped(val reason: String) : UploadResult()
    data class Failed(val error: String, val shouldRetry: Boolean = true) : UploadResult()
}
