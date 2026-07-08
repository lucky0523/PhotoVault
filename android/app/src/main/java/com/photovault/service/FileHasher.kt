package com.photovault.service

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes SHA-256 hash of files for duplicate detection and integrity verification.
 *
 * Uses streaming approach to handle large files without loading them entirely into memory.
 * Reads the file in 8KB chunks to compute the hash incrementally.
 */
@Singleton
class FileHasher @Inject constructor(
    private val mediaBytesReader: MediaBytesReader
) {

    companion object {
        private const val BUFFER_SIZE = 8 * 1024 // 8KB read buffer
    }

    /**
     * Computes the SHA-256 hash of a file identified by its content URI, along
     * with the exact number of bytes read.
     *
     * The returned size is authoritative for chunked upload: it always matches
     * the bytes covered by [HashAndSize.hash]. MediaStore's SIZE column can be
     * stale (e.g. a video indexed before it finished writing), so callers should
     * prefer this size over MediaStore's for integrity-critical operations.
     *
     * @param context Application context for content resolver access
     * @param fileUri Content URI of the file to hash
     * @return The hex-encoded SHA-256 hash and the byte count.
     * @throws java.io.IOException if the file cannot be read
     */
    suspend fun computeSha256AndSize(context: Context, fileUri: Uri): HashAndSize {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L

        mediaBytesReader.openOriginal(context, fileUri).use { inputStream ->
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
                total += bytesRead
            }
        }

        return HashAndSize(digest.digest().toHexString(), total)
    }

    /**
     * Computes the SHA-256 hash of a file identified by its content URI.
     *
     * @param context Application context for content resolver access
     * @param fileUri Content URI of the file to hash
     * @return Hex-encoded SHA-256 hash string
     * @throws java.io.IOException if the file cannot be read
     * @throws java.security.NoSuchAlgorithmException if SHA-256 is not available
     */
    suspend fun computeSha256(context: Context, fileUri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)

        mediaBytesReader.openOriginal(context, fileUri).use { inputStream ->
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }

        return digest.digest().toHexString()
    }

    /**
     * Computes the SHA-256 hash from an InputStream.
     *
     * @param inputStream The input stream to read from
     * @return Hex-encoded SHA-256 hash string
     */
    suspend fun computeSha256(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)

        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }

        return digest.digest().toHexString()
    }

    /**
     * Computes the MD5 checksum of a byte array (used for chunk verification).
     *
     * @param data The byte array to compute MD5 for
     * @return Hex-encoded MD5 hash string
     */
    fun computeMd5(data: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(data).toHexString()
    }

    /**
     * Computes the SHA-256 hash and byte count of a local [file].
     *
     * Reading from a stable on-disk snapshot (rather than a live MediaStore URI)
     * guarantees the hash matches the exact bytes that will later be chunked and
     * uploaded, even if the original file is still being finalized by the camera.
     */
    suspend fun computeSha256AndSize(file: java.io.File): HashAndSize {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L

        file.inputStream().use { inputStream ->
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
                total += bytesRead
            }
        }

        return HashAndSize(digest.digest().toHexString(), total)
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}

/**
 * Result of hashing a file: its SHA-256 hash and the exact byte count read.
 */
data class HashAndSize(val hash: String, val size: Long)
