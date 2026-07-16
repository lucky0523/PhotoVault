package com.photovault.service

import androidx.room.Room
import com.photovault.data.local.AppDatabase
import com.photovault.data.local.dao.QueuedFileDao
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
 * Persistence tests for [BackupQueue] (photo-backup-service: durable queue so the
 * "排队中" list survives an app close/kill).
 *
 * ## Framework
 * Follows the repo convention (JUnit4 + Robolectric 4.14, real in-memory Room via
 * [Room.inMemoryDatabaseBuilder]; see `PhotoStatusDaoObserveAllTest`). The real
 * [QueuedFileDao] is used so the entity/DAO/migration schema is genuinely
 * exercised.
 *
 * Mirror writes are dispatched asynchronously on [BackupQueue]'s internal
 * single-thread persistence scope, so each test awaits them with the test-only
 * [BackupQueue.awaitPersistence] before asserting the database state.
 *
 * The key behavior under test is restart survival: a *new* [BackupQueue] built on
 * the same database (simulating a process that was killed and relaunched, with an
 * empty in-memory queue) rebuilds its queue from persistence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BackupQueuePersistenceTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: QueuedFileDao

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.queuedFileDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- helpers ------------------------------------------------------------

    private fun file(
        uri: String,
        createdTime: Long,
        folderUri: String = "content://tree/folderA"
    ) = FileInfo(
        uri = uri,
        fileName = uri.substringAfterLast('/'),
        fileSize = 1_000L,
        createdTime = createdTime,
        mimeType = "image/jpeg",
        folderUri = folderUri
    )

    // --- tests --------------------------------------------------------------

    @Test
    fun `enqueue mirrors files to persistence`() = runBlocking {
        val queue = BackupQueue(dao)

        queue.enqueue(listOf(file("content://f/1", 100), file("content://f/2", 200)))
        queue.awaitPersistence()

        val persisted = dao.getAll().map { it.uri }
        assertEquals(listOf("content://f/1", "content://f/2"), persisted)
    }

    @Test
    fun `restoreFromPersistence rebuilds the queue after a restart`() = runBlocking {
        // Populate + persist via one queue instance...
        val original = BackupQueue(dao)
        original.enqueue(listOf(file("content://f/2", 200), file("content://f/1", 100)))
        original.awaitPersistence()

        // ...then simulate a process restart: a brand-new queue with an empty
        // in-memory PriorityQueue, sharing the same (persisted) database.
        val restarted = BackupQueue(dao)
        assertTrue("fresh queue starts empty in memory", restarted.getAll().isEmpty())

        restarted.restoreFromPersistence()

        // Rebuilt and ordered oldest-first (by createdTime).
        assertEquals(
            listOf("content://f/1", "content://f/2"),
            restarted.getAll().map { it.uri }
        )
    }

    @Test
    fun `restored FileInfo preserves all fields`() = runBlocking {
        val original = BackupQueue(dao)
        val f = FileInfo(
            uri = "content://f/9",
            fileName = "photo.heic",
            fileSize = 42_000L,
            createdTime = 555L,
            mimeType = "image/heic",
            folderUri = "content://tree/album",
            forceReupload = true
        )
        original.enqueue(listOf(f))
        original.awaitPersistence()

        val restored = BackupQueue(dao).apply { restoreFromPersistence() }.getAll().single()
        assertEquals(f, restored)
    }

    @Test
    fun `dequeue removes the file from persistence so a restart does not resurrect it`() = runBlocking {
        val queue = BackupQueue(dao)
        queue.enqueue(listOf(file("content://f/1", 100), file("content://f/2", 200)))
        queue.awaitPersistence()

        val first = queue.dequeue()
        queue.awaitPersistence()
        assertEquals("content://f/1", first?.uri)

        // The dequeued file is gone from persistence; only the remaining one
        // survives a restart.
        assertEquals(listOf("content://f/2"), dao.getAll().map { it.uri })
        val restarted = BackupQueue(dao).apply { restoreFromPersistence() }
        assertEquals(listOf("content://f/2"), restarted.getAll().map { it.uri })
    }

    @Test
    fun `clear empties the persisted queue`() = runBlocking {
        val queue = BackupQueue(dao)
        queue.enqueue(listOf(file("content://f/1", 100), file("content://f/2", 200)))
        queue.awaitPersistence()

        queue.clear()
        queue.awaitPersistence()

        assertTrue(dao.getAll().isEmpty())
        val restarted = BackupQueue(dao).apply { restoreFromPersistence() }
        assertTrue(restarted.getAll().isEmpty())
    }

    @Test
    fun `removeByFolder deletes only that folder's persisted files`() = runBlocking {
        val queue = BackupQueue(dao)
        queue.enqueue(
            listOf(
                file("content://f/a1", 100, folderUri = "content://tree/A"),
                file("content://f/a2", 150, folderUri = "content://tree/A"),
                file("content://f/b1", 200, folderUri = "content://tree/B")
            )
        )
        queue.awaitPersistence()

        val removed = queue.removeByFolder("content://tree/A")
        queue.awaitPersistence()

        assertEquals(2, removed)
        assertEquals(listOf("content://f/b1"), dao.getAll().map { it.uri })
    }

    @Test
    fun `restoreFromPersistence skips files already present in memory`() = runBlocking {
        // Seed persistence with two rows.
        dao.insertAll(
            listOf(
                file("content://f/1", 100).let { queuedOf(it) },
                file("content://f/2", 200).let { queuedOf(it) }
            )
        )

        // A queue that already has f/1 in memory should not double-add it on restore.
        val queue = BackupQueue(dao)
        queue.enqueue(listOf(file("content://f/1", 100)))
        queue.awaitPersistence()

        queue.restoreFromPersistence()

        val uris = queue.getAll().map { it.uri }
        assertEquals(listOf("content://f/1", "content://f/2"), uris)
        // f/1 appears exactly once (deduped by URI).
        assertEquals(1, uris.count { it == "content://f/1" })
    }

    private fun queuedOf(f: FileInfo) = com.photovault.data.local.entity.QueuedFile(
        uri = f.uri,
        fileName = f.fileName,
        fileSize = f.fileSize,
        createdTime = f.createdTime,
        mimeType = f.mimeType,
        folderUri = f.folderUri,
        forceReupload = f.forceReupload
    )
}
