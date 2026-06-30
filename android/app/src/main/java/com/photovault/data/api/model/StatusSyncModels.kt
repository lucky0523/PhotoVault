package com.photovault.data.api.model

import com.google.gson.annotations.SerializedName

/**
 * Response from `GET /files/status-sync`.
 *
 * Returns every file record on the server that is not in the "active" state
 * (i.e. trashed or purged). The client matches these by [StatusSyncItem.fileHash]
 * to update its local photo_status table.
 */
data class StatusSyncResponse(
    @SerializedName("items")
    val items: List<StatusSyncItem>
)

/**
 * A single non-active file record on the server.
 *
 * @param fileHash SHA-256 hash of the file content — used to match local records
 * @param status    "trashed" or "purged"
 * @param deletedAt ISO-8601 timestamp when the file was moved to the recycle bin
 * @param purgedAt  ISO-8601 timestamp when the file was permanently deleted
 * @param expiresAt ISO-8601 timestamp when the trash item will be auto-purged (30 days after deletion)
 */
data class StatusSyncItem(
    @SerializedName("file_hash")
    val fileHash: String,

    @SerializedName("status")
    val status: String,

    @SerializedName("deleted_at")
    val deletedAt: String? = null,

    @SerializedName("purged_at")
    val purgedAt: String? = null,

    @SerializedName("expires_at")
    val expiresAt: String? = null
)
