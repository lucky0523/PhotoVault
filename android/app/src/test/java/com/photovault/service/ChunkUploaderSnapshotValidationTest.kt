package com.photovault.service

import android.net.Uri
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
import com.photovault.data.api.model.StoragePolicyConfig
import com.photovault.data.api.model.UploadProgress
import com.photovault.data.api.model.UploadResult
import com.photovault.data.local.dao.PhotoStatusDao
import com.photovault.data.local.dao.UploadRecordDao
import com.photovault.data.local.entity.PhotoStatus
import com.photovault.data.local.entity.PhotoStatusValue
import com.photovault.data.local.entity.UploadRecord
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.ByteArrayInputStream
import java.util.function.Supplier

/**
 * Upload-flow tests for [ChunkUploader.uploadFile]'s snapshot-validation seam
 * (skip-incomplete-media-backup, R3.4 / R3.5 / R3.6).
 *
 * These verify the observable contract added by task 6.2:
 *  - A structurally-complete snapshot whose size matches MediaStore.SIZE and has
 *    no trailing zero padding proceeds through the existing flow and COMPLETES
 *    the upload (behavior unchanged, R3.6).
 *  - A corrupted snapshot (large trailing-zero run, or a size mismatch vs
 *    MediaStore.SIZE) makes uploadFile return `Failed(shouldRetry = true)` and
 *    NEVER initiates the completion request (R3.4 keep-record-for-retry / R3.5).
 *
 * ## Test seam notes
 * `uploadFile` reads the source URI multiple times through
 * [MediaBytesReader.openOriginal] → `ContentResolver.openInputStream`: once for
 * the pre-check hash, once to stage the snapshot, and once more for EXIF
 * extraction when a new session is initialized. A single registered stream would
 * be consumed after the first read, so the source bytes are registered via
 * `registerInputStreamSupplier`, handing out a fresh [ByteArrayInputStream] on
 * every open. The test runs on API 28 so [MediaBytesReader] takes the plain-open
 * path (no `setRequireOriginal`), keeping the URI stable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ChunkUploaderSnapshotValidationTest {

    private val uri: Uri = Uri.parse("content://media/external/images/media/77")

    private val storagePolicy = StoragePolicyConfig(
        useCustomPath = false,
        customPath = null,
        useYearMonthLayer = false
    )

    // --- valid file: proceeds through the flow and completes (R3.6) ------------

    @Test
    fun validSnapshot_completesUpload() = runTest {
        val bytes = validJpeg()
        registerSource(bytes)

        val api = FakeBackupApi()
        val uploader = uploader(api)

        val result = uploader.uploadFile(
            context = RuntimeEnvironment.getApplication(),
            fileInfo = fileInfo(fileSize = bytes.size.toLong()),
            storagePolicy = storagePolicy,
            onProgress = {}
        )

        assertTrue(
            "a valid snapshot must complete the upload, got $result",
            result is UploadResult.Success
        )
        assertTrue("completeUpload must be called for a valid file", api.completeCalled)
        assertTrue("initUpload must be reached for a valid file", api.initCalled)
    }

    // --- corrupted (trailing zero padding): Failed(retry) + no complete (R3.4/3.5)

    @Test
    fun trailingZeroPaddedSnapshot_failsRetryable_andDoesNotComplete() = runTest {
        // A valid JPEG followed by a large 0x00 run (camera pre-allocated but not
        // fully written — the real MVIMG corruption scenario). fileSize is set to
        // the padded length so the size check passes and the trailing-zero rule
        // is what trips.
        val padded = validJpeg() + ByteArray(128 * 1024) // 128KB >= 64KB threshold
        registerSource(padded)

        val api = FakeBackupApi()
        val uploader = uploader(api)

        val result = uploader.uploadFile(
            context = RuntimeEnvironment.getApplication(),
            fileInfo = fileInfo(fileSize = padded.size.toLong()),
            storagePolicy = storagePolicy,
            onProgress = {}
        )

        assertTrue(
            "a trailing-zero-padded snapshot must fail, got $result",
            result is UploadResult.Failed
        )
        assertTrue(
            "the failure must be retryable so the file is picked up again later (R3.4)",
            (result as UploadResult.Failed).shouldRetry
        )
        assertFalse("completeUpload must NOT be called for a corrupt snapshot (R3.5)", api.completeCalled)
        assertFalse("initUpload must NOT be reached for a corrupt snapshot", api.initCalled)
    }

    // --- corrupted (size mismatch vs MediaStore.SIZE): Failed(retry) + no complete

    @Test
    fun sizeMismatchSnapshot_failsRetryable_andDoesNotComplete() = runTest {
        val bytes = validJpeg()
        registerSource(bytes)

        val api = FakeBackupApi()
        val uploader = uploader(api)

        // MediaStore.SIZE (fileInfo.fileSize) deliberately differs from the actual
        // staged snapshot bytes by 1 byte → strict-equality size check fails (R3.1).
        val result = uploader.uploadFile(
            context = RuntimeEnvironment.getApplication(),
            fileInfo = fileInfo(fileSize = bytes.size.toLong() + 1),
            storagePolicy = storagePolicy,
            onProgress = {}
        )

        assertTrue(
            "a size-mismatched snapshot must fail, got $result",
            result is UploadResult.Failed
        )
        assertTrue(
            "the failure must be retryable (R3.4)",
            (result as UploadResult.Failed).shouldRetry
        )
        assertFalse("completeUpload must NOT be called on size mismatch", api.completeCalled)
        assertFalse("initUpload must NOT be reached on size mismatch", api.initCalled)
    }

    // --- helpers ---------------------------------------------------------------

    /** Registers [bytes] as the source URI content, fresh on every open. */
    private fun registerSource(bytes: ByteArray) {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        shadowOf(resolver).registerInputStreamSupplier(uri) {
            ByteArrayInputStream(bytes)
        }
    }

    /**
     * A minimal but structurally-complete JPEG: SOI (`FF D8`), a small non-zero
     * payload, and the EOI marker (`FF D9`). No large trailing-zero run.
     */
    private fun validJpeg(): ByteArray {
        val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val payload = ByteArray(4096) { (((it % 250) + 1) and 0xFF).toByte() } // all non-zero
        val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        return soi + payload + eoi
    }

    private fun fileInfo(fileSize: Long) = FileInfo(
        uri = uri.toString(),
        fileName = "photo.jpg",
        fileSize = fileSize,
        createdTime = 0L,
        mimeType = "image/jpeg",
        folderUri = "tree://folder",
        forceReupload = false
    )

    private fun uploader(api: FakeBackupApi): ChunkUploader {
        val statusSyncManager = StatusSyncManager(FakeFileApi(), api, FakePhotoStatusDao())
        return ChunkUploader(
            backupApi = api,
            uploadRecordDao = FakeUploadRecordDao(),
            fileHasher = FileHasher(MediaBytesReader()),
            statusSyncManager = statusSyncManager,
            mediaBytesReader = MediaBytesReader()
        )
    }

    /**
     * [BackupApi] that lets the happy path flow all the way to completeUpload,
     * recording which server steps were reached so tests can assert the
     * validation seam short-circuits corrupt files before init/complete.
     */
    private class FakeBackupApi : BackupApi {
        var initCalled = false
        var completeCalled = false

        override suspend fun checkDuplicate(request: DuplicateCheckRequest): Response<DuplicateCheckResponse> =
            // not_found → proceed to snapshot + validation
            Response.success(
                DuplicateCheckResponse(isDuplicate = false, fileId = null, status = "not_found")
            )

        override suspend fun initUpload(request: InitUploadRequest): Response<InitUploadResponse> {
            initCalled = true
            return Response.success(
                InitUploadResponse(sessionId = "session-1", totalChunks = 1, chunkSize = ChunkUploader.CHUNK_SIZE)
            )
        }

        override suspend fun uploadChunk(
            sessionId: RequestBody,
            chunkIndex: RequestBody,
            checksum: RequestBody,
            chunkData: MultipartBody.Part
        ): Response<ChunkUploadResponse> =
            Response.success(
                ChunkUploadResponse(chunkIndex = 0, received = true, checksumValid = true)
            )

        override suspend fun completeUpload(request: CompleteUploadRequest): Response<CompleteUploadResponse> {
            completeCalled = true
            return Response.success(
                CompleteUploadResponse(
                    success = true,
                    fileId = "file-1",
                    integrityValid = true,
                    storedPath = "/stored/photo.jpg"
                )
            )
        }

        override suspend fun getResumeInfo(sessionId: String): Response<ResumeInfoResponse> =
            throw NotImplementedError()
    }

    /** [FileApi] stub — status sync isn't exercised by uploadFile. */
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
    }

    /** In-memory [UploadRecordDao] — no existing record → always a new session. */
    private class FakeUploadRecordDao : UploadRecordDao {
        override suspend fun insertOrUpdate(record: UploadRecord) {}
        override suspend fun getByFileUri(fileUri: String): UploadRecord? = null
        override suspend fun getBySessionId(sessionId: String): UploadRecord? = null
        override suspend fun deleteByFileUri(fileUri: String) {}
        override suspend fun updateProgress(fileUri: String, chunkIndex: Int, updatedAt: Long) {}
        override suspend fun deleteExpired(expiryTime: Long) {}
        override suspend fun getAll(): List<UploadRecord> = emptyList()
    }

    /** In-memory [PhotoStatusDao] — only markActive is exercised on success. */
    private class FakePhotoStatusDao : PhotoStatusDao {
        private val store = mutableListOf<PhotoStatus>()

        override suspend fun upsert(status: PhotoStatus) {
            store.removeAll { it.fileUri == status.fileUri }
            store.add(status)
        }
        override suspend fun upsertAll(statuses: List<PhotoStatus>) = statuses.forEach { upsert(it) }
        override suspend fun getByFileUri(fileUri: String): PhotoStatus? = store.find { it.fileUri == fileUri }
        override suspend fun getByFileHash(fileHash: String): List<PhotoStatus> = store.filter { it.fileHash == fileHash }
        override suspend fun getByStatus(status: String): List<PhotoStatus> = store.filter { it.status == status }
        override suspend fun getNonActive(): List<PhotoStatus> = store.filter { it.status != PhotoStatusValue.ACTIVE }
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
                if (it.fileHash == fileHash) it.copy(status = status, deletedAt = deletedAt, expiresAt = expiresAt, updatedAt = updatedAt) else it
            }
        }
        override suspend fun markActive(fileUri: String, updatedAt: Long) {
            store.replaceAll {
                if (it.fileUri == fileUri) it.copy(status = PhotoStatusValue.ACTIVE, deletedAt = null, expiresAt = null, updatedAt = updatedAt) else it
            }
        }
        override suspend fun delete(fileUri: String) { store.removeAll { it.fileUri == fileUri } }
    }
}
