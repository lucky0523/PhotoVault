package com.photovault.ui.navigation

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trip tests for [Routes.folderDetail] argument encoding.
 *
 * Compose Navigation applies a single [Uri.decode] to path arguments when it
 * parses a route, so the route builder must encode such that ONE decode restores
 * the original value exactly. Previously the value was double-decoded (URLEncoder
 * + Navigation decode + a manual URLDecoder.decode), which corrupted SAF tree
 * URIs — e.g. turning "primary%3ADCIM%2FCamera" into "primary:DCIM/Camera" and
 * dropping the "Camera" segment during upload.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NavGraphRouteTest {

    /** Simulates how Navigation extracts a single path argument: split + one decode. */
    private fun decodedArg(route: String, index: Int): String =
        Uri.decode(route.split("/")[index])

    @Test
    fun `folderUri round-trips through one decode (SAF encoded form preserved)`() {
        val original =
            "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera"
        val route = Routes.folderDetail(1L, "Camera", original, 0)
        // route = folder_detail/{id}/{name}/{uri}/{backedUp}
        assertEquals(original, decodedArg(route, 3))
    }

    @Test
    fun `folderName with spaces round-trips`() {
        val original =
            "content://com.android.externalstorage.documents/tree/primary%3APictures"
        val route = Routes.folderDetail(2L, "My Camera Folder", original, 3)
        assertEquals("My Camera Folder", decodedArg(route, 2))
        assertEquals(original, decodedArg(route, 3))
    }

    @Test
    fun `encoded segments contain no raw slashes so the route splits cleanly`() {
        val original =
            "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera%2FSub"
        val route = Routes.folderDetail(3L, "a/b name", original, 1)
        val parts = route.split("/")
        // folder_detail + 4 args = 5 segments; no raw '/' leaked from encoded args.
        assertEquals(5, parts.size)
        assertEquals("folder_detail", parts[0])
        assertEquals("3", parts[1])
        assertEquals("a/b name", decodedArg(route, 2))
        assertEquals(original, decodedArg(route, 3))
        assertEquals("1", parts[4])
    }
}
