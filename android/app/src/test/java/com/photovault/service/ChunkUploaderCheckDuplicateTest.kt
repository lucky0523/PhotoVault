package com.photovault.service

import com.photovault.data.api.BackupApi
import com.photovault.data.api.FileApi
import com.photovault.data.api.model.ChunkUploadResponse
import com.photovault.data.api.model.CompleteUploadRequest
import com.photovault.data.api.model.CompleteUploadResponse
import com.photovault.data.api.model.DirectoryListingResponse
import com.photovault.data.api.model.DuplicateCheckRequest
import com.photovault.data.api.model.DuplicateCheckResponse
import com.photovault.data.api.model.InitUploadRequest
import com.photovault.data.api.model.InitUploadResponse
import com.photovault.data.api.model.ResumeInfoResponse
import com.photovault.data.api.model.StatusSyncItem
import com.photovault.data.api.model.StatusSyncResponse
import com.photovault.data.api.model.UploadResult
import com.photovault.data.local.dao.PhotoStatusDao
import com.photovault.data.local.dao.UploadRecordDao
import com.photovault.data.local.entity.PhotoStatus
import com.photovault.data.local.entity.PhotoStatusValue
import com.photovault.data.local.entity.UploadRecord
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Unit tests for [ChunkUploader.checkDuplicate]'s force-reupload branch.
 *
 * When a trashed/purged photo is manually re-backed-up ([FileInfo.forceReupload]
 * = true), the uploader must NOT short-circuit on the server's trashed/purged
 * status. Instead it returns null so the caller proceeds to upload and complete,
 * letting the server reactivate the record. Without the flag, the historical
 * behaviour is preserved: the upload is skipped and the local status is recorded.
 */
class ChunkUploaderCheckDuplicateTest {

    private fun uploader(
        dao: FakePhotoStatusDao,
        checkResponder: (String) -> Response<DuplicateCheckResponse>
    ): ChunkUploader {
        val backupApi = FakeBackupApi(checkResponder)
        val statusSyncManager = StatusSyncManager(FakeFileApi(), backupApi, dao)
        return ChunkUploader(
            backupApi = backupApi,
            uploadRecordDao = FakeUploadRecordDao(),
            fileHasher = FileHasher(MediaBytesReader()),
            statusSyncManager = statusSyncManager,
            mediaBytesReader = MediaBytesReader()
        )
    }

    private fun fileInfo(forceReupload: Boolean) = FileInfo(
        uri = "uri://a",
        fileName = "photo.jpg",
        fileSize = 100L,
        createdTime = 0L,
        mimeType = "image/jpeg",
        folderUri = "tree://folder",
        forceReupload = forceReupload
    )

    // --- force branch: must proceed (null) and leave local status untouched ---

    @Test
    fun forceReupload_trashedOnServer_proceedsAndDoesNotRemark() = runTest {
        val dao = FakePhotoStatusDao(mutableListOf())
        val uploader = uploader(dao) {
            successCheck(isDuplicate = false, status = PhotoStatusValue.TRASHED)
        }

        val result = uploader.checkDuplicate("hashA", fileInfo(forceReupload = true))

        assertNull("force re-upload must proceed (not skip) for trashed files", result)
        assertNull("local status must not be re-marked as trashed", dao.get("uri://a"))
    }

    @Test
    fun forceReupload_purgedOnServer_proceedsAndDoesNotRemark() = runTest {
        val dao = FakePhotoStatusDao(mutableListOf())
        val uploader = uploader(dao) {
            successCheck(isDuplicate = false, status = PhotoStatusValue.PURGED)
        }

        val result = uploader.checkDuplicate("hashA", fileInfo(forceReupload = true))

        assertNull("force re-upload must proceed (not skip) for purged files", result)
        assertNull("local status must not be re-marked as purged", dao.get("uri://a"))
    }

    // --- non-force branch: preserve historical skip behaviour -----------------

