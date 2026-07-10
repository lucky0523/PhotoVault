package com.photovault.service

import java.util.PriorityQueue
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
 */
@Singleton
class BackupQueue @Inject constructor() {

    private val queue = PriorityQueue<FileInfo>(
        compareBy { it.createdTime }
    )

    /**
     * Adds a list of files to the backup queue.
     * Files are automatically sorted by creation time (oldest first).
     */
    @Synchronized
    fun enqueue(files: List<FileInfo>) {
        queue.addAll(files)
    }

    /**
     * Removes and returns the next file to backup (oldest creation time).
     * Returns null if the queue is empty.
     */
    @Synchronized
    fun dequeue(): FileInfo? {
        return queue.poll()
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
        val before = queue.size
        queue.removeAll { canonicalFolderKey(it.folderUri) == key }
        return before - queue.size
    }

    /**
     * Returns a snapshot of all files currently in the queue, sorted by creation time.
     */
    @Synchronized
    fun getAll(): List<FileInfo> {
        return queue.toList().sortedBy { it.createdTime }
    }
}
