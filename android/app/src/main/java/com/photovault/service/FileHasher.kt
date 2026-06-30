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
class FileHasher @Inject constructor() {

    companion object {
        private const val BUFFER_SIZE = 8 * 1024 // 8KB read buffer
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

        context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        } ?: throw IllegalArgumentException("Cannot open file: $fileUri")

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

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
