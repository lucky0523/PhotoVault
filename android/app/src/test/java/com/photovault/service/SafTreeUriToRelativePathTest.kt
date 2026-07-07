package com.photovault.service

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [safTreeUriToRelativePath].
 *
 * Feature: rebackup-status-refresh — 重新上传路径修复。
 *
 * A re-backup's folderUri arrives URL-decoded from the navigation layer, which
 * used to make DocumentsContract.getTreeDocumentId drop everything after the
 * first "/" (e.g. "DCIM/Camera" -> "DCIM"). Both the encoded (normal backup) and
 * decoded (re-backup) forms must resolve to the same full relative path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SafTreeUriToRelativePathTest {

    @Test
    fun `encoded form (normal backup) resolves full path`() {
        val encoded =
            "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera"
        assertEquals("DCIM/Camera", safTreeUriToRelativePath(encoded))
    }

    @Test
    fun `decoded form (re-backup) resolves full path including Camera`() {
        // The navigation layer double-decodes the uri; "Camera" becomes its own
        // path segment. This is the case that previously lost "Camera".
        val decoded =
            "content://com.android.externalstorage.documents/tree/primary:DCIM/Camera"
        assertEquals("DCIM/Camera", safTreeUriToRelativePath(decoded))
    }

    @Test
    fun `both forms resolve to the same relative path`() {
        val encoded =
            "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera"
        val decoded =
            "content://com.android.externalstorage.documents/tree/primary:DCIM/Camera"
        assertEquals(safTreeUriToRelativePath(encoded), safTreeUriToRelativePath(decoded))
    }

    @Test
    fun `single-level folder resolves`() {
        val encoded =
            "content://com.android.externalstorage.documents/tree/primary%3APictures"
        assertEquals("Pictures", safTreeUriToRelativePath(encoded))
    }

    @Test
    fun `deeper nested path is preserved`() {
        val decoded =
            "content://com.android.externalstorage.documents/tree/primary:DCIM/Camera/Sub"
        assertEquals("DCIM/Camera/Sub", safTreeUriToRelativePath(decoded))
    }
}
