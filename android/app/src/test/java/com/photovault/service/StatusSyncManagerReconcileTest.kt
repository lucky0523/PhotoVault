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
import com.photovault.data.local.dao.PhotoStatusDao
import com.photovault.data.local.entity.PhotoStatus
import com.photovault.data.local.entity.PhotoStatusValue
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

/**
 * Unit tests for [StatusSyncManager]'s restore-reconciliation path.
 *
 * Covers the "recover from recycle bin on the server" flow: a local trashed/purged
 * record whose hash is no longer reported by status-sync is flipped back to active
 * only when `POST /backup/check` explicitly confirms the file is active. Any
 * ambiguity (still trashed/purged, not_found, or a failed check) must leave the
 * local record untouched so a later sync can retry.
 */
class StatusSyncManagerReconcileTest {

    private fun manager(
        statusSyncItems: List<StatusSyncItem>,
        syncSuccessful: Boolean = true,
        dao: FakePhotoStatusDao,
        checkResponder: (String) -> Response<DuplicateCheckResponse>
    ): StatusSyncManager {
        val fileApi = FakeFileApi(statusSyncItems, syncSuccessful)
        val backupApi = FakeBackupApi(checkResponder)
        return StatusSyncManager(fileApi, backupApi, dao)
    }

    private fun trashedRecord(uri: String, hash: String) = PhotoStatus(
        fileUri = uri,
        fileHash = hash,
        status = PhotoStatusValue.TRASHED,
        deletedAt = 1_000L,
        expiresAt = 2_000L
    )

    @Test
    fun restoredFile_confirmedActive_isFlippedToActive() = runTest {
        val dao = FakePhotoStatusDao(mutableListOf(trashedRecord("uri://a", "hashA")))
        // status-sync no longer reports hashA (restored) → candidate.
        val mgr = manager(statusSyncItems = emptyList(), dao = dao) {
            successCheck(isDuplicate = true, status = PhotoStatusValue.ACTIVE)
        }

        mgr.syncStatus()

        assertEquals(PhotoStatusValue.ACTIVE, dao.get("uri://a")!!.status)
    }

    @Test
    fun candidateStillTrashedOnServer_isNotFlipped() = runTest {
        val dao = FakePhotoStatusDao(mutableListOf(trashedRecord("uri://a", "hashA")))
        val mgr = manager(statusSyncItems = emptyList(), dao = dao) {
            successCheck(isDuplicate = false, status = PhotoStatusValue.TRASHED)
        }

        mgr.syncStatus()

        assertEquals(PhotoStatusValue.TRASHED, dao.get("uri://a")!!.status)
    }

    @Test
    fun candidateCheckFails_isNotFlipped() = runTest {
        val dao = FakePhotoStatusDao(mutableListOf(trashedRecord("uri://a", "hashA")))
        val mgr = manager(statusSyncItems = emptyList(), dao = dao) {
            Response.error(500, "".toResponseBody("application/json".toMediaTypeOrNull()))
        }

        mgr.syncStatus()

        assertEquals(PhotoStatusValue.TRASHED, dao.get("uri://a")!!.status)
    }

    @Test
    fun candidateNotFoundOnServer_isNotFlipped() = runTest {
        val dao = FakePhotoStatusDao(mutableListOf(trashedRecord("uri://a", "hashA")))
        val mgr = manager(statusSyncItems = emptyList(), dao = dao) {
            successCheck(isDuplicate = false, status = "not_found")
        }

        mgr.syncStatus()

        assertEquals(PhotoStatusValue.TRASHED, dao.get("uri://a")!!.status)
    }

    @Test
    fun hashStillReportedByServer_isNotACandidate_stays() = runTest {
        val dao = FakePhotoStatusDao(mutableListOf(trashedRecord("uri://a", "hashA")))
        // Server still reports hashA as trashed → not a restore candidate; /backup/check
        // must not even be consulted. If it is, we'd (wrongly) flip to active and fail.
        val mgr = manager(
            statusSyncItems = listOf(
                StatusSyncItem(fileHash = "hashA", status = PhotoStatusValue.TRASHED)
            ),
            dao = dao
        ) { successCheck(isDuplicate = true, status = PhotoStatusValue.ACTIVE) }

        mgr.syncStatus()

        assertEquals(PhotoStatusValue.TRASHED, dao.get("uri://a")!!.status)
    }

    @Test
    fun syncStatusIfStale_skipsWithinThrottleWindow() = runTest {
        val dao = FakePhotoStatusDao(mutableListOf())
        val fileApi = FakeFileApi(emptyList(), successful = true)
        var checkCalls = 0
        val backupApi = FakeBackupApi { checkCalls++; successCheck(true, PhotoStatusValue.ACTIVE) }
        val mgr = StatusSyncManager(fileApi, backupApi, dao)

        // First call runs (returns >= 0), second within the window is throttled.
        val first = mgr.syncStatusIfStale(minIntervalMs = 60_000L)
        val second = mgr.syncStatusIfStale(minIntervalMs = 60_000L)

        assertEquals(0, first) // ran: no items, no reactivations
        assertEquals(StatusSyncManager.RESULT_SKIPPED_THROTTLED, second)
        assertEquals(1, fileApi.getStatusSyncCalls) // network hit only once
    }

    @Test
    fun syncStatusIfStale_runsAgainAfterWindowElapses() = runTest {
        val dao = FakePhotoStatusDao(mutableListOf())
        val fileApi = FakeFileApi(emptyList(), successful = true)
        val backupApi = FakeBackupApi { successCheck(true, PhotoStatusValue.ACTIVE) }
        val mgr = StatusSyncManager(fileApi, backupApi, dao)

        // minInterval 0 → never throttled: both calls run.
        mgr.syncStatusIfStale(minIntervalMs = 0L)
        mgr.syncStatusIfStale(minIntervalMs = 0L)

        assertEquals(2, fileApi.getStatusSyncCalls)
    }

    @Test
    fun multipleLocalRecordsSameHash_allFlipped() = runTest {
        val dao = FakePhotoStatusDao(
            mutableListOf(
                trashedRecord("uri://a", "hashA"),
                trashedRecord("uri://b", "hashA")
            )
        )
        val mgr = manager(statusSyncItems = emptyList(), dao = dao) {
            successCheck(isDuplicate = true, status = PhotoStatusValue.ACTIVE)
        }

        mgr.syncStatus()

        assertEquals(PhotoStatusValue.ACTIVE, dao.get("uri://a")!!.status)
        assertEquals(PhotoStatusValue.ACTIVE, dao.get("uri://b")!!.status)
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

    private class FakeFileApi(
        private val items: List<StatusSyncItem>,
        private val successful: Boolean
    ) : FileApi {
        var getStatusSyncCalls = 0
            private set

        override suspend fun getStatusSync(): Response<StatusSyncResponse> {
            getStatusSyncCalls++
            return if (successful) Response.success(StatusSyncResponse(items))
            else Response.error(500, "".toResponseBody("application/json".toMediaTypeOrNull()))
        }

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
}
