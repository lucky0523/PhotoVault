package com.huoyi.photovault.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import com.huoyi.photovault.data.local.AppDatabase
import com.huoyi.photovault.data.local.entity.BackupFolder
import com.huoyi.photovault.data.local.entity.PhotoStatus
import com.huoyi.photovault.data.local.entity.PhotoStatusValue
import com.huoyi.photovault.ui.main.tabs.applyBackedUpCountDelta
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 验收 35 consistency check: the incremental count update done after an upload
 * ([applyBackedUpCountDelta]) must land on the same folder counts as a full
 * recount by [FolderStatusCountsRefresher.refresh] from photo_status.
 *
 * MediaStore is replaced by a fake "media" ContentProvider that returns a fixed
 * set of image ids (videos: none); photo_status and backup_folders live in a
 * Room in-memory database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class FolderStatusCountsConsistencyTest {

    /** Minimal MediaStore stand-in: images 1..4, no videos. */
    class FakeMediaProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?
        ): Cursor {
            val cursor = MatrixCursor(arrayOf(MediaStore.MediaColumns._ID))
            if (uri == MediaStore.Images.Media.EXTERNAL_CONTENT_URI) {
                IMAGE_IDS.forEach { cursor.addRow(arrayOf<Any>(it)) }
            }
            return cursor
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private lateinit var db: AppDatabase
    private lateinit var refresher: FolderStatusCountsRefresher

    private val folderUri =
        "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FTest"

    private fun imageUri(id: Long) = "${MediaStore.Images.Media.EXTERNAL_CONTENT_URI}/$id"

    @Before
    fun setUp() {
        Robolectric.buildContentProvider(FakeMediaProvider::class.java).create(MediaStore.AUTHORITY)
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        refresher = FolderStatusCountsRefresher(context, db.backupFolderDao(), db.photoStatusDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun status(id: Long, value: String) =
        PhotoStatus(fileUri = imageUri(id), fileHash = "h$id", status = value, fileSize = 100L)

    private suspend fun folder(): BackupFolder = db.backupFolderDao().getAllOnce().single()

    /** Applies the post-upload incremental update, flips the status, then recounts. */
    private suspend fun uploadAndCompare(id: Long) {
        val dao = db.photoStatusDao()
        val prior = dao.getByFileUri(imageUri(id))?.status
        val incremental = applyBackedUpCountDelta(folder(), prior)
        db.backupFolderDao().update(incremental)

        StatusSyncManager(NoopFileApi, NoopBackupApi, dao)
            .markActive(imageUri(id), "new-$id", 200L)

        refresher.refresh()
        val recounted = folder()
        assertEquals("prior=$prior", recounted, incremental)
    }

    @Test
    fun `incremental update matches full recount for active, null, trashed and purged priors`() = runBlocking {
        val dao = db.photoStatusDao()
        // 1: active (modified, re-uploaded), 2: no status (new), 3: trashed, 4: purged
        dao.upsertAll(
            listOf(
                status(1, PhotoStatusValue.ACTIVE),
                status(3, PhotoStatusValue.TRASHED),
                status(4, PhotoStatusValue.PURGED)
            )
        )
        db.backupFolderDao().insert(BackupFolder(folderUri = folderUri, folderName = "Test"))
        refresher.refresh()
        folder().let {
            assertEquals(4, it.totalImages)
            assertEquals(1, it.backedUpImages)
            assertEquals(1, it.trashedImages)
            assertEquals(1, it.purgedImages)
        }

        uploadAndCompare(1) // ACTIVE: unchanged
        uploadAndCompare(2) // null: +1
        uploadAndCompare(3) // trashed: moves to backed up
        uploadAndCompare(4) // purged: moves to backed up

        assertEquals(4, folder().backedUpImages)
    }

    private object NoopBackupApi : com.huoyi.photovault.data.api.BackupApi {
        override suspend fun checkDuplicate(request: com.huoyi.photovault.data.api.model.DuplicateCheckRequest) =
            throw NotImplementedError()
        override suspend fun initUpload(request: com.huoyi.photovault.data.api.model.InitUploadRequest) =
            throw NotImplementedError()
        override suspend fun uploadChunk(
            sessionId: okhttp3.RequestBody,
            chunkIndex: okhttp3.RequestBody,
            checksum: okhttp3.RequestBody,
            chunkData: okhttp3.MultipartBody.Part
        ) = throw NotImplementedError()
        override suspend fun completeUpload(request: com.huoyi.photovault.data.api.model.CompleteUploadRequest) =
            throw NotImplementedError()
        override suspend fun getResumeInfo(sessionId: String) = throw NotImplementedError()
    }

    private object NoopFileApi : com.huoyi.photovault.data.api.FileApi {
        override suspend fun getStatusSync() = throw NotImplementedError()
        override suspend fun browseDirectory(path: String, page: Int, pageSize: Int) = throw NotImplementedError()
        override suspend fun listFiles(path: String, page: Int, pageSize: Int) = throw NotImplementedError()
        override suspend fun getThumbnail(fileId: Int) = throw NotImplementedError()
        override suspend fun downloadFile(fileId: Int) = throw NotImplementedError()
        override suspend fun listTrash(page: Int, pageSize: Int) = throw NotImplementedError()
        override suspend fun restoreTrashFile(fileId: Int) = throw NotImplementedError()
        override suspend fun purgeTrashFile(fileId: Int) = throw NotImplementedError()
        override suspend fun moveFileToTrash(fileId: Int) = throw NotImplementedError()
    }

    companion object {
        private val IMAGE_IDS = listOf(1L, 2L, 3L, 4L)
    }
}
