package com.photovault.ui.main.tabs

import com.photovault.data.local.entity.UploadRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [UploadRecord.toFileInfo] and [guessMimeTypeFromFileName], the
 * pure reconstruction used by [TasksTabViewModel.resumePausedTask] to re-enqueue
 * an AUTO_OFF Paused_Task (task 27.6). Property 23 (27.10) covers the field
 * mapping exhaustively; these examples pin the mapping and MIME fallbacks.
 */
class UploadRecordToFileInfoTest {

    private fun record(
        fileUri: String = "content://media/1",
        fileName: String = "photo.jpg",
        fileSize: Long = 2048L,
        fileModifiedTime: Long = 1_700_000_000_000L,
        folderUri: String = "content://tree/folderA",
        mimeType: String = "image/jpeg"
    ): UploadRecord = UploadRecord(
        fileUri = fileUri,
        sessionId = "session",
        fileHash = "hash",
        fileName = fileName,
        fileSize = fileSize,
        fileModifiedTime = fileModifiedTime,
        folderUri = folderUri,
        mimeType = mimeType,
        totalChunks = 4,
        uploadedChunkIndex = 1
    )

    @Test
    fun `maps all fields including stored mime type`() {
        val info = record().toFileInfo()

        assertEquals("content://media/1", info.uri)
        assertEquals("photo.jpg", info.fileName)
        assertEquals(2048L, info.fileSize)
        // createdTime comes from the persisted fileModifiedTime column.
        assertEquals(1_700_000_000_000L, info.createdTime)
        assertEquals("content://tree/folderA", info.folderUri)
        assertEquals("image/jpeg", info.mimeType)
        // Resume path never force-reuploads (that is the trash re-backup path).
        assertEquals(false, info.forceReupload)
    }

    @Test
    fun `blank mime type is guessed from extension`() {
        val info = record(fileName = "clip.mp4", mimeType = "").toFileInfo()
        assertEquals("video/mp4", info.mimeType)
    }

    @Test
    fun `unknown extension falls back to image wildcard`() {
        val info = record(fileName = "mystery.xyz", mimeType = "").toFileInfo()
        assertEquals("image/*", info.mimeType)

        val noExt = record(fileName = "noextension", mimeType = "").toFileInfo()
        assertEquals("image/*", noExt.mimeType)
    }

    @Test
    fun `guess is case insensitive for common photo types`() {
        assertEquals("image/jpeg", guessMimeTypeFromFileName("IMG_0001.JPG"))
        assertEquals("image/jpeg", guessMimeTypeFromFileName("a.jpeg"))
        assertEquals("image/png", guessMimeTypeFromFileName("Screenshot.PNG"))
        assertEquals("image/heic", guessMimeTypeFromFileName("live.HEIC"))
        assertEquals("video/quicktime", guessMimeTypeFromFileName("movie.MOV"))
    }
}
