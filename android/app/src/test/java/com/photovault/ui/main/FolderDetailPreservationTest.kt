package com.photovault.ui.main

import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import com.photovault.data.local.AppDatabase
import com.photovault.data.local.dao.PhotoStatusDao
import com.photovault.data.local.entity.PhotoStatus
import com.photovault.data.local.entity.PhotoStatusValue
import com.photovault.service.BackupQueue
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * Preservation property tests for the `rebackup-status-refresh` bugfix spec.
 *
 * Feature: rebackup-status-refresh, Property 2: Preservation — 一致状态下行为不变
 *
 * ## Purpose (observation-first)
 * These tests lock in the behavior the fix MUST NOT change. They are written
 * against the UNFIXED code and are EXPECTED TO PASS. After the A+B fix
 * (DAO `observeAll()` + reactive `combine` + `ON_RESUME` reload), rerunning the
 * exact same tests must still PASS, proving no regression on non-bug inputs
 * (display state already consistent with the `photo_status` table).
 *
 * ## Behaviors pinned down (design.md "Preservation Requirements")
 * 1. 首次加载四态一致 — first load associates each photo with its `photo_status`
 *    row and derives the correct four-state (未备份/已备份/回收站/已删除).  (3.1)
 * 2. 无记录即未备份 — a photo with no `photo_status` row reads as "未备份"
 *    (`status == null`, `isBackedUp`/`isTrashed`/`isPurged` all false).      (3.2)
 * 3. 筛选逻辑不变 — the by-status filter (全部/已备份/回收站/已删除) partitions
 *    the list exactly by the pure `FolderImage` derivations.                (3.3, 3.4)
 * 4. 仅 trashed/purged 可触发对话框 — the long-press "重新备份" dialog condition
 *    (`isTrashed || isPurged`) is false for active / not-backed-up photos.   (3.5)
 *
 * ## Test seam
 * Properties 1–2 drive the real [FolderDetailViewModel] through Robolectric's
 * [RoboCursor] + ShadowContentResolver (injectable MediaStore) and a real
 * in-memory Room database ([Room.inMemoryDatabaseBuilder]) as the authoritative
 * `photo_status` table. Properties 3–4 assert the pure UI derivations that the
 * screen's filter/dialog logic is built from (`FolderImage.isBackedUp` etc.),
 * mirroring the exact predicates in `FolderDetailScreen`.
 *
 * Note: `mimeType = "image/png"` keeps [MotionPhotoDetector] a no-op (non-JPEG ⇒
 * returns false without I/O). In Android unit tests `android.os.Build.MODEL` is
 * null, matching existing conventions.
 *
 * Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class FolderDetailPreservationTest {

    // A SAF tree uri whose relative path resolves to "DCIM/Test"; the injected
    // RoboCursor is returned regardless of the query selection.
    private val folderUri =
        "content://com.android.externalstorage.documents/tree/primary:DCIM/Test"

    /** The content uri the ViewModel builds for a MediaStore image with [id]. */
    private fun uriFor(id: Long): String = "content://media/external/images/media/$id"

    /**
     * Mirrors `PhotoFilter` in FolderDetailScreen. Kept local to the test so the
     * property locks in the screen's exact by-status partitioning without
     * depending on the private composable enum.
     */
    private enum class Filter { ALL, BACKED_UP, TRASHED, PURGED }

    /** Mirrors the `filteredImages` `when` block in FolderDetailScreen. */
    private fun filterBy(images: List<FolderImage>, filter: Filter): List<FolderImage> =
        when (filter) {
            Filter.ALL -> images
            Filter.BACKED_UP -> images.filter { it.isBackedUp }
            Filter.TRASHED -> images.filter { it.isTrashed }
            Filter.PURGED -> images.filter { it.isPurged }
        }

    @Before
    fun setUp() {
        // viewModelScope dispatches on Dispatchers.Main.immediate; route it to a
        // synchronous dispatcher so loadImages runs deterministically.
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Builds a FolderImage the way the ViewModel would, for the pure-derivation
     * properties (3, 4). A null [status] means no `photo_status` row (未备份).
     */
    private fun folderImage(id: Long, status: String?): FolderImage =
        FolderImage(
            uri = Uri.parse(uriFor(id)),
            name = "photo$id.png",
            fileSize = 1_000L,
            createdTime = id,
            mimeType = "image/png",
            status = status?.let { PhotoStatus(fileUri = uriFor(id), fileHash = "hash-$id", status = it) }
        )

    /**
     * First-load observation through the real ViewModel: injects one MediaStore
     * image per entry in [statuses] (id = index+1), seeds the `photo_status`
     * table for the non-null entries, then returns the ViewModel-exposed
     * `images` once the initial load has populated. A fresh in-memory Room db is
     * built per call so property iterations stay isolated.
     */
    private fun loadImagesWith(statuses: List<String?>): List<FolderImage> = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao: PhotoStatusDao = db.photoStatusDao()

        try {
            // Inject one MediaStore Images row per requested photo. RoboCursor
            // returns these regardless of the query selection; the Video
            // collection is left unset (⇒ empty).
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
                statuses.mapIndexed { index, _ ->
                    val id = (index + 1).toLong()
                    arrayOf<Any?>(id, "photo$id.png", 1_000L, id, "image/png", "DCIM/Test/")
                }.toTypedArray()
            )
            shadowOf(context.contentResolver)
                .setCursor(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor)

            // Seed the authoritative photo_status table for entries that have a
            // status; leave null entries with no row (⇒ 未备份).
            statuses.forEachIndexed { index, status ->
                if (status != null) {
                    val id = (index + 1).toLong()
                    dao.upsert(
                        PhotoStatus(fileUri = uriFor(id), fileHash = "hash-$id", status = status)
                    )
                }
            }

            val vm = FolderDetailViewModel(
                context = context,
                photoStatusDao = dao,
                backupQueue = BackupQueue()
            )
            vm.loadImages(folderUri)

            withTimeout(5_000) { vm.images.first { it.size == statuses.size } }
        } finally {
            db.close()
        }
    }

    /** Asserts a single image's status + derived four-state flags match [expected]. */
    private fun assertDerivations(img: FolderImage, expected: String?) {
        assertEquals("status string mismatch for ${img.uri}", expected, img.status?.status)
        when (expected) {
            PhotoStatusValue.ACTIVE -> {
                assertTrue("expected isBackedUp", img.isBackedUp)
                assertFalse(img.isTrashed)
                assertFalse(img.isPurged)
            }
            PhotoStatusValue.TRASHED -> {
                assertTrue("expected isTrashed", img.isTrashed)
                assertFalse(img.isBackedUp)
                assertFalse(img.isPurged)
            }
            PhotoStatusValue.PURGED -> {
                assertTrue("expected isPurged", img.isPurged)
                assertFalse(img.isBackedUp)
                assertFalse(img.isTrashed)
            }
            else -> { // no record ⇒ 未备份
                assertNull("no-record photo must have null status", img.status)
                assertFalse(img.isBackedUp)
                assertFalse(img.isTrashed)
                assertFalse(img.isPurged)
            }
        }
    }

    /**
     * Property — 首次加载四态一致.
     *
     * For any batch of photos with arbitrary `photo_status` values (including
     * "no record"), the first `loadImages` result associates each photo with the
     * correct row and derives the correct four-state.
     *
     * Feature: rebackup-status-refresh, Property 2: Preservation — 一致状态下行为不变
     *
     * Validates: Requirements 3.1, 3.2, 3.4
     */
    @Test
    fun `first load associates each photo with its status`() {
        val statusArb = Arb.of<String?>(
            PhotoStatusValue.ACTIVE,
            PhotoStatusValue.TRASHED,
            PhotoStatusValue.PURGED,
            null
        )
        runBlocking {
            checkAll(25, Arb.list(statusArb, 1..8)) { statuses ->
                val images = loadImagesWith(statuses)
                assertEquals("every MediaStore photo must be present", statuses.size, images.size)
                statuses.forEachIndexed { index, expected ->
                    val id = (index + 1).toLong()
                    val img = images.first { it.uri.toString() == uriFor(id) }
                    assertDerivations(img, expected)
                }
            }
        }
    }

    /**
     * Property — 无记录即未备份.
     *
     * For any batch of photos with NO `photo_status` rows, every photo reads as
     * 未备份 (status null, all derived flags false).
     *
     * Feature: rebackup-status-refresh, Property 2: Preservation — 一致状态下行为不变
     *
     * Validates: Requirements 3.2
     */
    @Test
    fun `photos without a status row read as not backed up`() {
        runBlocking {
            // Generate 1..8 photos, none with a photo_status row.
            checkAll(25, Arb.int(1..8)) { count ->
                val statuses = List<String?>(count) { null }
                val images = loadImagesWith(statuses)
                assertEquals(statuses.size, images.size)
                images.forEach { img ->
                    assertNull("expected 未备份 (null status)", img.status)
                    assertFalse(img.isBackedUp)
                    assertFalse(img.isTrashed)
                    assertFalse(img.isPurged)
                }
            }
        }
    }

    /**
     * Property — 筛选逻辑不变.
     *
     * For any list of images and any filter option, the by-status filter returns
     * exactly the images whose derived state matches the option (and ALL returns
     * everything). This locks in the screen's `filteredImages` partitioning.
     *
     * Feature: rebackup-status-refresh, Property 2: Preservation — 一致状态下行为不变
     *
     * Validates: Requirements 3.3, 3.4
     */
    @Test
    fun `status filter partitions images exactly by derived state`() {
        val statusArb = Arb.of<String?>(
            PhotoStatusValue.ACTIVE,
            PhotoStatusValue.TRASHED,
            PhotoStatusValue.PURGED,
            null
        )
        runBlocking {
            checkAll(100, Arb.list(statusArb, 0..12)) { statuses ->
                val images = statuses.mapIndexed { index, s -> folderImage((index + 1).toLong(), s) }

                // 全部: unchanged list.
                assertEquals(images, filterBy(images, Filter.ALL))

                // 已备份 / 回收站 / 已删除: exactly the matching derivation.
                assertEquals(
                    images.filter { it.status?.status == PhotoStatusValue.ACTIVE },
                    filterBy(images, Filter.BACKED_UP)
                )
                assertEquals(
                    images.filter { it.status?.status == PhotoStatusValue.TRASHED },
                    filterBy(images, Filter.TRASHED)
                )
                assertEquals(
                    images.filter { it.status?.status == PhotoStatusValue.PURGED },
                    filterBy(images, Filter.PURGED)
                )

                // The three status buckets are disjoint and, together with the
                // no-record (未备份) photos, cover the whole list.
                val backed = filterBy(images, Filter.BACKED_UP)
                val trashed = filterBy(images, Filter.TRASHED)
                val purged = filterBy(images, Filter.PURGED)
                val notBackedUp = images.count { it.status == null }
                assertEquals(
                    images.size,
                    backed.size + trashed.size + purged.size + notBackedUp
                )
            }
        }
    }

    /**
     * Property — 仅 trashed/purged 可触发对话框.
     *
     * The long-press "重新备份" dialog condition is `isTrashed || isPurged`.
     * For active / not-backed-up photos it must be false; for trashed/purged it
     * must be true.
     *
     * Feature: rebackup-status-refresh, Property 2: Preservation — 一致状态下行为不变
     *
     * Validates: Requirements 3.5
     */
    @Test
    fun `only trashed or purged photos satisfy the rebackup dialog condition`() {
        val statusArb = Arb.of<String?>(
            PhotoStatusValue.ACTIVE,
            PhotoStatusValue.TRASHED,
            PhotoStatusValue.PURGED,
            null
        )
        runBlocking {
            checkAll(100, statusArb) { status ->
                val img = folderImage(id = 1L, status = status)
                val canTriggerDialog = img.isTrashed || img.isPurged
                when (status) {
                    PhotoStatusValue.TRASHED, PhotoStatusValue.PURGED ->
                        assertTrue("trashed/purged must be able to open the dialog", canTriggerDialog)
                    else -> // active or 未备份
                        assertFalse("active/not-backed-up must NOT open the dialog", canTriggerDialog)
                }
            }
        }
    }
}
