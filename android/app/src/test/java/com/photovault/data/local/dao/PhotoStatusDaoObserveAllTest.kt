package com.photovault.data.local.dao

import androidx.room.Room
import com.photovault.data.local.AppDatabase
import com.photovault.data.local.entity.PhotoStatus
import com.photovault.data.local.entity.PhotoStatusValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit test for [PhotoStatusDao.observeAll] (task 3.1 of the
 * `rebackup-status-refresh` bugfix spec).
 *
 * Verifies the reactive query emits a list containing the latest status after
 * insert / update / [PhotoStatusDao.markActive], so FolderDetailViewModel can
 * converge the displayed status to the authoritative photo_status table.
 *
 * Uses [Room.inMemoryDatabaseBuilder] following existing repo conventions
 * (see FolderDetailStatusRefreshTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PhotoStatusDaoObserveAllTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PhotoStatusDao

    private val photoUri = "content://media/external/images/media/1"

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.photoStatusDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `observeAll emits inserted record`() = runBlocking {
        dao.upsert(
            PhotoStatus(fileUri = photoUri, fileHash = "hash-1", status = PhotoStatusValue.TRASHED)
        )

        val emitted = dao.observeAll().first()

        assertEquals(1, emitted.size)
        assertEquals(photoUri, emitted.single().fileUri)
        assertEquals(PhotoStatusValue.TRASHED, emitted.single().status)
    }

    @Test
    fun `observeAll reflects latest status after upsert update`() = runBlocking {
        dao.upsert(
            PhotoStatus(fileUri = photoUri, fileHash = "hash-1", status = PhotoStatusValue.TRASHED)
        )
        // Upsert with REPLACE conflict strategy updates the existing row (same PK).
        dao.upsert(
            PhotoStatus(fileUri = photoUri, fileHash = "hash-1", status = PhotoStatusValue.PURGED)
        )

        val emitted = dao.observeAll().first()

        assertEquals(1, emitted.size)
        assertEquals(PhotoStatusValue.PURGED, emitted.single().status)
    }

    @Test
    fun `observeAll reflects latest status after markActive`() = runBlocking {
        dao.upsert(
            PhotoStatus(fileUri = photoUri, fileHash = "hash-1", status = PhotoStatusValue.TRASHED)
        )

        dao.markActive(photoUri)

        val emitted = dao.observeAll().first()

        assertEquals(1, emitted.size)
        val record = emitted.single()
        assertEquals(PhotoStatusValue.ACTIVE, record.status)
        // markActive clears the deletion markers.
        assertTrue(record.deletedAt == null)
        assertTrue(record.expiresAt == null)
        assertNotNull(record.fileUri)
    }
}
