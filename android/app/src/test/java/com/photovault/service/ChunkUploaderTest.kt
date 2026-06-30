package com.photovault.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for ChunkUploader chunk calculation logic.
 */
class ChunkUploaderTest {

    @Test
    fun `calculateTotalChunks - file smaller than chunk size`() {
        // 1 byte file → 1 chunk
        assertEquals(1, calculateTotalChunks(1))
        // 1MB file → 1 chunk
        assertEquals(1, calculateTotalChunks(1 * 1024 * 1024))
    }

    @Test
    fun `calculateTotalChunks - file exactly one chunk`() {
        // Exactly 2MB → 1 chunk
        assertEquals(1, calculateTotalChunks(2L * 1024 * 1024))
    }

    @Test
    fun `calculateTotalChunks - file slightly larger than one chunk`() {
        // 2MB + 1 byte → 2 chunks
        assertEquals(2, calculateTotalChunks(2L * 1024 * 1024 + 1))
    }

    @Test
    fun `calculateTotalChunks - file exactly two chunks`() {
        // 4MB → 2 chunks
        assertEquals(2, calculateTotalChunks(4L * 1024 * 1024))
    }

    @Test
    fun `calculateTotalChunks - large file`() {
        // 10MB → 5 chunks
        assertEquals(5, calculateTotalChunks(10L * 1024 * 1024))
        // 10MB + 1 → 6 chunks
        assertEquals(6, calculateTotalChunks(10L * 1024 * 1024 + 1))
    }

    @Test
    fun `calculateTotalChunks - 100MB file`() {
        // 100MB → 50 chunks
        assertEquals(50, calculateTotalChunks(100L * 1024 * 1024))
    }

    @Test
    fun `chunk size constant is 2MB`() {
        assertEquals(2 * 1024 * 1024, ChunkUploader.CHUNK_SIZE)
    }

    @Test
    fun `max retries is 3`() {
        assertEquals(3, ChunkUploader.MAX_RETRIES)
    }

    @Test
    fun `retry delay is 30 seconds`() {
        assertEquals(30_000L, ChunkUploader.RETRY_DELAY_MS)
    }

    @Test
    fun `session expire days is 7`() {
        assertEquals(7, ChunkUploader.SESSION_EXPIRE_DAYS)
    }

    /**
     * Helper that mirrors the private calculateTotalChunks logic in ChunkUploader.
     */
    private fun calculateTotalChunks(fileSize: Long): Int {
        val chunkSize = ChunkUploader.CHUNK_SIZE.toLong()
        return ((fileSize + chunkSize - 1) / chunkSize).toInt()
    }
}
