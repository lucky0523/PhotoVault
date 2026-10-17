package com.huoyi.photovault.ui.main.tabs

import com.huoyi.photovault.data.local.SettingsPreferences
import com.huoyi.photovault.data.local.dao.BackupHistoryDao
import com.huoyi.photovault.data.local.dao.BackupStatusCount
import com.huoyi.photovault.data.local.dao.UploadRecordDao
import com.huoyi.photovault.data.local.entity.BackupHistoryRecord
import com.huoyi.photovault.data.local.entity.BackupStatus
import com.huoyi.photovault.data.local.entity.UploadRecord
import com.huoyi.photovault.service.BackupConditionChecker
import com.huoyi.photovault.service.BackupQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for the derived [HistoryCounts] shown on the 备份任务 history filter pills:
 * the pure mapping from SQL per-status rows, and the ViewModel wiring through
 * [BackupHistoryDao.observeStatusCounts].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class HistoryCountsTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun record(id: Long, status: BackupStatus) = BackupHistoryRecord(
        id = id,
        fileUri = "content://media/$id",
        fileName = "IMG_$id.jpg",
        fileSize = 1024L,
        status = status,
        errorMessage = if (status == BackupStatus.SUCCESS) null else "reason",
        folderUri = "tree://folder",
        completedAt = 1_700_000_000_000L + id
    )

    @Test
    fun historyCountsOf_noRows_isAllZero() {
        assertEquals(HistoryCounts(), TasksTabViewModel.historyCountsOf(emptyList()))
    }

    @Test
    fun historyCountsOf_mixedRows_mapsEachStatusAndSumsAll() {
        val rows = listOf(
            BackupStatusCount(BackupStatus.SKIPPED, 2),
            BackupStatusCount(BackupStatus.SUCCESS, 3),
            BackupStatusCount(BackupStatus.FAILED, 1)
        )
        assertEquals(
            HistoryCounts(all = 6, success = 3, failed = 1, skipped = 2),
            TasksTabViewModel.historyCountsOf(rows)
        )
    }

    @Test
    fun historyCountsOf_singleStatus_missingStatusesAreZero() {
        val rows = listOf(BackupStatusCount(BackupStatus.FAILED, 4))
        assertEquals(
            HistoryCounts(all = 4, success = 0, failed = 4, skipped = 0),
            TasksTabViewModel.historyCountsOf(rows)
        )
    }

    @Test
    fun viewModel_exposesCountsFromFullHistory() {
        val records = listOf(
            record(1, BackupStatus.SUCCESS),
            record(2, BackupStatus.FAILED),
            record(3, BackupStatus.FAILED),
            record(4, BackupStatus.SKIPPED)
        )
        val context = RuntimeEnvironment.getApplication()
        val viewModel = TasksTabViewModel(
            context = context,
            backupQueue = BackupQueue(),
            backupConditionChecker = BackupConditionChecker(context, SettingsPreferences(context)),
            backupHistoryDao = FakeBackupHistoryDao(records),
            uploadRecordDao = EmptyUploadRecordDao(),
            settingsPreferences = SettingsPreferences(context)
        )

        assertEquals(
            HistoryCounts(all = 4, success = 1, failed = 2, skipped = 1),
            viewModel.uiState.value.historyCounts
        )
    }

    private class FakeBackupHistoryDao(
        private val records: List<BackupHistoryRecord>
    ) : BackupHistoryDao {
        override suspend fun insert(record: BackupHistoryRecord): Long = 0L
        override fun getAll(): Flow<List<BackupHistoryRecord>> = flowOf(records)
        override fun getByStatus(status: BackupStatus): Flow<List<BackupHistoryRecord>> =
            flowOf(records.filter { it.status == status })
        override suspend fun getByStatusOnce(status: BackupStatus): List<BackupHistoryRecord> =
            records.filter { it.status == status }
        override suspend fun getById(id: Long): BackupHistoryRecord? = records.find { it.id == id }
        override suspend fun updateStatus(id: Long, status: BackupStatus, errorMessage: String?, completedAt: Long) {}
        override suspend fun deleteById(id: Long) {}
        override suspend fun deleteAll(): Int = 0
        override suspend fun deleteOlderThan(olderThan: Long) {}
        override suspend fun getCountByStatus(status: BackupStatus): Int =
            records.count { it.status == status }
        override fun observeStatusCounts(): Flow<List<BackupStatusCount>> =
            flowOf(records.groupBy { it.status }.map { (status, list) -> BackupStatusCount(status, list.size) })
    }

    private class EmptyUploadRecordDao : UploadRecordDao {
        override suspend fun insertOrUpdate(record: UploadRecord) {}
        override suspend fun getByFileUri(fileUri: String): UploadRecord? = null
        override suspend fun getBySessionId(sessionId: String): UploadRecord? = null
        override suspend fun deleteByFileUri(fileUri: String) {}
        override suspend fun deleteByFolderUri(folderUri: String) {}
        override suspend fun updateProgress(fileUri: String, chunkIndex: Int, updatedAt: Long) {}
        override suspend fun deleteExpired(expiryTime: Long) {}
        override suspend fun getAll(): List<UploadRecord> = emptyList()
        override suspend fun getPausedByAutoOff(): List<UploadRecord> = emptyList()
        override suspend fun markAutoOffPaused(fileUri: String, pausedAt: Long, updatedAt: Long) {}
        override suspend fun clearAutoOffPause(fileUri: String, updatedAt: Long) {}
    }
}
