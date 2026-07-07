package com.photovault.ui.main

import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import com.photovault.data.local.AppDatabase
import com.photovault.data.local.dao.PhotoStatusDao
import com.photovault.data.local.entity.PhotoStatus
import com.photovault.data.local.entity.PhotoStatusValue
import com.photovault.service.BackupQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboCursor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit test for the reactive `combine` in [FolderDetailViewModel] (task 3.2 of
 * the `rebackup-status-refresh` bugfix spec).
 *
 * ## What this proves
 * 1. **Reactive recompute** — after the MediaStore scan is cached in `_rawImages`
 *    and the `photo_status` table changes ([PhotoStatusDao.markActive]), the
 *    ViewModel-exposed `images` recomputes and converges to the table (the photo
 *    reads as backed up) WITHOUT a manual re-query.
 * 2. **MediaStore scanned only once** — the `combine` association is a pure O(n)
 *    mapping: a `photo_status` change must NOT re-run `queryMediaStoreImages`
 *    (nor [MotionPhotoDetector]).
 *
 * ## How "scanned only once" is verified (robust proxy)
 * `queryMediaStoreImages` is private, so we count MediaStore query invocations at
 * the seam instead. Robolectric's `ShadowContentResolver.query` calls
 * [RoboCursor.setQuery] on the injected cursor for every `contentResolver.query`
 * against that collection. We subclass [RoboCursor] to increment a counter in
 * `setQuery`, inject it only for the Images collection (the Video collection is
 * left unset ⇒ returns null ⇒ never counted), and assert the counter stays at 1
 * across the initial load AND the subsequent `markActive` recompute. This is a
 * direct, implementation-independent count of "how many times MediaStore was
 * scanned".
 *
 * ## Test seam
 * Real in-memory Room database ([Room.inMemoryDatabaseBuilder]) as the
 * authoritative `photo_status` table so `markActive` genuinely mutates it and the
 * DAO's `observeAll()` Flow re-emits. `mimeType = "image/png"` keeps
 * [MotionPhotoDetector] a no-op (non-JPEG ⇒ returns false without I/O).
 *
 * Validates: Requirements 2.1, 2.2, 2.3
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class FolderDetailReactiveMergeTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PhotoStatusDao

    private val folderUri =
        "content://com.android.externalstorage.documents/tree/primary:DCIM/Test"
    private val photoUri = "content://media/external/images/media/1"

    /**
     * A [RoboCursor] that counts how many times it is used to answer a
     * `contentResolver.query` (Robolectric calls [setQuery] on each query).
     */
    private class CountingRoboCursor : RoboCursor() {
        val queryCount = AtomicInteger(0)
        override fun setQuery(
            uri: Uri?,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?
        ) {
            queryCount.incrementAndGet()
            super.setQuery(uri, projection, selection, selectionArgs, sortOrder)
        }
    }

    private lateinit var imagesCursor: CountingRoboCursor

    @Before
    fun setUp() {
        // viewModelScope dispatches on Dispatchers.Main.immediate; route it to a
        // synchronous dispatcher so loadImages runs deterministically.
        Dispatchers.setMain(Dispatchers.Unconfined)

        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.photoStatusDao()

        // Inject a single MediaStore image into the Images collection the
        // ViewModel queries, via a counting cursor. The Video collection is left
        // unset (⇒ null ⇒ not counted).
        imagesCursor = CountingRoboCursor().apply {
            setColumnNames(
                listOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.RELATIVE_PATH
                )
            )
            setResults(
                arrayOf(
                    arrayOf<Any?>(1L, "photo.png", 1_000L, 1_000L, "image/png", "DCIM/Test/")
                )
            )
        }
        shadowOf(context.contentResolver)
            .setCursor(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imagesCursor)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel(): FolderDetailViewModel =
        FolderDetailViewModel(
            context = RuntimeEnvironment.getApplication(),
            photoStatusDao = dao,
            backupQueue = BackupQueue()
        )

    /**
     * combine recomputes `images` on a `photo_status` change AND does not re-run
     * the MediaStore query (scan counted exactly once).
     *
     * Validates: Requirements 2.1, 2.2, 2.3
     */
    @Test
    fun `combine recomputes on status change without re-scanning MediaStore`() {
        runBlocking {
            dao.upsert(
                PhotoStatus(fileUri = photoUri, fileHash = "hash-1", status = PhotoStatusValue.TRASHED)
            )

            val vm = newViewModel()
            vm.loadImages(folderUri)

            // Initial (stale) load: reflects the seeded trashed status.
            val loaded = withTimeout(5_000) { vm.images.first { it.isNotEmpty() } }
            assertEquals(1, loaded.size)
            assertEquals(PhotoStatusValue.TRASHED, loaded.single().status?.status)

            val scansAfterLoad = imagesCursor.queryCount.get()
            assertEquals(
                "MediaStore should be scanned exactly once on first load",
                1,
                scansAfterLoad
            )

            // The re-backup completed on the server: table is now authoritative-active.
            dao.markActive(photoUri)

            // Reactive combine must recompute images and converge to the table.
            val converged = withTimeout(5_000) {
                vm.images.first { list ->
                    list.any { it.uri.toString() == photoUri && it.isBackedUp }
                }
            }
            assertTrue(
                "images did not converge to active after markActive",
                converged.single().isBackedUp
            )

            // The status change must NOT have re-run the heavy MediaStore scan:
            // the association step is a pure O(n) mapping.
            assertEquals(
                "MediaStore must NOT be re-scanned when only photo_status changes; " +
                    "the combine association must be a pure mapping",
                1,
                imagesCursor.queryCount.get()
            )
        }
    }
}
