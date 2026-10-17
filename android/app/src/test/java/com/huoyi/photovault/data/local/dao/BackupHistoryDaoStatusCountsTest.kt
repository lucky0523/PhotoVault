package com.huoyi.photovault.data.local.dao

import androidx.room.Room
import com.huoyi.photovault.data.local.AppDatabase
import com.huoyi.photovault.data.local.entity.BackupHistoryRecord
import com.huoyi.photovault.data.local.entity.BackupStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit test for [BackupHistoryDao.observeStatusCounts], the SQL `GROUP BY status`
 * query behind the 备份任务 history filter-pill counts.
 *
 * Uses [Room.inMemoryDatabaseBuilder] following [PhotoStatusDaoObserveAllTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BackupHistoryDaoStatusCountsTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BackupHistoryDao

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.backupHistoryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private var seq = 0L

    private fun record(status: BackupStatus): BackupHistoryRecord {
        seq++
        return BackupHistoryRecord(
            id = 0,
            fileUri = "content://media/$seq",
            fileName = "IMG_$seq.jpg",
            fileSize = 1024L,
            status = status,
            errorMessage = if (status == BackupStatus.SUCCESS) null else "reason",
            folderUri = "tree://folder",
            completedAt = 1_700_000_000_000L + seq
        )
    }

    private suspend fun countsByStatus(): Map<BackupStatus, Int> =
        dao.observeStatusCounts().first().associate { it.status to it.count }

    @Test
    fun `empty table emits no rows`() = runBlocking {
        assertTrue(dao.observeStatusCounts().first().isEmpty())
    }

    @Test
    fun `mixed statuses are counted per status`() = runBlocking {
        repeat(3) { dao.insert(record(BackupStatus.SUCCESS)) }
        dao.insert(record(BackupStatus.FAILED))
        repeat(2) { dao.insert(record(BackupStatus.SKIPPED)) }

        assertEquals(
            mapOf(BackupStatus.SUCCESS to 3, BackupStatus.FAILED to 1, BackupStatus.SKIPPED to 2),
            countsByStatus()
        )
    }

    @Test
    fun `statuses without records are absent`() = runBlocking {
        repeat(4) { dao.insert(record(BackupStatus.FAILED)) }

        assertEquals(mapOf(BackupStatus.FAILED to 4), countsByStatus())
    }

    @Test
    fun `deleteById lowers the matching count`() = runBlocking {
        val failedId = dao.insert(record(BackupStatus.FAILED))
        dao.insert(record(BackupStatus.FAILED))
        dao.insert(record(BackupStatus.SUCCESS))

        dao.deleteById(failedId)

        assertEquals(
            mapOf(BackupStatus.FAILED to 1, BackupStatus.SUCCESS to 1),
            countsByStatus()
        )
    }

    @Test
    fun `deleteAll empties the counts`() = runBlocking {
        dao.insert(record(BackupStatus.SUCCESS))
        dao.insert(record(BackupStatus.SKIPPED))

        dao.deleteAll()

        assertTrue(dao.observeStatusCounts().first().isEmpty())
    }
}
