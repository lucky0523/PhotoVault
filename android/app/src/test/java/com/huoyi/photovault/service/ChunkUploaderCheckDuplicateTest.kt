package com.huoyi.photovault.service

import com.huoyi.photovault.data.api.BackupApi
import com.huoyi.photovault.data.api.FileApi
import com.huoyi.photovault.data.api.model.ChunkUploadResponse
import com.huoyi.photovault.data.api.model.CompleteUploadRequest
import com.huoyi.photovault.data.api.model.CompleteUploadResponse
import com.huoyi.photovault.data.api.model.DirectoryListingResponse
import com.huoyi.photovault.data.api.model.DuplicateCheckRequest
import com.huoyi.photovault.data.api.model.DuplicateCheckResponse
import com.huoyi.photovault.data.api.model.InitUploadRequest
import com.huoyi.photovault.data.api.model.InitUploadResponse
import com.huoyi.photovault.data.api.model.ResumeInfoResponse
import com.huoyi.photovault.data.api.model.StatusSyncItem
import com.huoyi.photovault.data.api.model.StatusSyncResponse
import com.huoyi.photovault.data.api.model.UploadResult
import com.huoyi.photovault.data.local.dao.PhotoStatusDao
import com.huoyi.photovault.data.local.dao.UploadRecordDao
import com.huoyi.photovault.data.local.entity.PhotoStatus
import com.huoyi.photovault.data.local.entity.PhotoStatusValue
import com.huoyi.photovault.data.local.entity.UploadRecord
import com.huoyi.photovault.data.api.model.StoragePolicyConfig
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.ByteArrayInputStream

