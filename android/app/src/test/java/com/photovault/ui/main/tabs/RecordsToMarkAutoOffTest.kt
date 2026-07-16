package com.photovault.ui.main.tabs

import com.photovault.data.local.entity.UploadRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SettingsViewModel.recordsToMarkAutoOff].
 *
 * Feature: photo-backup-service, task 27.4 (关闭开关处理：标记 AUTO_OFF 并过滤自动续传).
 *
 * When the user turns OFF the "自动备份" switch while an automatic run is active,
 * only the In_Flight_File(s) — a persisted [UploadRecord] with a confirmed chunk
 * (`uploaded_chunk_index >= 0`) that is still within the 7-day resume window —
 * are marked AUTO_OFF. Queued_Not_Started_File(s) have no record and never
 * appear here; expired records are dropped (R-25.2/25.5/32.1).
 */
class RecordsToMarkAutoOffTest {

    private val expiryMs = SettingsViewModel.SESSION_EXPIRY_MS
    private val now = 1_000_000_000_000L

    private fun record(
        fileUri: String,
        uploadedChunkIndex: Int,
        createdAt: Long = now
    ): UploadRecord = UploadRecord(
        fileUri = fileUri,
        sessionId = "session-$fileUri",
        fileHash = "hash-$fileUri",
        fileName = fileUri,
        fileSize = 1024L,
        fileModifiedTime = 0L,
        totalChunks = 4,
        uploadedChunkIndex = uploadedChunkIndex,
        createdAt = createdAt
    )

    @Test
    fun `marks only in-flight records within the resume window`() {
        val inFlight = record("in-flight", uploadedChunkIndex = 0)
        val alsoInFlight = record("also-in-flight", uploadedChunkIndex = 2)
        val notStarted = record("not-started", uploadedChunkIndex = -1)

        val result = SettingsViewModel.recordsToMarkAutoOff(
            records = listOf(notStarted, inFlight, alsoInFlight),
            nowMs = now,
            sessionExpiryMs = expiryMs
        )

        assertEquals(listOf(inFlight, alsoInFlight), result)
    }

    @Test
    fun `drops expired in-flight records`() {
        // Older than the 7-day window → excluded even though it has a chunk.
        val expired = record("expired", uploadedChunkIndex = 1, createdAt = now - expiryMs - 1)
        val fresh = record("fresh", uploadedChunkIndex = 1, createdAt = now - expiryMs + 1)

        val result = SettingsViewModel.recordsToMarkAutoOff(
            records = listOf(expired, fresh),
            nowMs = now,
            sessionExpiryMs = expiryMs
        )

        assertEquals(listOf(fresh), result)
    }

    @Test
    fun `boundary at exactly the expiry window is kept`() {
        // now - created_at == expiry → still within window (<=), kept.
        val boundary = record("boundary", uploadedChunkIndex = 0, createdAt = now - expiryMs)

        val result = SettingsViewModel.recordsToMarkAutoOff(
            records = listOf(boundary),
            nowMs = now,
            sessionExpiryMs = expiryMs
        )

        assertEquals(listOf(boundary), result)
    }

    @Test
    fun `returns empty when nothing is in flight`() {
        // R-25.5: no confirmed chunk anywhere → nothing to mark.
        val result = SettingsViewModel.recordsToMarkAutoOff(
            records = listOf(
                record("a", uploadedChunkIndex = -1),
                record("b", uploadedChunkIndex = -1)
            ),
            nowMs = now,
            sessionExpiryMs = expiryMs
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns empty for empty input`() {
        assertTrue(
            SettingsViewModel.recordsToMarkAutoOff(emptyList(), now, expiryMs).isEmpty()
        )
    }
}