    @Test
    fun normal_trashedOnServer_isSkippedAndMarked() = runTest {
        val dao = FakePhotoStatusDao(mutableListOf())
        val uploader = uploader(dao) {
            successCheck(isDuplicate = false, status = PhotoStatusValue.TRASHED)
        }

        val result = uploader.checkDuplicate("hashA", fileInfo(forceReupload = false))

        assertTrue(result is UploadResult.Skipped)
        assertEquals("文件在回收站中", (result as UploadResult.Skipped).reason)
        assertEquals(PhotoStatusValue.TRASHED, dao.get("uri://a")!!.status)
    }

    @Test
    fun normal_purgedOnServer_isSkippedAndMarked() = runTest {
        val dao = FakePhotoStatusDao(mutableListOf())
        val uploader = uploader(dao) {
            successCheck(isDuplicate = false, status = PhotoStatusValue.PURGED)
        }

        val result = uploader.checkDuplicate("hashA", fileInfo(forceReupload = false))

        assertTrue(result is UploadResult.Skipped)
        assertEquals("文件已彻底删除", (result as UploadResult.Skipped).reason)
        assertEquals(PhotoStatusValue.PURGED, dao.get("uri://a")!!.status)
    }

    // --- active duplicate: force flag is irrelevant, still skipped as duplicate ---

    @Test
    fun forceReupload_activeDuplicate_stillReportedAsDuplicate() = runTest {
        val dao = FakePhotoStatusDao(mutableListOf())
        val uploader = uploader(dao) {
            successCheck(isDuplicate = true, status = PhotoStatusValue.ACTIVE)
        }

        val result = uploader.checkDuplicate("hashA", fileInfo(forceReupload = true))

        assertTrue(result is UploadResult.Duplicate)
        assertEquals(PhotoStatusValue.ACTIVE, dao.get("uri://a")!!.status)
    }

    @Test
    fun notFoundOnServer_proceedsRegardlessOfForce() = runTest {
        val dao = FakePhotoStatusDao(mutableListOf())
        val uploader = uploader(dao) {
            successCheck(isDuplicate = false, status = "not_found")
        }

        assertNull(uploader.checkDuplicate("hashA", fileInfo(forceReupload = false)))
        assertNull(uploader.checkDuplicate("hashA", fileInfo(forceReupload = true)))
    }

    // --- helpers -----------------------------------------------------------

    private fun successCheck(isDuplicate: Boolean, status: String) =
        Response.success(
            DuplicateCheckResponse(isDuplicate = isDuplicate, fileId = null, status = status)
        )

    /** In-memory [PhotoStatusDao] keyed by fileUri. */
    private class FakePhotoStatusDao(
        private val store: MutableList<PhotoStatus>
    ) : PhotoStatusDao {
        fun get(uri: String): PhotoStatus? = store.find { it.fileUri == uri }

        override suspend fun upsert(status: PhotoStatus) {
            store.removeAll { it.fileUri == status.fileUri }
            store.add(status)
        }

        override suspend fun upsertAll(statuses: List<PhotoStatus>) {
            statuses.forEach { upsert(it) }
        }

        override suspend fun getByFileUri(fileUri: String): PhotoStatus? =
            store.find { it.fileUri == fileUri }

        override suspend fun getByFileHash(fileHash: String): List<PhotoStatus> =
            store.filter { it.fileHash == fileHash }

        override suspend fun getByStatus(status: String): List<PhotoStatus> =
            store.filter { it.status == status }

        override suspend fun getNonActive(): List<PhotoStatus> =
            store.filter { it.status != PhotoStatusValue.ACTIVE }

        override suspend fun getAll(): List<PhotoStatus> = store.toList()

        override fun observeAll(): kotlinx.coroutines.flow.Flow<List<PhotoStatus>> =
            kotlinx.coroutines.flow.flowOf(store.toList())

        override suspend fun updateStatusByHash(
            fileHash: String,
            status: String,
            deletedAt: Long?,
            expiresAt: Long?,
            updatedAt: Long
        ) {
            store.replaceAll {
                if (it.fileHash == fileHash) {
                    it.copy(status = status, deletedAt = deletedAt, expiresAt = expiresAt, updatedAt = updatedAt)
                } else it
            }
        }

        override suspend fun markActive(fileUri: String, updatedAt: Long) {
            store.replaceAll {
                if (it.fileUri == fileUri) {
                    it.copy(
                        status = PhotoStatusValue.ACTIVE,
                        deletedAt = null,
                        expiresAt = null,
                        updatedAt = updatedAt
                    )
                } else it
            }
        }

        override suspend fun delete(fileUri: String) {
            store.removeAll { it.fileUri == fileUri }
        }
    }

