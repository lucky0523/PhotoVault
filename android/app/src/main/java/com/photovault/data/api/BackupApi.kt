package com.photovault.data.api

import com.photovault.data.api.model.ChunkUploadResponse
import com.photovault.data.api.model.CompleteUploadRequest
import com.photovault.data.api.model.CompleteUploadResponse
import com.photovault.data.api.model.DuplicateCheckRequest
import com.photovault.data.api.model.DuplicateCheckResponse
import com.photovault.data.api.model.InitUploadRequest
import com.photovault.data.api.model.InitUploadResponse
import com.photovault.data.api.model.ResumeInfoResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

/**
 * Retrofit interface for backup-related API endpoints.
 * Handles duplicate detection, upload initialization, chunk upload,
 * upload completion, and resume info retrieval.
 */
interface BackupApi {

    @POST("/api/v1/backup/check")
    suspend fun checkDuplicate(
        @Body request: DuplicateCheckRequest
    ): Response<DuplicateCheckResponse>

    @POST("/api/v1/backup/init")
    suspend fun initUpload(
        @Body request: InitUploadRequest
    ): Response<InitUploadResponse>

    @Multipart
    @POST("/api/v1/backup/chunk")
    suspend fun uploadChunk(
        @Part("session_id") sessionId: RequestBody,
        @Part("chunk_index") chunkIndex: RequestBody,
        @Part("checksum") checksum: RequestBody,
        @Part chunkData: MultipartBody.Part
    ): Response<ChunkUploadResponse>

    @POST("/api/v1/backup/complete")
    suspend fun completeUpload(
        @Body request: CompleteUploadRequest
    ): Response<CompleteUploadResponse>

    @GET("/api/v1/backup/resume/{session_id}")
    suspend fun getResumeInfo(
        @Path("session_id") sessionId: String
    ): Response<ResumeInfoResponse>
}
