package com.huoyi.photovault.service

import com.huoyi.photovault.data.local.entity.PhotoStatus
import com.huoyi.photovault.data.local.entity.PhotoStatusValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [selectFilesToEnqueue], the scan's per-folder enqueue decision.
 *
 * Covers the file-versioning additions (验收 32: missing size is only
 * back-filled; 验收 33: a changed size re-enqueues through the usual gates) and
 * regression of the original loop for files without a status row.
 */
class SelectFilesToEnqueueTest {

    private val now = 10_000_000_000L
    private val old = now - 24L * 60 * 60 * 1000 // well past the quiet period

    private fun file(uri: String, size: Long = 100L, modified: Long = old) = FileInfo(
        uri = uri,
        fileName = "$uri.jpg",
        fileSize = size,
        createdTime = modified,
        mimeType = "image/jpeg",
        folderUri = "tree://folder"
    )

    private fun row(uri: String, status: String = PhotoStatusValue.ACTIVE, size: Long? = 100L) =
        PhotoStatus(fileUri = uri, fileHash = "h-$uri", status = status, fileSize = size)

    private fun select(
        files: List<FileInfo>,
        statuses: List<PhotoStatus> = emptyList(),
        queuedUris: Set<String> = emptySet(),
        inFlightUri: String? = null,
        failureCountByUri: Map<String, Int> = emptyMap(),
        lastFailureAtByUri: Map<String, Long> = emptyMap(),
        forceFullScan: Boolean = false
    ) = selectFilesToEnqueue(
        statusMap = statuses.associateBy { it.fileUri },
        files = files,
        queuedUris = queuedUris,
        inFlightUri = inFlightUri,
        failureCountByUri = failureCountByUri,
        lastFailureAtByUri = lastFailureAtByUri,
        now = now,
        forceFullScan = forceFullScan
    )

    // --- 验收 32: size back-fill -------------------------------------------

    @Test
    fun `active row without size is only back-filled, not enqueued`() {
        val result = select(listOf(file("a", size = 500L)), listOf(row("a", size = null)))

        assertTrue(result.newFiles.isEmpty())
        assertEquals(listOf("a" to 500L), result.sizeBackfill)
        assertEquals(0, result.modifiedCount)
        assertTrue(result.skippedModifiedTimes.isEmpty())
    }

    @Test
    fun `active row without size and untrusted MediaStore size is neither back-filled nor enqueued`() {
        val result = select(listOf(file("a", size = 0L)), listOf(row("a", size = null)))

        assertTrue(result.newFiles.isEmpty())
        assertTrue(result.sizeBackfill.isEmpty())
    }

    @Test
    fun `active row with unchanged size is skipped`() {
        val result = select(listOf(file("a", size = 100L)), listOf(row("a", size = 100L)))

        assertTrue(result.newFiles.isEmpty())
        assertTrue(result.sizeBackfill.isEmpty())
        assertEquals(0, result.modifiedCount)
    }

    // --- 验收 33: modified files --------------------------------------------

    @Test
    fun `active row with changed size is enqueued once and counted as modified`() {
        val f = file("a", size = 168L)
        val result = select(listOf(f), listOf(row("a", size = 100L)))

        assertEquals(listOf(f), result.newFiles)
        assertEquals(1, result.modifiedCount)
        assertTrue(result.sizeBackfill.isEmpty())
    }

    @Test
    fun `modified file already queued or in flight is not enqueued again`() {
        val statuses = listOf(row("a", size = 100L), row("b", size = 100L))
        val files = listOf(file("a", size = 200L), file("b", size = 200L))

        val result = select(files, statuses, queuedUris = setOf("a"), inFlightUri = "b")

        assertTrue(result.newFiles.isEmpty())
        assertEquals(2, result.modifiedCount)
    }

    @Test
    fun `modified file within quiet period is skipped and recorded for lastScanTime rollback`() {
        val recent = now - 1_000L
        val result = select(listOf(file("a", size = 200L, modified = recent)), listOf(row("a", size = 100L)))

        assertTrue(result.newFiles.isEmpty())
        assertEquals(listOf(recent), result.skippedModifiedTimes)
        assertEquals(1, result.modifiedCount)
    }

    @Test
    fun `modified file in failure backoff is skipped, forceFullScan bypasses the backoff`() {
        val f = file("a", size = 200L)
        val statuses = listOf(row("a", size = 100L))
        val failures = mapOf("a" to 1)
        val lastFail = mapOf("a" to now - 60_000L) // within the 30 min backoff

        val normal = select(listOf(f), statuses, failureCountByUri = failures, lastFailureAtByUri = lastFail)
        assertTrue(normal.newFiles.isEmpty())

        val forced = select(
            listOf(f), statuses,
            failureCountByUri = failures, lastFailureAtByUri = lastFail, forceFullScan = true
        )
        assertEquals(listOf(f), forced.newFiles)
    }

    // --- trashed / purged ----------------------------------------------------

    @Test
    fun `trashed and purged rows are never enqueued or back-filled`() {
        val statuses = listOf(
            row("t", status = PhotoStatusValue.TRASHED, size = null),
            row("p", status = PhotoStatusValue.PURGED, size = 1L)
        )
        val result = select(listOf(file("t", size = 999L), file("p", size = 999L)), statuses, forceFullScan = true)

        assertTrue(result.newFiles.isEmpty())
        assertTrue(result.sizeBackfill.isEmpty())
        assertEquals(0, result.modifiedCount)
    }

    // --- regression: files without a status row behave as before ------------

    @Test
    fun `new files pass through quiet period, dedup and backoff gates like the original loop`() {
        val recent = now - 1_000L
        val files = listOf(
            file("new"),                       // enqueued
            file("quiet", modified = recent),  // quiet period
            file("zero", modified = 0L),       // missing DATE_MODIFIED is never skipped
            file("queued"),                    // already queued
            file("inflight"),                  // currently uploading
            file("backoff"),                   // failed recently
            file("expired")                    // failed long ago, backoff elapsed
        )
        val result = select(
            files,
            queuedUris = setOf("queued"),
            inFlightUri = "inflight",
            failureCountByUri = mapOf("backoff" to 2, "expired" to 1),
            lastFailureAtByUri = mapOf("backoff" to now - 60_000L, "expired" to now - 2L * 60 * 60 * 1000)
        )

        assertEquals(listOf("new", "zero", "expired"), result.newFiles.map { it.uri })
        assertEquals(listOf(recent), result.skippedModifiedTimes)
        assertEquals(0, result.modifiedCount)
        assertTrue(result.sizeBackfill.isEmpty())
    }

    @Test
    fun `scan order is preserved across new and modified files`() {
        val files = listOf(file("n1"), file("m", size = 300L), file("n2"))
        val result = select(files, listOf(row("m", size = 100L)))

        assertEquals(listOf("n1", "m", "n2"), result.newFiles.map { it.uri })
    }
}
