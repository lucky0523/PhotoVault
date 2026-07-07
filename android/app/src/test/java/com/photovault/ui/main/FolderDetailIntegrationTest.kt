package com.photovault.ui.main

import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import com.photovault.data.local.AppDatabase
import com.photovault.data.local.dao.PhotoStatusDao
import com.photovault.data.local.entity.PhotoStatus
import com.photovault.data.local.entity.PhotoStatusValue
import com.photovault.service.BackupQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboCursor

/**
 * Integration tests for the `rebackup-status-refresh` bugfix spec (task 4).
 *
 * Feature: rebackup-status-refresh
 *
 * These exercise the whole fixed pipeline end-to-end — the real
 * [FolderDetailViewModel] driving its `images` StateFlow off a real in-memory
 * Room `photo_status` table ([Room.inMemoryDatabaseBuilder]) plus an injectable
 * MediaStore (Robolectric [RoboCursor] + ShadowContentResolver). Unlike the
 * unit / property tests (which pin down single behaviors), these cover the full
 * user journeys from the design's "Integration Tests" section:
 *
 * 1. **全流程 (end-to-end)** — enter the detail page, trigger `rebackup` on a
 *    `trashed` photo, then simulate the upload succeeding on the server by
 *    calling [PhotoStatusDao.markActive] (mimicking `StatusSyncManager.markActive`).
 *    The ViewModel-exposed `images` item's badge / opacity / filter-group must
 *    converge to "已备份" WITHOUT leaving the page. Note `rebackup()` itself only
 *    enqueues + starts the foreground service and does NOT write the table, so
 *    before `markActive` the photo must still read as trashed (2.4 — no premature
 *    "backed up").
 * 2. **生命周期 (lifecycle)** — after load, a table change happens while the page
 *    is (conceptually) backgrounded; calling [FolderDetailViewModel.reloadStatuses]
 *    (the ON_RESUME path) re-aligns `images` to the table (2.6).
 * 3. **交互保持 (interaction preserved)** — `active` / 未备份 photos never satisfy
 *    the long-press "重新备份" dialog condition (`isTrashed || isPurged`) (3.5),
 *    and the by-status filter partitions the list correctly (3.3).
 *
 * ## Test seam notes
 * - `mimeType = "image/png"` keeps [MotionPhotoDetector] a no-op (non-JPEG ⇒
 *   returns false without I/O). In Android unit tests `android.os.Build.MODEL`
 *   is null, matching existing conventions.
 * - [RoboCursor] is stateful/position-exhausting: a scan advances it and leaves
 *   it empty for any later scan. A fresh cursor is installed per scan via
 *   [installMediaStoreCursor]. The tests here load once (populating `_rawImages`)
 *   and then only mutate the `photo_status` table, so no rescan occurs — but the
 *   helper still installs a fresh cursor for each ViewModel to stay robust.
 * - A hot subscriber ([keepImagesHot]) is kept on `images` so the
 *   `stateIn(WhileSubscribed)` Flow stays active for the whole journey.
 *
 * Validates: Requirements 2.1, 2.2, 2.3, 2.6, 3.3, 3.5
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class FolderDetailIntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PhotoStatusDao

    private val folderUri =
        "content://com.android.externalstorage.documents/tree/primary:DCIM/Test"

    private fun uriFor(id: Long): String = "content://media/external/images/media/$id"

    // --- Rendered-state mirrors of FolderDetailScreen -----------------------

    /** Mirrors the four-state badge in `StatusBadge` (FolderDetailScreen). */
    private enum class RenderedBadge { NONE, BACKED_UP, TRASHED, PURGED }

    private fun badgeOf(img: FolderImage): RenderedBadge = when {
        img.isBackedUp -> RenderedBadge.BACKED_UP
        img.isTrashed -> RenderedBadge.TRASHED
        img.isPurged -> RenderedBadge.PURGED
        else -> RenderedBadge.NONE
    }

    /** Mirrors `val alpha = if (isTrashed || isPurged) 0.4f else 1f` in ImageThumbnailItem. */
    private fun opacityOf(img: FolderImage): Float =
        if (img.isTrashed || img.isPurged) 0.4f else 1f

    /** Mirrors `PhotoFilter` + the `filteredImages` when-block in FolderDetailScreen. */
    private enum class Filter { ALL, BACKED_UP, TRASHED, PURGED }

    private fun filterBy(images: List<FolderImage>, filter: Filter): List<FolderImage> =
        when (filter) {
            Filter.ALL -> images
            Filter.BACKED_UP -> images.filter { it.isBackedUp }
            Filter.TRASHED -> images.filter { it.isTrashed }
            Filter.PURGED -> images.filter { it.isPurged }
        }

    /** Mirrors the long-press dialog condition in FolderDetailScreen. */
    private fun canTriggerRebackupDialog(img: FolderImage): Boolean =
        img.isTrashed || img.isPurged

    // --- Fixtures ------------------------------------------------------------

    @Before
    fun setUp() {
        // viewModelScope dispatches on Dispatchers.Main.immediate; route it to a
        // synchronous dispatcher so loadImages / combine run deterministically.
        Dispatchers.setMain(Dispatchers.Unconfined)

        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.photoStatusDao()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    /**
     * Installs a FRESH MediaStore cursor with one Images row per id in
     * `1..count`. Fresh per scan because a [RoboCursor] is stateful/exhausting.
     * The Video collection is left unset (⇒ empty).
     */
    private fun installMediaStoreCursor(count: Int) {
        val context = RuntimeEnvironment.getApplication()
        val cursor = RoboCursor()
        cursor.setColumnNames(
            listOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.RELATIVE_PATH
            )
        )
        cursor.setResults(
            (1..count).map { id ->
                val idL = id.toLong()
                arrayOf<Any?>(idL, "photo$idL.png", 1_000L, idL, "image/png", "DCIM/Test/")
            }.toTypedArray()
        )
        shadowOf(context.contentResolver)
            .setCursor(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor)
    }

    private fun newViewModel(backupQueue: BackupQueue = BackupQueue()): FolderDetailViewModel =
        FolderDetailViewModel(
            context = RuntimeEnvironment.getApplication(),
            photoStatusDao = dao,
            backupQueue = backupQueue
        )

    /** Keeps `images` hot for the whole journey (stateIn WhileSubscribed). */
    private fun CoroutineScope.keepImagesHot(vm: FolderDetailViewModel): Job =
        launch { vm.images.collect { } }

    private suspend fun FolderDetailViewModel.await(
        predicate: (List<FolderImage>) -> Boolean
    ): List<FolderImage> = withTimeout(5_000) { images.first(predicate) }

    // --- 1. 全流程 (end-to-end) ---------------------------------------------

    /**
     * End-to-end: 进入详情页 → 对 trashed 照片 rebackup → 模拟上传成功 markActive →
     * images 收敛为「已备份」（无需离开页面）。
     *
     * Asserts the full badge / opacity / filter-group refresh, and that
     * `rebackup()` alone does NOT mark the photo as backed up (2.4).
     *
     * Validates: Requirements 2.1, 2.2, 2.3
     */
    @Test
    fun `rebackup then successful upload refreshes item to backed up in place`() {
        runBlocking {
            val photoUri = uriFor(1L)
            installMediaStoreCursor(count = 1)
            dao.upsert(PhotoStatus(fileUri = photoUri, fileHash = "hash-1", status = PhotoStatusValue.TRASHED))

            val backupQueue = BackupQueue()
            val vm = newViewModel(backupQueue)
            val hot = keepImagesHot(vm)
            try {
                vm.loadImages(folderUri)

                // Enter detail page: initial load shows the trashed photo.
                val loaded = vm.await { it.isNotEmpty() }
                val trashedItem = loaded.single()
                assertEquals(PhotoStatusValue.TRASHED, trashedItem.status?.status)
                assertEquals(RenderedBadge.TRASHED, badgeOf(trashedItem))
                assertEquals(0.4f, opacityOf(trashedItem), 0.0001f)
                assertTrue(filterBy(loaded, Filter.TRASHED).any { it.uri.toString() == photoUri })
                assertFalse(filterBy(loaded, Filter.BACKED_UP).any { it.uri.toString() == photoUri })

                // Trigger re-backup: only enqueues + starts the foreground service.
                vm.rebackup(trashedItem, folderUri)

                // The file was queued for re-upload (forceReupload), and the table
                // was NOT written — so the photo must still read as trashed (2.4:
                // no premature "backed up").
                val queued = backupQueue.getAll()
                assertEquals(1, queued.size)
                assertEquals(photoUri, queued.single().uri)
                assertTrue("re-backup must force re-upload", queued.single().forceReupload)

                // While the upload is in flight the photo shows the uploading
                // spinner and is still trashed (NOT prematurely backed up).
                val uploading = vm.await { list ->
                    list.any { it.uri.toString() == photoUri && it.isUploading }
                }
                assertFalse(
                    "photo must NOT be shown as backed up before the upload completes",
                    uploading.single().isBackedUp
                )
                assertTrue("photo must show the uploading spinner", uploading.single().isUploading)
                assertEquals(RenderedBadge.TRASHED, badgeOf(uploading.single()))

                // The upload completes on the server: StatusSyncManager.markActive
                // flips the authoritative table to active.
                dao.markActive(photoUri)

                // Without leaving the page, images converge to 已备份 and the
                // uploading spinner clears.
                val refreshed = vm.await { list ->
                    list.any { it.uri.toString() == photoUri && it.isBackedUp }
                }
                val activeItem = refreshed.single()
                assertEquals(RenderedBadge.BACKED_UP, badgeOf(activeItem))       // 2.1 badge
                assertEquals(1f, opacityOf(activeItem), 0.0001f)                 // 2.2 opacity
                assertFalse("spinner must clear once backed up", activeItem.isUploading)
                assertTrue(                                                      // 2.3 filter group
                    "backed-up filter must now contain the photo",
                    filterBy(refreshed, Filter.BACKED_UP).any { it.uri.toString() == photoUri }
                )
                assertFalse(
                    "trashed filter must no longer contain the photo",
                    filterBy(refreshed, Filter.TRASHED).any { it.uri.toString() == photoUri }
                )
            } finally {
                hot.cancel()
            }
        }
    }

    // --- 2. 生命周期 (lifecycle / ON_RESUME) --------------------------------

    /**
     * Lifecycle: 加载后（照片切走期间）表发生变更 → 调用 reloadStatuses()（ON_RESUME
     * 路径）→ images 兜底对齐到表。
     *
     * Validates: Requirements 2.6
     */
    @Test
    fun `reloadStatuses realigns images to the table after a background change`() {
        runBlocking {
            val photoUri = uriFor(1L)
            installMediaStoreCursor(count = 1)
            dao.upsert(PhotoStatus(fileUri = photoUri, fileHash = "hash-1", status = PhotoStatusValue.PURGED))

            val vm = newViewModel()
            val hot = keepImagesHot(vm)
            try {
                vm.loadImages(folderUri)

                val loaded = vm.await { it.isNotEmpty() }
                assertEquals(PhotoStatusValue.PURGED, loaded.single().status?.status)
                assertEquals(RenderedBadge.PURGED, badgeOf(loaded.single()))

                // Table changes while the page is (conceptually) backgrounded.
                dao.markActive(photoUri)

                // ON_RESUME fallback path.
                vm.reloadStatuses()

                // UI aligns to the table.
                val realigned = vm.await { list ->
                    list.any { it.uri.toString() == photoUri && it.isBackedUp }
                }
                assertEquals(RenderedBadge.BACKED_UP, badgeOf(realigned.single()))
                assertEquals(1f, opacityOf(realigned.single()), 0.0001f)
            } finally {
                hot.cancel()
            }
        }
    }

    // --- 3. 交互保持 (interaction preserved) --------------------------------

    /**
     * Loads a mixed batch (active / 未备份 / trashed / purged) through the real
     * ViewModel and returns the resulting images, ordered by id.
     */
    private fun loadMixed(): List<FolderImage> = runBlocking {
        // ids 1..4 → active, no-record (未备份), trashed, purged
        val statuses = listOf(
            PhotoStatusValue.ACTIVE,
            null,
            PhotoStatusValue.TRASHED,
            PhotoStatusValue.PURGED
        )
        installMediaStoreCursor(count = statuses.size)
        statuses.forEachIndexed { index, status ->
            if (status != null) {
                val id = (index + 1).toLong()
                dao.upsert(PhotoStatus(fileUri = uriFor(id), fileHash = "hash-$id", status = status))
            }
        }
        val vm = newViewModel()
        vm.loadImages(folderUri)
        vm.await { it.size == statuses.size }.sortedBy { it.createdTime }
    }

    /**
     * Interaction preserved — active / 未备份 photos never satisfy the long-press
     * "重新备份" dialog condition; trashed / purged always do.
     *
     * Validates: Requirements 3.5
     */
    @Test
    fun `only trashed or purged photos can open the rebackup dialog`() {
        val images = loadMixed()
        assertEquals(4, images.size)

        val active = images.first { it.uri.toString() == uriFor(1L) }
        val notBackedUp = images.first { it.uri.toString() == uriFor(2L) }
        val trashed = images.first { it.uri.toString() == uriFor(3L) }
        val purged = images.first { it.uri.toString() == uriFor(4L) }

        assertFalse("active must NOT open the dialog", canTriggerRebackupDialog(active))
        assertFalse("未备份 must NOT open the dialog", canTriggerRebackupDialog(notBackedUp))
        assertTrue("trashed must open the dialog", canTriggerRebackupDialog(trashed))
        assertTrue("purged must open the dialog", canTriggerRebackupDialog(purged))
    }

    /**
     * Interaction preserved — the by-status filter partitions the loaded list
     * correctly (全部/已备份/回收站/已删除), disjoint buckets covering the whole list.
     *
     * Validates: Requirements 3.3
     */
    @Test
    fun `status filter switching partitions the loaded images correctly`() {
        val images = loadMixed()

        assertEquals(images, filterBy(images, Filter.ALL))

        assertEquals(
            listOf(uriFor(1L)),
            filterBy(images, Filter.BACKED_UP).map { it.uri.toString() }
        )
        assertEquals(
            listOf(uriFor(3L)),
            filterBy(images, Filter.TRASHED).map { it.uri.toString() }
        )
        assertEquals(
            listOf(uriFor(4L)),
            filterBy(images, Filter.PURGED).map { it.uri.toString() }
        )

        // Disjoint buckets + 未备份 cover the whole list.
        val backed = filterBy(images, Filter.BACKED_UP).size
        val trashed = filterBy(images, Filter.TRASHED).size
        val purged = filterBy(images, Filter.PURGED).size
        val notBackedUp = images.count { it.status == null }
        assertEquals(images.size, backed + trashed + purged + notBackedUp)
    }
}
