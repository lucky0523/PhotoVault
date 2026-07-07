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
import io.kotest.property.PropertyTesting
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboCursor

/**
 * Bug Condition exploration test for the `rebackup-status-refresh` bugfix spec.
 *
 * Feature: rebackup-status-refresh, Property 1: Bug Condition — 展示状态收敛到 photo_status 表
 *
 * ## What this proves (by counterexample)
 * `FolderDetailViewModel.images` is a one-shot snapshot: [FolderDetailViewModel.loadImages]
 * reads `photoStatusDao.getAll()` once and never observes the `photo_status` table again.
 * So after a re-backup succeeds and flips the record to `active`
 * (`PhotoStatusDao.markActive`), the ViewModel-exposed `images` keep showing the
 * stale `trashed`/`purged` status, and every UI derivation (badge / opacity /
 * filter group, all derived from the pure [FolderImage.status] fields) stays wrong.
 *
 * ## Expected outcome on the UNFIXED code
 * These tests are EXPECTED TO FAIL. The failure IS the proof that the bug exists.
 * After the fix (DAO `observeAll()` Flow + reactive `combine` in the ViewModel),
 * the very same tests must flip to PASS because `images` will converge to the table.
 *
 * ## Test seam
 * MediaStore is injected through Robolectric's [RoboCursor] + ShadowContentResolver
 * so [FolderDetailViewModel.loadImages] returns one controllable photo without a
 * device. The `photo_status` table is a real in-memory Room database
 * ([Room.inMemoryDatabaseBuilder]) — the authoritative single source of truth —
 * so `markActive` genuinely mutates the table.
 *
 * Note: `mimeType = "image/png"` keeps [MotionPhotoDetector] a no-op (non-JPEG ⇒
 * returns false without any I/O). In Android unit tests `android.os.Build.MODEL`
 * is null, matching existing conventions.
 *
 * Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class FolderDetailStatusRefreshTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PhotoStatusDao

    // A SAF tree uri whose relative path resolves to "DCIM/Test"; the injected
    // cursor is returned regardless of the query selection.
    private val folderUri = "content://com.android.externalstorage.documents/tree/primary:DCIM/Test"
    private val photoUri = "content://media/external/images/media/1"

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
    }

    /**
     * Installs a FRESH MediaStore cursor into the Images collection the ViewModel
     * queries. Must be called once per `loadImages`/`reloadStatuses` scan, because
     * a [RoboCursor] is stateful — once its position is advanced by a scan it is
     * exhausted, so a shared cursor would yield empty results on any subsequent
     * scan (e.g. the 2nd `checkAll` iteration). The Video collection is left unset
     * (⇒ empty).
     */
    private fun installFreshMediaStoreCursor() {
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
            arrayOf(
                arrayOf<Any?>(1L, "photo.png", 1_000L, 1_000L, "image/png", "DCIM/Test/")
            )
        )
        shadowOf(context.contentResolver)
            .setCursor(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor)
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
     * Seeds one record with [initialStatus], loads the folder, flips the record
     * to `active` via [PhotoStatusDao.markActive], then waits (with a real-time
     * timeout) for the ViewModel-exposed `images` to converge so the photo reads
     * as backed up. Returns true if it converged.
     *
     * On the UNFIXED code convergence never happens (no reactive observation),
     * so this returns false — the counterexample.
     */
    private fun reloadsAfterMarkActive(initialStatus: String): Boolean = runBlocking {
        // Fresh cursor per invocation so each iteration's loadImages scan reads a
        // non-exhausted MediaStore result (prevents cross-iteration state leakage).
        installFreshMediaStoreCursor()

        dao.upsert(
            PhotoStatus(fileUri = photoUri, fileHash = "hash-1", status = initialStatus)
        )

        val vm = newViewModel()
        vm.loadImages(folderUri)

        // Wait for the initial (stale) load to populate images.
        val loaded = withTimeout(5_000) { vm.images.first { it.isNotEmpty() } }
        // Sanity: initial load reflects the seeded non-active status.
        require(loaded.single().status?.status == initialStatus) {
            "initial load did not reflect seeded status $initialStatus"
        }

        // The re-backup completed on the server: the table is now authoritative-active.
        dao.markActive(photoUri)

        // Fix Checking: images must converge so the photo reads as backed up.
        try {
            withTimeout(2_000) {
                vm.images.first { list ->
                    list.any { it.uri.toString() == photoUri && it.isBackedUp }
                }
            }
            true
        } catch (e: TimeoutCancellationException) {
            false
        }
    }

    /**
     * Concrete case — Trashed → Active 不刷新.
     *
     * Validates: Requirements 2.1, 2.2, 2.3
     */
    @Test
    fun `trashed to active - images converge to table status`() {
        val converged = reloadsAfterMarkActive(PhotoStatusValue.TRASHED)
        assertTrue(
            "BUG: after markActive the ViewModel images still show status=trashed " +
                "(isBackedUp=false); images did not refresh to the photo_status table.",
            converged
        )
    }

    /**
     * Concrete case — Purged → Active 不刷新.
     *
     * Validates: Requirements 2.1, 2.2, 2.3
     */
    @Test
    fun `purged to active - images converge to table status`() {
        val converged = reloadsAfterMarkActive(PhotoStatusValue.PURGED)
        assertTrue(
            "BUG: after markActive the ViewModel images still show status=purged " +
                "(isBackedUp=false); images did not refresh to the photo_status table.",
            converged
        )
    }

    /**
     * Property (Scoped PBT) — for ANY initial non-active status, after markActive
     * the ViewModel-exposed images must converge to the photo_status table.
     *
     * Feature: rebackup-status-refresh, Property 1: Bug Condition — 展示状态收敛到 photo_status 表
     *
     * Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6
     */
    @Test
    fun `Property 1 - any non-active status converges to table after markActive`() {
        runBlocking {
            // The bug is deterministic; a small iteration count over the non-active
            // status domain is sufficient to surface the counterexample.
            PropertyTesting.defaultIterationCount = 6
            checkAll(Arb.of(PhotoStatusValue.TRASHED, PhotoStatusValue.PURGED)) { initialStatus ->
                // Fresh table row per iteration (same uri is fine — upsert replaces).
                val converged = reloadsAfterMarkActive(initialStatus)
                assertTrue(
                    "BUG: initial status=$initialStatus did not converge to active " +
                        "(isBackedUp stayed false) after markActive — images is a stale snapshot.",
                    converged
                )
            }
        }
    }
}
