package com.photovault.service

import com.photovault.data.local.dao.QueuedFileDao
import com.photovault.data.local.entity.QueuedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.PriorityQueue
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Represents a file to be backed up.
 */
data class FileInfo(
    val uri: String,
    val fileName: String,
    val fileSize: Long,
    val createdTime: Long,
    val mimeType: String,
    val folderUri: String,
    /**
     * When true, this file is a manual re-upload of a trashed/purged photo.
     * The uploader must NOT short-circuit on the server's trashed/purged status
     * (see ChunkUploader.checkDuplicate); it should upload and complete so the
     * server reactivates the existing record.
     */
    val forceReupload: Boolean = false
)

/**
 * Manages the backup queue, sorted by file creation time (oldest first).
 *
 * Files are enqueued when new images are discovered during scanning,
 * and dequeued one at a time for sequential upload.
 *
 * ## Persistence
 * The in-memory [PriorityQueue] is authoritative while the process is alive, but
 * it is lost when the app is closed or the process is killed. To keep the "排队中"
 * list from being wiped on a restart, every mutation is mirrored to the
 * [QueuedFileDao] (`queued_files` table) and the in-memory queue is rebuilt from
 * it on the next process start via [restoreFromPersistence].
 *
 * Persistence writes are dispatched on a dedicated single-thread executor so they
 * preserve call order and never block the (synchronous) callers. Losing a write
 * in a crash is harmless: a stale row is re-added on the next restore and the
 * server dedups it, while a missed insert is rediscovered by the next scan. The
 * [queuedFileDao] is optional so the queue can be constructed without a database
 * in unit tests, where persistence is simply a no-op.
 */
@Singleton
class BackupQueue @Inject constructor(
    private val queuedFileDao: QueuedFileDao?
) {

    /**
     * No-arg constructor for unit tests that don't exercise persistence. Kept as
     * a plain (non-`@Inject`) secondary constructor so Dagger sees exactly one
     * injected constructor; a default parameter value would generate a second
     * synthetic constructor that Hilt rejects.
     */
    constructor() : this(null)

    private val queue = PriorityQueue<FileInfo>(
        compareBy { it.createdTime }
    )

    /**
     * Serializes durable mirror writes so they commit in call order (insert then
     * delete for a quick enqueue→dequeue) without blocking the synchronized queue
     * operations that callers invoke on their own threads/coroutines.
     */
    private val persistenceScope = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )

    /**
     * Adds a list of files to the backup queue.
     * Files are automatically sorted by creation time (oldest first).
     */
    @Synchronized
    fun enqueue(files: List<FileInfo>) {
        queue.addAll(files)
        if (files.isNotEmpty()) {
            persist { dao -> dao.insertAll(files.map { it.toQueuedFile() }) }
        }
    }

    /**
     * Removes and returns the next file to backup (oldest creation time).
     * Returns null if the queue is empty.
     */
    @Synchronized
    fun dequeue(): FileInfo? {
        val file = queue.poll() ?: return null
        persist { dao -> dao.deleteByUri(file.uri) }
        return file
    }

    /**
     * Returns the next file without removing it from the queue.
     * Returns null if the queue is empty.
     */
    @Synchronized
    fun peek(): FileInfo? {
        return queue.peek()
    }

    /**
     * Returns the current number of files in the queue.
     */
    @Synchronized
    fun size(): Int {
        return queue.size
    }

    /**
     * Returns true if the queue is empty.
     */
    @Synchronized
    fun isEmpty(): Boolean {
        return queue.isEmpty()
    }

    /**
     * Clears all files from the queue.
     */
    @Synchronized
    fun clear() {
        queue.clear()
        persist { dao -> dao.deleteAll() }
    }

    /**
     * Removes all queued files that belong to [folderUri], matched by canonical
     * key (tolerant of URL-encoding differences, see [canonicalFolderKey]).
     *
     * Called when a backup folder is removed so its not-yet-uploaded files stop
     * being backed up instead of draining through the upload loop.
     *
     * @return the number of files removed from the queue.
     */
    @Synchronized
    fun removeByFolder(folderUri: String): Int {
        val key = canonicalFolderKey(folderUri)
        val removedUris = queue.filter { canonicalFolderKey(it.folderUri) == key }.map { it.uri }
        val before = queue.size
        queue.removeAll { canonicalFolderKey(it.folderUri) == key }
        if (removedUris.isNotEmpty()) {
            persist { dao -> dao.deleteByUris(removedUris) }
        }
        return before - queue.size
    }

    /**
     * Returns a snapshot of all files currently in the queue, sorted by creation time.
     */
    @Synchronized
    fun getAll(): List<FileInfo> {
        return queue.toList().sortedBy { it.createdTime }
    }

    /**
     * Rebuilds the in-memory queue from the persisted [QueuedFile] rows so the
     * "排队中" list survives an app close/kill. Files already present in memory
     * (matched by URI) are skipped, so this is idempotent and safe to call from
     * more than one entry point (the scan worker and the foreground service).
     *
     * A no-op when no [QueuedFileDao] is available (unit tests) or when the read
     * fails — the in-memory queue is left untouched and the next scan rediscovers
     * any still-pending files.
     */
    suspend fun restoreFromPersistence() {
        val dao = queuedFileDao ?: return
        val persisted = try {
            dao.getAll()
        } catch (e: Exception) {
            android.util.Log.e("PhotoVaultBackup", "Failed to restore backup queue: ${e.message}", e)
            return
        }
        if (persisted.isEmpty()) return
        synchronized(this) {
            val existing = queue.mapTo(HashSet()) { it.uri }
            persisted.asSequence()
                .filter { it.uri !in existing }
                .forEach { queue.add(it.toFileInfo()) }
        }
    }

    private inline fun persist(crossinline block: suspend (QueuedFileDao) -> Unit) {
        val dao = queuedFileDao ?: return
        persistenceScope.launch {
            try {
                block(dao)
            } catch (e: Exception) {
                android.util.Log.e("PhotoVaultBackup", "Failed to persist backup queue change: ${e.message}", e)
            }
        }
    }

    /**
     * Test-only: suspends until all persistence writes launched so far have
     * committed. Each mirror write is a child coroutine of [persistenceScope], and
     * a child only completes once its (suspending) DAO call finishes, so joining
     * the current children awaits the actual database writes. Not used in
     * production, where writes are intentionally fire-and-forget.
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun awaitPersistence() {
        val job = persistenceScope.coroutineContext[Job] ?: return
        job.children.toList().forEach { it.join() }
    }
}

private fun FileInfo.toQueuedFile(): QueuedFile = QueuedFile(
    uri = uri,
    fileName = fileName,
    fileSize = fileSize,
    createdTime = createdTime,
    mimeType = mimeType,
    folderUri = folderUri,
    forceReupload = forceReupload
)

private fun QueuedFile.toFileInfo(): FileInfo = FileInfo(
    uri = uri,
    fileName = fileName,
    fileSize = fileSize,
    createdTime = createdTime,
    mimeType = mimeType,
    folderUri = folderUri,
    forceReupload = forceReupload
)
