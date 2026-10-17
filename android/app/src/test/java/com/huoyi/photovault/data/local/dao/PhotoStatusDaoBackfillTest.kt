package com.huoyi.photovault.data.local.dao

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import com.huoyi.photovault.data.local.AppDatabase
import com.huoyi.photovault.data.local.entity.BackupHistoryRecord
import com.huoyi.photovault.data.local.entity.BackupStatus
import com.huoyi.photovault.data.local.entity.PhotoStatus
import com.huoyi.photovault.data.local.entity.PhotoStatusValue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Room in-memory tests for the v11 PhotoStatusDao additions:
 * [PhotoStatusDao.backfillFileSize], [PhotoStatusDao.backfillFileSizes]
 * (the @Transaction interface default method), [PhotoStatusDao.markActiveWithContent]
 * and [BackupHistoryDao.deleteAll] returning the deleted row count.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PhotoStatusDaoBackfillTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PhotoStatusDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.photoStatusDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun active(uri: String, size: Long? = null, hash: String = "h-$uri") =
        PhotoStatus(fileUri = uri, fileHash = hash, status = PhotoStatusValue.ACTIVE, fileSize = size)

    @Test
    fun `backfillFileSizes fills missing sizes and keeps existing ones`() = runBlocking {
        dao.upsertAll(listOf(active("a"), active("b", size = 500L), active("c")))

        dao.backfillFileSizes(listOf("a" to 100L, "b" to 999L, "c" to 300L))

        assertEquals(100L, dao.getByFileUri("a")!!.fileSize)
        assertEquals("existing size must not be overwritten", 500L, dao.getByFileUri("b")!!.fileSize)
        assertEquals(300L, dao.getByFileUri("c")!!.fileSize)
    }

    @Test
    fun `backfillFileSizes runs the whole batch in one transaction`() = runBlocking {
        dao.upsertAll(listOf(active("a"), active("b"), active("c")))
        // Abort the batch on its last item. If the default method were not wrapped
        // in a transaction, the updates for "a" and "b" would stay committed.
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER abort_on_c BEFORE UPDATE OF file_size ON photo_status
            WHEN NEW.file_uri = 'c' BEGIN SELECT RAISE(ABORT, 'boom'); END
            """.trimIndent()
        )

        try {
            dao.backfillFileSizes(listOf("a" to 1L, "b" to 2L, "c" to 3L))
            fail("trigger should abort the batch")
        } catch (expected: SQLiteConstraintException) {
            // expected
        }

        assertNull("rolled back with the batch", dao.getByFileUri("a")!!.fileSize)
        assertNull("rolled back with the batch", dao.getByFileUri("b")!!.fileSize)
        assertTrue(!db.openHelper.writableDatabase.inTransaction())
    }

    @Test
    fun `markActiveWithContent updates hash and size, null size keeps old value`() = runBlocking {
        dao.upsert(
            PhotoStatus(
                fileUri = "a", fileHash = "H2", status = PhotoStatusValue.TRASHED,
                deletedAt = 1L, expiresAt = 2L, fileSize = 10L
            )
        )

        dao.markActiveWithContent("a", "H1", 20L, updatedAt = 5L)
        dao.getByFileUri("a")!!.let {
            assertEquals(PhotoStatusValue.ACTIVE, it.status)
            assertEquals("H1", it.fileHash)
            assertEquals(20L, it.fileSize)
            assertNull(it.deletedAt)
            assertNull(it.expiresAt)
            assertEquals(5L, it.updatedAt)
        }

        dao.markActiveWithContent("a", "H3", null, updatedAt = 6L)
        dao.getByFileUri("a")!!.let {
            assertEquals("H3", it.fileHash)
            assertEquals("null size must keep the stored size", 20L, it.fileSize)
        }
    }

    @Test
    fun `deleteAll returns number of history rows deleted`() = runBlocking {
        val history = db.backupHistoryDao()
        repeat(3) { i ->
            history.insert(
                BackupHistoryRecord(
                    fileUri = "u$i", fileName = "f$i.jpg", fileSize = 1L,
                    status = BackupStatus.SUCCESS, folderUri = "tree://f"
                )
            )
        }

        assertEquals(3, history.deleteAll())
        assertEquals(0, history.deleteAll())
    }
}
