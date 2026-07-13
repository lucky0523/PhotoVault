package com.photovault.util

import android.content.Context
import android.net.Uri

/**
 * Lightweight client-side Motion Photo (动态照片) detector.
 *
 * Android "Motion Photo" (and the older Google Camera "MicroVideo") store a
 * still JPEG with an MP4 video appended to the end of the same file. The
 * primary image's XMP metadata describes the embedded video:
 *
 * - Legacy Google format: `GCamera:MicroVideo="1"` + `GCamera:MicroVideoOffset`.
 * - Current format: `GCamera:MotionPhoto="1"` plus a Container whose video item
 *   has `Item:Mime="video/mp4"`.
 *
 * For the folder grid we only need a boolean marker (not the video offset), so
 * we scan just the head of the JPEG for the XMP markers — the XMP APP1 segment
 * lives near the start of the file, keeping this cheap enough to run per-image
 * off the main thread. This mirrors the server-side detection in
 * `server/app/services/motion_photo.py`.
 */
object MotionPhotoDetector {

    /**
     * How many bytes from the head of the file to scan for the XMP packet.
     * The XMP APP1 segment sits right after SOI at the very start of the JPEG,
     * so 64KB is more than enough to catch it while keeping the per-file read
     * cheap (important when scanning thousands of files).
     */
    private const val HEAD_SCAN_BYTES = 64 * 1024

    // ASCII markers written into the JPEG XMP by motion-photo capable cameras.
    private val MARKERS = listOf(
        "MotionPhoto".toByteArray(Charsets.US_ASCII),
        "MicroVideo".toByteArray(Charsets.US_ASCII)
    )

    /**
     * Returns true when [uri] points at a JPEG whose XMP declares an embedded
     * motion-photo video. Non-JPEG files return false without any I/O.
     */
    fun isMotionPhoto(context: Context, uri: Uri, mimeType: String): Boolean {
        if (mimeType != "image/jpeg" && mimeType != "image/jpg") return false

        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(HEAD_SCAN_BYTES)
                var read = 0
                while (read < HEAD_SCAN_BYTES) {
                    val r = input.read(buffer, read, HEAD_SCAN_BYTES - read)
                    if (r == -1) break
                    read += r
                }
                MARKERS.any { marker -> indexOf(buffer, read, marker) != -1 }
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Naive byte-sequence search within [data] over its first [length] bytes.
     * Returns the start index of [pattern], or -1 if not found.
     */
    private fun indexOf(data: ByteArray, length: Int, pattern: ByteArray): Int {
        if (pattern.isEmpty() || length < pattern.size) return -1
        val last = length - pattern.size
        outer@ for (i in 0..last) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
