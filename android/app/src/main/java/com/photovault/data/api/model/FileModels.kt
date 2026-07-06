package com.photovault.data.api.model

import com.google.gson.annotations.SerializedName

/**
 * Request/Response models for the file browsing API endpoints.
 * Used by the Cloud Tab to display backed-up files and directories.
 */

// --- Directory Browsing ---

data class DirectoryListingResponse(
    @SerializedName("current_path") val currentPath: String,
    @SerializedName("parent_path") val parentPath: String?,
    @SerializedName("directories") val directories: List<DirectoryInfo>,
    @SerializedName("files") val files: List<FileBrowseInfo>,
    @SerializedName("total_files") val totalFiles: Int,
    @SerializedName("page") val page: Int,
    @SerializedName("page_size") val pageSize: Int
)

data class DirectoryInfo(
    @SerializedName("name") val name: String,
    @SerializedName("path") val path: String,
    @SerializedName("file_count") val fileCount: Int,
    @SerializedName("latest_file_time") val latestFileTime: String?,
    // 逐目录分状态计数，默认 0（兼容尚未返回该字段的服务端）
    @SerializedName("backed_up_count") val backedUpCount: Int = 0,
    @SerializedName("trashed_count") val trashedCount: Int = 0,
    @SerializedName("purged_count") val purgedCount: Int = 0
)

data class FileBrowseInfo(
    @SerializedName("id") val id: Int,
    @SerializedName("file_name") val fileName: String,
    @SerializedName("file_size") val fileSize: Long,
    @SerializedName("mime_type") val mimeType: String?,
    @SerializedName("exif_time") val exifTime: String?,
    @SerializedName("thumbnail_url") val thumbnailUrl: String?,
    @SerializedName("created_at") val createdAt: String
)
