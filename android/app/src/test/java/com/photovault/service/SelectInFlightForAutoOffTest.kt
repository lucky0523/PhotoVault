package com.photovault.service

import com.photovault.data.local.entity.UploadRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BackupForegroundService.selectInFlightForAutoOff].
 *
 * Feature: photo-backup-service, task 27 (关闭自动备份后保留正在上传文件为"已暂停"任务).
 *
 * When the user turns OFF the "自动备份" switch, only files for which at least
 * one chunk has already been confirmed (`uploaded_chunk_index >= 0`) are the
 * In_Flight_File(s) that must be kept and later marked AUTO_OFF (R-25.2/25.5).
 * Records that have not confirmed any chunk yet (`uploaded_chunk_index == -1`)
 * are not in flight and are excluded.
 */
class SelectInFlightForAutoOffTest {

    private fun record(fileUri: String, uploadedChunkIndex: Int): UploadRecord =
        UploadRecord(
            fileUri = fileUri,
            sessionId = "session-$fileUri",
            fileHash = "hash-$fileUri",
            fileName = fileUri,
            fileSize = 1024L,
            fileModifiedTime = 0L,
            totalChunks = 4,
            uploadedChunkIndex = uploadedChunkIndex
        )

    @Test
    fun `keeps only records with a confirmed chunk`() {
        val inFlight = record("in-flight", uploadedChunkIndex = 0)
        val alsoInFlight = record("also-in-flight", uploadedChunkIndex = 2)
        val notStarted = record("not-started", uploadedChunkIndex = -1)

        val result = BackupForegroundService.selectInFlightForAutoOff(
            listOf(notStarted, inFlight, alsoInFlight)
        )

        assertEquals(listOf(inFlight, alsoInFlight), result)
    }

    @Test
    fun `index zero counts as in flight`() {
        // Boundary: uploaded_chunk_index == 0 means one chunk confirmed → in flight.
        val boundary = record("boundary", uploadedChunkIndex = 0)

        val result = BackupForegroundService.selectInFlightForAutoOff(listOf(boundary))

        assertEquals(listOf(boundary), result)
    }

    @Test
    fun `returns empty when no record has a confirmed chunk`() {
        // R-25.5: nothing in flight → no records selected.
        val result = BackupForegroundService.selectInFlightForAutoOff(
            listOf(
                record("a", uploadedChunkIndex = -1),
                record("b", uploadedChunkIndex = -1)
            )
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns empty for empty input`() {
        assertTrue(
            BackupForegroundService.selectInFlightForAutoOff(emptyList()).isEmpty()
        )
    }
}
