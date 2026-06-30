package com.photovault.data.api

import com.photovault.data.api.model.DirectoryListingResponse
import com.photovault.data.api.model.StatusSyncResponse
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Retrofit interface for file browsing API endpoints.
 * Used by the Cloud Tab to browse backed-up files on the server.
 */
interface FileApi {

    /**
     * Browse directory structure on the server.
     * Returns directories and files at the given path.
     */
    @GET("/api/v1/files/browse")
    suspend fun browseDirectory(
        @Query("path") path: String = "/",
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50
    ): Response<DirectoryListingResponse>

    /**
     * List files in a specific directory.
     */
    @GET("/api/v1/files/list")
    suspend fun listFiles(
        @Query("path") path: String = "/",
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50
    ): Response<DirectoryListingResponse>

    /**
     * Get a thumbnail image for a file.
     */
    @GET("/api/v1/files/thumbnail/{file_id}")
    suspend fun getThumbnail(
        @Path("file_id") fileId: Int
    ): Response<ResponseBody>

    /**
     * Download the original file (streaming for large files).
     */
    @Streaming
    @GET("/api/v1/files/download/{file_id}")
    suspend fun downloadFile(
        @Path("file_id") fileId: Int
    ): Response<ResponseBody>

    /**
     * Fetch status changes for all trashed/purged files on the server.
     *
     * Used by [com.photovault.service.StatusSyncManager] to synchronize the
     * local photo_status table so the client knows which files were deleted
     * via the web UI (and should be skipped during automatic backup).
     */
    @GET("/api/v1/files/status-sync")
    suspend fun getStatusSync(): Response<StatusSyncResponse>
}
