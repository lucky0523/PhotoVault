package com.photovault.ui.main.tabs

import android.net.Uri
import com.photovault.data.local.SettingsPreferences
import com.photovault.data.local.dao.BackupHistoryDao
import com.photovault.data.local.dao.UploadRecordDao
import com.photovault.data.local.entity.BackupHistoryRecord
import com.photovault.data.local.entity.BackupStatus
import com.photovault.data.local.entity.UploadRecord
import com.photovault.service.BackupConditionChecker
import com.photovault.service.BackupQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import java.io.ByteArrayInputStream
import java.util.function.Supplier

/**
 * State-transition unit tests for [TasksTabViewModel]'s AUTO_OFF Paused_Task
 * flows (photo-backup-service, task 27.11).
 *
 * ## Framework
 * The project's unit tests use **JUnit4 + Robolectric 4.14** with in-memory
 * **Fake DAOs** (see `ChunkUploaderCheckDuplicateTest`, `FolderDetailIntegrationTest`);
 * there is no Mockk on the test classpath. This test follows that convention
 * rather than the task's tentative "JUnit5 + Mockk" wording: it drives the real
 * ViewModel against a functional in-memory [FakeUploadRecordDao] and a real
 * Robolectric [android.content.Context] (needed for the `contentResolver`
 * source-readable pre-check and `BackupForegroundService.start`).
 *
 * `Dispatchers.setMain(Dispatchers.Unconfined)` makes `viewModelScope.launch`
 * run eagerly/synchronously so each action's resulting `uiState` can be asserted
 * right after the call returns (the 2s polling loop parks on its `delay` and does
 * not interfere).
 *
 * Covers:
 * - resumePausedTask: record missing → no-op (R-27); happy path clears ONLY this
 *   file's AUTO_OFF marker + enqueues + starts a manual run without deleting the
 *   record (R-27.1/27.2); source unreadable → delete + one-shot message + no
 *   upload (R-27.6/32.3).
 * - clearPausedTask: success removes the record & entry (R-28.2/28.4); failure
 *   keeps them and surfaces a retry message (R-28.3); a cancelled dialog (no call)
 *   changes nothing (R-28.5).
 * - loadPausedTasks: read failure sets the error flag and touches no record
 *   (R-26.4); a fresh ViewModel rebuilds the list from persistence (R-31.1) and
 *   never auto-enqueues AUTO_OFF tasks (R-31.2); a removed record (e.g. upload
 *   succeeded) drops the entry on the next load (R-27.8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TasksTabViewModelStateTransitionTest {

    private lateinit var uploadRecordDao: FakeUploadRecordDao
    private lateinit var backupQueue: BackupQueue

    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        // viewModelScope dispatches on Dispatchers.Main.immediate; route it to an
        // unconfined dispatcher so launched work runs synchronously in the test.
        Dispatchers.setMain(Dispatchers.Unconfined)
        uploadRecordDao = FakeUploadRecordDao()
        backupQueue = BackupQueue()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- helpers ------------------------------------------------------------

    private fun newViewModel(): TasksTabViewModel {
        val context = RuntimeEnvironment.getApplication()
        return TasksTabViewModel(
            context = context,
            backupQueue = backupQueue,
            backupConditionChecker = BackupConditionChecker(context, SettingsPreferences(context)),
            backupHistoryDao = FakeBackupHistoryDao(),
            uploadRecordDao = uploadRecordDao,
            settingsPreferences = SettingsPreferences(context)
        )
    }

    private fun autoOffRecord(
        fileUri: String,
        fileName: String = "photo.jpg",
        uploadedChunkIndex: Int = 1,
        totalChunks: Int = 4,
        pausedAt: Long = now,
        createdAt: Long = now
    ) = UploadRecord(
        fileUri = fileUri,
        sessionId = "session-$fileUri",
        fileHash = "hash-$fileUri",
        fileName = fileName,
        fileSize = 1024L,
        fileModifiedTime = 0L,
        folderUri = "tree://folder",
        mimeType = "image/jpeg",
        totalChunks = totalChunks,
        uploadedChunkIndex = uploadedChunkIndex,
        createdAt = createdAt,
        updatedAt = now,
        pauseSource = "AUTO_OFF",
        pausedAt = pausedAt
    )

    /** Registers a readable content stream so isSourceReadable() returns true. */
    private fun markReadable(uriString: String) {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.contentResolver)
            .registerInputStream(Uri.parse(uriString), ByteArrayInputStream(ByteArray(4)))
    }

    /**
     * Registers a throwing stream supplier so `openInputStream` throws at open
     * time → isSourceReadable() returns false (mirrors `MediaBytesReaderTest`'s
     * approach for an unreadable/unsupported URI).
     */
    private fun markUnreadable(uriString: String) {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.contentResolver).registerInputStreamSupplier(
            Uri.parse(uriString),
            Supplier { throw java.io.FileNotFoundException("source gone") }
        )
    }

    // --- resumePausedTask ---------------------------------------------------

    @Test
    fun `resume with no persisted record is a no-op`() {
        // R-27: the record was already cleared/expired elsewhere → nothing to do.
        val vm = newViewModel()

        vm.resumePausedTask("content://missing")

        assertTrue("no file should be enqueued", backupQueue.getAll().isEmpty())
        assertNull("no transient message expected", vm.uiState.value.transientMessage)
        assertEquals(0, uploadRecordDao.clearAutoOffCount)
        assertEquals(0, uploadRecordDao.deleteCount)
    }

    @Test
    fun `resume clears only this record's AUTO_OFF marker and starts a manual run`() {
        // R-27.1/27.2: clear the AUTO_OFF marker for THIS file, enqueue it and
        // start a manual run; the record itself is preserved (resume, not clear),
        // and OTHER AUTO_OFF records are untouched (R-30.5).
        val target = "content://media/1"
        val other = "content://media/2"
        uploadRecordDao.seed(autoOffRecord(target, fileName = "a.jpg"))
        uploadRecordDao.seed(autoOffRecord(other, fileName = "b.jpg"))
        markReadable(target)

        val vm = newViewModel()
        vm.resumePausedTask(target)

        // Enqueued for upload as a manual run.
        val queued = backupQueue.getAll()
        assertEquals(1, queued.size)
        assertEquals(target, queued.single().uri)

        // AUTO_OFF marker cleared for the target only; record still present.
        val kept = uploadRecordDao.getStored(target)!!
        assertNull("target's AUTO_OFF marker must be cleared", kept.pauseSource)
        assertEquals(1, uploadRecordDao.clearAutoOffCount)
        assertEquals(0, uploadRecordDao.deleteCount)

        // The other AUTO_OFF record is untouched and still surfaces as paused.
        assertEquals("AUTO_OFF", uploadRecordDao.getStored(other)!!.pauseSource)
        val paused = vm.uiState.value.pausedTasks
        assertEquals(listOf(other), paused.map { it.fileUri })
        assertNull(vm.uiState.value.transientMessage)
    }

    @Test
    fun `resume with an unreadable source deletes the record and warns`() {
        // R-27.6/32.3: never start an upload for a source that no longer exists;
        // drop the orphaned record and surface a one-shot message.
        val target = "content://media/gone"
        uploadRecordDao.seed(autoOffRecord(target))
        markUnreadable(target)

        val vm = newViewModel()
        vm.resumePausedTask(target)

        assertTrue("no upload must be started", backupQueue.getAll().isEmpty())
        assertNull("record must be deleted", uploadRecordDao.getStored(target))
        assertEquals(1, uploadRecordDao.deleteCount)
        assertEquals(0, uploadRecordDao.clearAutoOffCount)
        assertEquals("源文件已不存在，无法续传", vm.uiState.value.transientMessage)
        assertTrue("entry must be removed", vm.uiState.value.pausedTasks.isEmpty())
    }

    // --- clearPausedTask ----------------------------------------------------

    @Test
    fun `clear success removes the record and the entry`() {
        // R-28.2/28.4: deleting the record drops the entry from the list.
        val target = "content://media/1"
        uploadRecordDao.seed(autoOffRecord(target))

        val vm = newViewModel()
        // init load surfaces the paused task.
        assertEquals(listOf(target), vm.uiState.value.pausedTasks.map { it.fileUri })

        vm.clearPausedTask(target)

        assertNull("record must be deleted", uploadRecordDao.getStored(target))
        assertEquals(1, uploadRecordDao.deleteCount)
        assertTrue("entry must be gone", vm.uiState.value.pausedTasks.isEmpty())
        assertNull("no error message on success", vm.uiState.value.transientMessage)
    }

    @Test
    fun `clear failure keeps the record and entry and shows a retry message`() {
        // R-28.3: a failed delete must not destroy anything; surface a retry hint.
        val target = "content://media/1"
        uploadRecordDao.seed(autoOffRecord(target))
        uploadRecordDao.failDelete = true

        val vm = newViewModel()
        assertEquals(listOf(target), vm.uiState.value.pausedTasks.map { it.fileUri })

        vm.clearPausedTask(target)

        assertTrue("record must be retained", uploadRecordDao.getStored(target) != null)
        assertEquals("清除失败，请重试", vm.uiState.value.transientMessage)
        assertEquals(
            "entry must remain visible",
            listOf(target),
            vm.uiState.value.pausedTasks.map { it.fileUri }
        )
    }

    @Test
    fun `cancelling the clear dialog changes nothing`() {
        // R-28.5: a cancelled dialog does not call clearPausedTask, so the record
        // and entry stay intact.
        val target = "content://media/1"
        uploadRecordDao.seed(autoOffRecord(target))

        val vm = newViewModel()
        val before = vm.uiState.value.pausedTasks.map { it.fileUri }

        // (No clearPausedTask call — this models tapping "取消".)

        assertEquals(listOf(target), before)
        assertTrue("record still persisted", uploadRecordDao.getStored(target) != null)
        assertEquals(0, uploadRecordDao.deleteCount)
    }

    // --- loadPausedTasks ----------------------------------------------------

    @Test
    fun `read failure sets the error flag without touching records`() {
        // R-26.4: a read failure surfaces an error state and modifies no record.
        uploadRecordDao.seed(autoOffRecord("content://media/1"))
        uploadRecordDao.failGetPaused = true

        val vm = newViewModel()

        assertTrue("error flag must be set", vm.uiState.value.pausedTasksLoadError)
        assertTrue("no paused tasks surfaced on error", vm.uiState.value.pausedTasks.isEmpty())
        assertEquals("no record may be deleted", 0, uploadRecordDao.deleteCount)
        assertEquals("no record may be cleared", 0, uploadRecordDao.clearAutoOffCount)
    }

    @Test
    fun `a fresh ViewModel rebuilds the paused list from persistence and does not auto-resume`() {
        // R-31.1: after a restart the list is rebuilt from the persisted AUTO_OFF
        // records. R-31.2: the ViewModel never auto-enqueues them (only an
        // explicit "继续" does), so the queue stays empty on load/poll.
        uploadRecordDao.seed(autoOffRecord("content://media/1", pausedAt = now - 100))
        uploadRecordDao.seed(autoOffRecord("content://media/2", pausedAt = now))

        val vm = newViewModel()

        // Rebuilt and ordered by pause time (most recent first).
        assertEquals(
            listOf("content://media/2", "content://media/1"),
            vm.uiState.value.pausedTasks.map { it.fileUri }
        )
        assertFalse(vm.uiState.value.pausedTasksLoadError)
        assertTrue("AUTO_OFF tasks must NOT be auto-enqueued", backupQueue.getAll().isEmpty())
    }

    @Test
    fun `expired records are filtered from the rebuilt list`() {
        // R-32.1: records older than the 7-day resume window are dropped.
        val expiredAt = now - TasksTabViewModel.SESSION_EXPIRY_MS - 1
        uploadRecordDao.seed(autoOffRecord("content://fresh", createdAt = now))
        uploadRecordDao.seed(autoOffRecord("content://expired", createdAt = expiredAt))

        val vm = newViewModel()

        assertEquals(
            listOf("content://fresh"),
            vm.uiState.value.pausedTasks.map { it.fileUri }
        )
    }

    @Test
    fun `a removed record drops the entry on reload (models upload success)`() {
        // R-27.8: once the resumed upload completes the ChunkUploader deletes the
        // record; the ViewModel's next load then removes the entry. Here we model
        // that by deleting the record out-of-band and reloading.
        val target = "content://media/1"
        uploadRecordDao.seed(autoOffRecord(target))

        val vm = newViewModel()
        assertEquals(listOf(target), vm.uiState.value.pausedTasks.map { it.fileUri })

        // Simulate a successful upload clearing the persisted record.
        uploadRecordDao.removeSilently(target)
        vm.loadPausedTasks()

        assertTrue("completed task's entry must be removed", vm.uiState.value.pausedTasks.isEmpty())
    }

    @Test
    fun `consumeTransientMessage clears a surfaced message`() {
        val target = "content://media/gone"
        uploadRecordDao.seed(autoOffRecord(target))
        markUnreadable(target)

        val vm = newViewModel()
        vm.resumePausedTask(target)
        assertEquals("源文件已不存在，无法续传", vm.uiState.value.transientMessage)

        vm.consumeTransientMessage()
        assertNull(vm.uiState.value.transientMessage)
    }

    // --- fakes --------------------------------------------------------------

    /** Functional in-memory [UploadRecordDao] with injectable failure switches. */
    private class FakeUploadRecordDao : UploadRecordDao {
        private val store = LinkedHashMap<String, UploadRecord>()

        var failGetPaused = false
        var failDelete = false

        var deleteCount = 0
        var clearAutoOffCount = 0
        var markCount = 0

        fun seed(record: UploadRecord) {
            store[record.fileUri] = record
        }

        fun getStored(fileUri: String): UploadRecord? = store[fileUri]

        /** Removes a record without touching the delete counter (out-of-band). */
        fun removeSilently(fileUri: String) {
            store.remove(fileUri)
        }

        override suspend fun insertOrUpdate(record: UploadRecord) {
            store[record.fileUri] = record
        }

        override suspend fun getByFileUri(fileUri: String): UploadRecord? = store[fileUri]

        override suspend fun getBySessionId(sessionId: String): UploadRecord? =
            store.values.find { it.sessionId == sessionId }

        override suspend fun deleteByFileUri(fileUri: String) {
            deleteCount++
            if (failDelete) throw RuntimeException("delete failed")
            store.remove(fileUri)
        }

        override suspend fun deleteByFolderUri(folderUri: String) {
            store.values.removeAll { it.folderUri == folderUri }
        }

        override suspend fun updateProgress(fileUri: String, chunkIndex: Int, updatedAt: Long) {
            store[fileUri]?.let { store[fileUri] = it.copy(uploadedChunkIndex = chunkIndex, updatedAt = updatedAt) }
        }

        override suspend fun deleteExpired(expiryTime: Long) {
            store.values.removeAll { it.createdAt < expiryTime }
        }

        override suspend fun getAll(): List<UploadRecord> = store.values.toList()

        override suspend fun getPausedByAutoOff(): List<UploadRecord> {
            if (failGetPaused) throw RuntimeException("read failed")
            return store.values
                .filter { it.pauseSource == "AUTO_OFF" }
                .sortedByDescending { it.pausedAt ?: it.updatedAt }
        }

        override suspend fun markAutoOffPaused(fileUri: String, pausedAt: Long, updatedAt: Long) {
            markCount++
            store[fileUri]?.let {
                store[fileUri] = it.copy(pauseSource = "AUTO_OFF", pausedAt = pausedAt, updatedAt = updatedAt)
            }
        }

        override suspend fun clearAutoOffPause(fileUri: String, updatedAt: Long) {
            clearAutoOffCount++
            store[fileUri]?.let {
                store[fileUri] = it.copy(pauseSource = null, pausedAt = null, updatedAt = updatedAt)
            }
        }
    }

    /** [BackupHistoryDao] stub — history isn't exercised by these tests. */
    private class FakeBackupHistoryDao : BackupHistoryDao {
        override suspend fun insert(record: BackupHistoryRecord): Long = 0L
        override fun getAll(): Flow<List<BackupHistoryRecord>> = flowOf(emptyList())
        override fun getByStatus(status: BackupStatus): Flow<List<BackupHistoryRecord>> = flowOf(emptyList())
        override suspend fun getById(id: Long): BackupHistoryRecord? = null
        override suspend fun updateStatus(id: Long, status: BackupStatus, errorMessage: String?, completedAt: Long) {}
        override suspend fun deleteById(id: Long) {}
        override suspend fun deleteAll() {}
        override suspend fun deleteOlderThan(olderThan: Long) {}
        override suspend fun getCountByStatus(status: BackupStatus): Int = 0
    }
}