/**
 * Unit tests for [ChunkUploader.checkDuplicate]'s force-reupload branch.
 *
 * When a trashed/purged photo is manually re-backed-up ([FileInfo.forceReupload]
 * = true), the uploader must NOT short-circuit on the server's trashed/purged
 * status. Instead it returns null so the caller proceeds to upload and complete,
 * letting the server reactivate the record. Without the flag, the historical
 * behaviour is preserved: the upload is skipped and the local status is recorded.
 *
 * The `modified*` cases drive the whole [ChunkUploader.uploadFile] flow for an
 * already-ACTIVE local file whose content changed (file versioning, 验收 34/36/37).
 * They need a ContentResolver, hence Robolectric (API 28 so MediaBytesReader
 * takes the plain-open path, as in ChunkUploaderSnapshotValidationTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ChunkUploaderCheckDuplicateTest {

    private fun uploader(
        dao: FakePhotoStatusDao,
        checkResponder: (String) -> Response<DuplicateCheckResponse>
    ): ChunkUploader = uploader(dao, FakeBackupApi(checkResponder))

    private fun uploader(dao: FakePhotoStatusDao, backupApi: FakeBackupApi): ChunkUploader {
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
        assertEquals(PhotoStatusValue.TRASHED, (result as UploadResult.Skipped).reason)
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
        assertEquals(PhotoStatusValue.PURGED, (result as UploadResult.Skipped).reason)
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

    // --- modified active file (file versioning, plan 18) ---------------------

    private val modifiedUri = "content://media/external/images/media/88"
    private val storagePolicy = StoragePolicyConfig(
        useCustomPath = false,
        customPath = null,
        useYearMonthLayer = false
    )

    /** Structurally complete JPEG (SOI, non-zero payload, EOI). */
    private fun jpeg(seed: Int): ByteArray {
        val payload = ByteArray(4096) { (((it + seed) % 250) + 1).toByte() }
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + payload +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    private fun registerSource(bytes: ByteArray) {
        shadowOf(RuntimeEnvironment.getApplication().contentResolver)
            .registerInputStreamSupplier(android.net.Uri.parse(modifiedUri)) { ByteArrayInputStream(bytes) }
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun modifiedFileInfo(size: Long) = FileInfo(
        uri = modifiedUri,
        fileName = "IMG_modified.jpg",
        fileSize = size,
        createdTime = 0L,
        mimeType = "image/jpeg",
        folderUri = "tree://folder"
    )

    /** Local row as it was before the file was modified: ACTIVE with old hash H2 and old size. */
    private fun activeRowH2() = PhotoStatus(
        fileUri = modifiedUri,
        fileHash = "H2-old",
        status = PhotoStatusValue.ACTIVE,
        updatedAt = 1_000L,
        fileSize = 1L
    )

    @Test
    fun notFound_forLocallyActiveFile_proceedsAndLeavesRowUntouched() = runTest {
        val dao = FakePhotoStatusDao(mutableListOf(activeRowH2()))
        val uploader = uploader(dao) { successCheck(isDuplicate = false, status = "not_found") }

        assertNull(uploader.checkDuplicate("H1", modifiedFileInfo(size = 4100L)))
        assertEquals(activeRowH2(), dao.get(modifiedUri))
    }

    /**
     * 验收 34/37: an ACTIVE file recorded with hash H2 is uploaded with new content
     * H1 through check(not_found) → init → chunk → complete. Afterwards the row is
     * still ACTIVE and carries H1 and the enqueue-time MediaStore SIZE.
     */
    @Test
    fun modifiedActiveFile_completes_updatesHashAndSize_staysActive() = runTest {
        val bytes = jpeg(seed = 7)
        registerSource(bytes)
        val dao = FakePhotoStatusDao(mutableListOf(activeRowH2()))
        val api = FakeBackupApi(
            checkResponder = { successCheck(isDuplicate = false, status = "not_found") },
            completeSucceeds = true
        )

        val result = uploader(dao, api).uploadFile(
            RuntimeEnvironment.getApplication(), modifiedFileInfo(bytes.size.toLong()), storagePolicy
        ) {}

        assertTrue("expected Success, got $result", result is UploadResult.Success)
        assertTrue(api.initCalled)
        assertTrue(api.completeCalled)
        val row = dao.get(modifiedUri)!!
        assertEquals(PhotoStatusValue.ACTIVE, row.status)
        assertEquals(sha256(bytes), row.fileHash)
        assertEquals(bytes.size.toLong(), row.fileSize)
    }

    /** init reports an active duplicate (another device uploaded H1): hash and size still updated. */
    @Test
    fun modifiedActiveFile_initDuplicate_updatesHashAndSize() = runTest {
        val bytes = jpeg(seed = 11)
        registerSource(bytes)
        val dao = FakePhotoStatusDao(mutableListOf(activeRowH2()))
        val api = FakeBackupApi(
            checkResponder = { successCheck(isDuplicate = false, status = "not_found") },
            initDuplicate = true
        )

        val result = uploader(dao, api).uploadFile(
            RuntimeEnvironment.getApplication(), modifiedFileInfo(bytes.size.toLong()), storagePolicy
        ) {}

        assertTrue("expected Duplicate, got $result", result is UploadResult.Duplicate)
        assertFalse(api.completeCalled)
        val row = dao.get(modifiedUri)!!
        assertEquals(PhotoStatusValue.ACTIVE, row.status)
        assertEquals(sha256(bytes), row.fileHash)
        assertEquals(bytes.size.toLong(), row.fileSize)
    }

    /** check reports an active duplicate: the enqueue-time SIZE is recorded too. */
    @Test
    fun modifiedActiveFile_checkDuplicate_updatesHashAndSize() = runTest {
        val dao = FakePhotoStatusDao(mutableListOf(activeRowH2()))
        val uploader = uploader(dao) { successCheck(isDuplicate = true, status = PhotoStatusValue.ACTIVE) }

        val result = uploader.checkDuplicate("H1", modifiedFileInfo(size = 4100L))

        assertTrue(result is UploadResult.Duplicate)
        val row = dao.get(modifiedUri)!!
        assertEquals("H1", row.fileHash)
        assertEquals(4100L, row.fileSize)
    }

    /** 验收 36: when complete fails the file stays ACTIVE with its old hash and size. */
    @Test
    fun modifiedActiveFile_completeFails_staysActiveWithOldContent() = runTest {
        val bytes = jpeg(seed = 3)
        registerSource(bytes)
        val dao = FakePhotoStatusDao(mutableListOf(activeRowH2()))
        val api = FakeBackupApi(
            checkResponder = { successCheck(isDuplicate = false, status = "not_found") },
            completeSucceeds = false
        )

        val result = uploader(dao, api).uploadFile(
            RuntimeEnvironment.getApplication(), modifiedFileInfo(bytes.size.toLong()), storagePolicy
        ) {}

        assertTrue("expected Failed, got $result", result is UploadResult.Failed)
        assertTrue(api.completeCalled)
        assertEquals(activeRowH2(), dao.get(modifiedUri))
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

        override suspend fun markActiveWithContent(
            fileUri: String,
            fileHash: String,
            fileSize: Long?,
            updatedAt: Long
        ) {
            store.replaceAll {
                if (it.fileUri == fileUri) {
                    it.copy(
                        status = PhotoStatusValue.ACTIVE,
                        deletedAt = null,
                        expiresAt = null,
                        fileHash = fileHash,
                        fileSize = fileSize ?: it.fileSize,
                        updatedAt = updatedAt
                    )
                } else it
            }
        }

        override suspend fun backfillFileSize(fileUri: String, fileSize: Long) {
            store.replaceAll {
                if (it.fileUri == fileUri && it.fileSize == null) it.copy(fileSize = fileSize) else it
            }
        }

        override suspend fun delete(fileUri: String) {
            store.removeAll { it.fileUri == fileUri }
        }
    }

    /**
     * [BackupApi] whose /backup/check response is supplied per test. init/chunk/
     * complete are only reachable from the full-flow tests: [initDuplicate]
     * makes init report an active duplicate; [completeSucceeds] null means those
     * endpoints are unexpected for the test.
     */
    private class FakeBackupApi(
        private val checkResponder: (String) -> Response<DuplicateCheckResponse>,
        private val completeSucceeds: Boolean? = null,
        private val initDuplicate: Boolean = false
    ) : BackupApi {
        var initCalled = false
        var completeCalled = false

        override suspend fun checkDuplicate(request: DuplicateCheckRequest): Response<DuplicateCheckResponse> =
            checkResponder(request.fileHash)

        override suspend fun initUpload(request: InitUploadRequest): Response<InitUploadResponse> {
            if (completeSucceeds == null && !initDuplicate) throw NotImplementedError()
            initCalled = true
            return if (initDuplicate) {
                Response.success(InitUploadResponse(isDuplicate = true, fileId = 9))
            } else {
                Response.success(
                    InitUploadResponse(sessionId = "session-1", totalChunks = 1, chunkSize = ChunkUploader.CHUNK_SIZE)
                )
            }
        }

        override suspend fun uploadChunk(
            sessionId: RequestBody,
            chunkIndex: RequestBody,
            checksum: RequestBody,
            chunkData: MultipartBody.Part
        ): Response<ChunkUploadResponse> {
            if (completeSucceeds == null) throw NotImplementedError()
            return Response.success(ChunkUploadResponse(chunkIndex = 0, received = true, checksumValid = true))
        }

        override suspend fun completeUpload(request: CompleteUploadRequest): Response<CompleteUploadResponse> {
            val succeeds = completeSucceeds ?: throw NotImplementedError()
            completeCalled = true
            return if (succeeds) {
                Response.success(CompleteUploadResponse(success = true, fileId = 1, storedPath = "/stored/x.jpg"))
            } else {
                Response.error(409, "".toResponseBody("application/json".toMediaTypeOrNull()))
            }
        }

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
        override suspend fun listTrash(page: Int, pageSize: Int): Response<com.huoyi.photovault.data.api.model.TrashListResponse> =
            throw NotImplementedError()
        override suspend fun restoreTrashFile(fileId: Int): Response<com.huoyi.photovault.data.api.model.TrashActionResponse> =
            throw NotImplementedError()
        override suspend fun purgeTrashFile(fileId: Int): Response<com.huoyi.photovault.data.api.model.TrashActionResponse> =
            throw NotImplementedError()
        override suspend fun moveFileToTrash(fileId: Int): Response<com.huoyi.photovault.data.api.model.TrashActionResponse> =
            throw NotImplementedError()
    }

    /** In-memory [UploadRecordDao] stub. Terminal duplicate/skip outcomes delete
     * stale resume rows, so deletion is intentionally a successful no-op here. */
    private class FakeUploadRecordDao : UploadRecordDao {
        // The full-flow tests start from "no resume record" and create one on init.
        override suspend fun insertOrUpdate(record: UploadRecord) = Unit
        override suspend fun getByFileUri(fileUri: String): UploadRecord? = null
        override suspend fun getBySessionId(sessionId: String): UploadRecord? = throw NotImplementedError()
        override suspend fun deleteByFileUri(fileUri: String) = Unit
        override suspend fun updateProgress(fileUri: String, chunkIndex: Int, updatedAt: Long) = Unit
        override suspend fun deleteExpired(expiryTime: Long) = throw NotImplementedError()
        override suspend fun deleteByFolderUri(folderUri: String) = throw NotImplementedError()
        override suspend fun getAll(): List<UploadRecord> = throw NotImplementedError()
        override suspend fun getPausedByAutoOff(): List<UploadRecord> = throw NotImplementedError()
        override suspend fun markAutoOffPaused(fileUri: String, pausedAt: Long, updatedAt: Long) = throw NotImplementedError()
        override suspend fun clearAutoOffPause(fileUri: String, updatedAt: Long) = throw NotImplementedError()
    }
}
