package com.photovault.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encapsulates "prefer original bytes + safe fallback" input-stream opening.
 *
 * This is the single entry point for R5: on API 29+ with ACCESS_MEDIA_LOCATION
 * granted, it requests the un-redacted original bytes (incl. location EXIF) via
 * [MediaStore.setRequireOriginal]; otherwise (permission missing, unsupported
 * device/URI, or any error) it falls back to a plain open and logs why. On
 * API < 29 it opens plainly since openInputStream already returns the full
 * original file there. (R5.1 / R5.4 / R5.6)
 */
@Singleton
class MediaBytesReader @Inject constructor() {

    companion object {
        private const val TAG = "PhotoVaultBackup"
    }

    /**
     * Opens an [InputStream] for [uri], requesting the un-redacted original bytes
     * on API 29+ when possible. Never returns null: if even the plain open fails
     * to produce a stream, an [IOException] is thrown so callers' existing catch
     * converts it into a retryable failure.
     */
    fun openOriginal(context: Context, uri: Uri): InputStream {
        val resolver = context.contentResolver

        // API < 29: openInputStream already returns the full original file. (R5.6)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return resolver.openInputStream(uri)
                ?: throw IOException("Cannot open source: $uri")
        }

        // API >= 29 but permission not granted: plain open + log fallback. (R5.4)
        if (!hasMediaLocationPermission(context)) {
            Log.i(TAG, "openOriginal fallback (ACCESS_MEDIA_LOCATION not granted) for $uri")
            return resolver.openInputStream(uri)
                ?: throw IOException("Cannot open source: $uri")
        }

        // API >= 29 with permission: try to request the original bytes. (R5.1)
        try {
            val original = MediaStore.setRequireOriginal(uri)
            val stream = resolver.openInputStream(original)
            if (stream != null) {
                return stream
            }
            Log.i(TAG, "openOriginal fallback (setRequireOriginal open returned null) for $uri")
        } catch (e: Exception) {
            // e.g. UnsupportedOperationException on devices/URIs that don't support it. (R5.4)
            Log.i(TAG, "openOriginal fallback (${e.javaClass.simpleName}: ${e.message}) for $uri")
        }

        // Fallback: plain open.
        return resolver.openInputStream(uri)
            ?: throw IOException("Cannot open source: $uri")
    }

    /**
     * True if ACCESS_MEDIA_LOCATION is currently granted. On API < 29 the
     * permission doesn't exist, so this returns false (setRequireOriginal isn't
     * used pre-Q anyway; the openOriginal API<29 branch bypasses this check).
     */
    fun hasMediaLocationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_MEDIA_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