    /** [BackupApi] whose /backup/check response is supplied per test. */
    private class FakeBackupApi(
        private val checkResponder: (String) -> Response<DuplicateCheckResponse>
    ) : BackupApi {
        override suspend fun checkDuplicate(request: DuplicateCheckRequest): Response<DuplicateCheckResponse> =
            checkResponder(request.fileHash)

        override suspend fun initUpload(request: InitUploadRequest): Response<InitUploadResponse> =
            throw NotImplementedError()
        override suspend fun uploadChunk(
            sessionId: RequestBody,
            chunkIndex: RequestBody,
            checksum: RequestBody,
            chunkData: MultipartBody.Part
        ): Response<ChunkUploadResponse> = throw NotImplementedError()
        override suspend fun completeUpload(request: CompleteUploadRequest): Response<CompleteUploadResponse> =
            throw NotImplementedError()
        override suspend fun getResumeInfo(sessionId: String): Response<ResumeInfoResponse> =
            throw NotImplementedError()
    }

    /** [FileApi] stub — status sync isn't exercised by checkDuplicate. */
    private class FakeFileApi : FileApi {
        override suspend fun getStatusSync(): Response<StatusSyncResponse> =
            Response.success(StatusSyncResponse(emptyList<StatusSyncItem>()))
        override suspend fun browseDirectory(path: String, page: Int, pageSize: Int): Response<DirectoryListingResponse> =
            throw NotImplementedError()
        override suspend fun listFiles(path: String, page: Int, pageSize: Int): Response<DirectoryListingResponse> =
            throw NotImplementedError()
        override suspend fun getThumbnail(fileId: Int): Response<okhttp3.ResponseBody> =
            throw NotImplementedError()
        override suspend fun downloadFile(fileId: Int): Response<okhttp3.ResponseBody> =
            throw NotImplementedError()
        override suspend fun listTrash(page: Int, pageSize: Int): Response<com.photovault.data.api.model.TrashListResponse> =
            throw NotImplementedError()
        override suspend fun restoreTrashFile(fileId: Int): Response<com.photovault.data.api.model.TrashActionResponse> =
            throw NotImplementedError()
        override suspend fun purgeTrashFile(fileId: Int): Response<com.photovault.data.api.model.TrashActionResponse> =
            throw NotImplementedError()
    }

    /** [UploadRecordDao] stub — not used by checkDuplicate. */
    private class FakeUploadRecordDao : UploadRecordDao {
        override suspend fun insertOrUpdate(record: UploadRecord) = throw NotImplementedError()
        override suspend fun getByFileUri(fileUri: String): UploadRecord? = throw NotImplementedError()
        override suspend fun getBySessionId(sessionId: String): UploadRecord? = throw NotImplementedError()
        override suspend fun deleteByFileUri(fileUri: String) = throw NotImplementedError()
        override suspend fun updateProgress(fileUri: String, chunkIndex: Int, updatedAt: Long) = throw NotImplementedError()
        override suspend fun deleteExpired(expiryTime: Long) = throw NotImplementedError()
        override suspend fun deleteByFolderUri(folderUri: String) = throw NotImplementedError()
        override suspend fun getAll(): List<UploadRecord> = throw NotImplementedError()
    }
}
