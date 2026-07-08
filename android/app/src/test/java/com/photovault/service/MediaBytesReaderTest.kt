package com.photovault.service

import android.Manifest
import android.net.Uri
import android.provider.MediaStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.function.Supplier

/**
 * Interaction tests for [MediaBytesReader] (R5.1 / R5.4 / R5.6).
 *
 * These exercise the "prefer original bytes + safe fallback" contract against a
 * real Robolectric [android.content.ContentResolver] whose streams are injected
 * via `ShadowContentResolver`:
 *
 *  - API < 29 → plain open (no setRequireOriginal), permission reported false.
 *  - API >= 29 + ACCESS_MEDIA_LOCATION granted → opens the
 *    `MediaStore.setRequireOriginal(uri)`-derived URI, permission reported true.
 *  - API >= 29 + permission denied → plain open, permission reported false.
 *  - API >= 29 + the original-bytes open throws → falls back to a plain open.
 *
 * ## Test seam notes
 * `MediaStore.setRequireOriginal(uri)` is pure Java (it appends a query
 * parameter), so it runs unchanged under Robolectric. The test computes the
 * exact modified URI itself and registers content against it, so the assertions
 * stay robust to whatever transformation the platform applies. To distinguish
 * "went through setRequireOriginal" from "plain open", each path registers a
 * DISTINCT byte payload under a DISTINCT URI — reading the wrong URI in
 * Robolectric yields an UnregisteredInputStream that throws on read, so a
 * successful read of the expected bytes proves which branch executed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MediaBytesReaderTest {

    private val reader = MediaBytesReader()
    private val uri: Uri = Uri.parse("content://media/external/images/media/42")

    private val originalBytes = "ORIGINAL-with-location-exif".toByteArray()
    private val plainBytes = "PLAIN-redacted-fallback".toByteArray()

    // API < 29: plain open, no setRequireOriginal, permission reported false. (R5.6)
    @Test
    @Config(sdk = [26])
    fun `api below 29 opens plainly and reports no permission`() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(plainBytes))

        val bytes = reader.openOriginal(context, uri).use { it.readBytes() }

        assertArrayEquals("api<29 should read the plain stream", plainBytes, bytes)
        assertFalse(
            "ACCESS_MEDIA_LOCATION does not exist below API 29",
            reader.hasMediaLocationPermission(context)
        )
    }

    // API >= 29 with permission: opens the setRequireOriginal-derived URI. (R5.1)
    @Test
    @Config(sdk = [30])
    fun `api 29 plus with permission opens the original uri`() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)

        // Register the original bytes ONLY under the setRequireOriginal-derived
        // URI. If the reader opened the plain URI instead, the read would throw.
        val requireOriginalUri = MediaStore.setRequireOriginal(uri)
        shadowOf(context.contentResolver)
            .registerInputStream(requireOriginalUri, ByteArrayInputStream(originalBytes))

        assertTrue(reader.hasMediaLocationPermission(context))

        val stream = reader.openOriginal(context, uri)
        assertNotNull(stream)
        val bytes = stream.use { it.readBytes() }
        assertArrayEquals(
            "with permission the reader must open the setRequireOriginal-derived URI",
            originalBytes,
            bytes
        )
    }

    // API >= 29 with permission DENIED: plain open, permission reported false. (R5.4)
    @Test
    @Config(sdk = [30])
    fun `api 29 plus without permission falls back to plain open`() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)

        // Register plain bytes ONLY under the plain URI. Opening the
        // setRequireOriginal-derived URI would throw on read.
        shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(plainBytes))

        assertFalse(reader.hasMediaLocationPermission(context))

        val bytes = reader.openOriginal(context, uri).use { it.readBytes() }
        assertArrayEquals(
            "without permission the reader must do a plain open",
            plainBytes,
            bytes
        )
    }

    // API >= 29 with permission, but the original-bytes open throws: fall back to
    // a plain open rather than propagating the error. (R5.4)
    @Test
    @Config(sdk = [30])
    fun `api 29 plus falls back to plain open when setRequireOriginal open throws`() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)

        val requireOriginalUri = MediaStore.setRequireOriginal(uri)
        // This test drives the exception path, which requires the original-bytes
        // URI to differ from the plain URI (setRequireOriginal appends a query
        // parameter on real platforms / Robolectric). Document the dependency.
        assumeTrue(
            "setRequireOriginal must produce a distinct URI to exercise the fallback",
            requireOriginalUri != uri
        )

        // Opening the original-bytes URI throws at open time → reader catches it
        // and falls back to the plain open.
        shadowOf(context.contentResolver).registerInputStreamSupplier(
            requireOriginalUri,
            Supplier { throw UnsupportedOperationException("device/URI unsupported") }
        )
        shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(plainBytes))

        val bytes = reader.openOriginal(context, uri).use { it.readBytes() }
        assertArrayEquals(
            "an error on the original-bytes path must fall back to a plain open",
            plainBytes,
            bytes
        )
    }
}
