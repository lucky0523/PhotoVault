package com.huoyi.photovault.data.network.endpoint

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** AC-2 and design §3.1: validation, canonical form, legacy normalisation, fingerprints. */
class ServerAddressTest {

    private fun ok(raw: String, other: String? = null): String? {
        val result = ServerAddress.validate(raw, other)
        check(result is ValidationResult.Ok) { "expected Ok for '$raw' but got $result" }
        return result.canonical
    }

    private fun err(raw: String, other: String? = null): AddressError {
        val result = ServerAddress.validate(raw, other)
        check(result is ValidationResult.Err) { "expected Err for '$raw' but got $result" }
        return result.error
    }

    // ---- AC-2 valid inputs ----

    @Test
    fun `bare ip and port gets http scheme`() {
        assertEquals("http://192.168.1.10:8000", ok("192.168.1.10:8000"))
    }

    @Test
    fun `https host without port is kept`() {
        assertEquals("https://nas.local", ok("https://nas.local"))
    }

    @Test
    fun `ipv6 literal keeps brackets`() {
        assertEquals("http://[fd00::10]:8000", ok("http://[fd00::10]:8000"))
    }

    @Test
    fun `canonical form lowercases host, drops default port and trailing slash`() {
        assertEquals("http://nas.local", ok("  HTTP://NAS.Local:80/  "))
        assertEquals("https://nas.local", ok("https://nas.local:443"))
        assertEquals("https://nas.local:80", ok("https://nas.local:80"))
    }

    // ---- AC-2 invalid inputs ----

    @Test
    fun `non http scheme is rejected`() {
        assertEquals(AddressError.BAD_SCHEME, err("ftp://x"))
    }

    @Test
    fun `scheme without host is invalid`() {
        assertEquals(AddressError.INVALID, err("http://"))
    }

    @Test
    fun `out of range port is BAD_PORT not INVALID`() {
        assertEquals(AddressError.BAD_PORT, err("http://a:70000"))
        assertEquals(AddressError.BAD_PORT, err("a:0"))
        assertEquals(AddressError.BAD_PORT, err("http://a:123456"))
        assertEquals(AddressError.BAD_PORT, err("http://[fd00::10]:99999"))
    }

    // ---- PORT_REGEX regression (qr-dual-address design §3.3) ----

    @Test
    fun `port-less ipv6 with a single zero group is not read as a port`() {
        assertEquals("http://[2001:db8::1]", ok("http://[2001:0db8::1]"))
    }

    @Test
    fun `port-less teredo ipv6 passes`() {
        assertEquals(
            "http://[2001:0:4136:e378:8000:63bf:3fff:fdd2]",
            ok("http://[2001:0:4136:e378:8000:63bf:3fff:fdd2]")
        )
    }

    @Test
    fun `ipv6 with out of range port is still BAD_PORT`() {
        assertEquals(AddressError.BAD_PORT, err("http://[fd00::10]:99999"))
    }

    @Test
    fun `path query and userinfo are rejected`() {
        assertEquals(AddressError.HAS_EXTRA, err("http://a/path"))
        assertEquals(AddressError.HAS_EXTRA, err("http://a?q=1"))
        assertEquals(AddressError.HAS_EXTRA, err("http://u:p@a"))
        assertEquals(AddressError.HAS_EXTRA, err("http://a#frag"))
    }

    @Test
    fun `longer than 255 characters is rejected`() {
        val raw = "http://" + "a".repeat(249) // 256 chars
        assertEquals(256, raw.length)
        assertEquals(AddressError.TOO_LONG, err(raw))
        // exactly 255 passes the length rule and fails a later one
        val atLimit = "http://a/" + "b".repeat(246)
        assertEquals(255, atLimit.length)
        assertEquals(AddressError.HAS_EXTRA, err(atLimit))
    }

    @Test
    fun `same endpoint as the other field is rejected after normalisation`() {
        assertEquals(AddressError.SAME_AS_OTHER, err("192.168.1.10:8000", other = "http://192.168.1.10:8000/"))
        assertEquals(AddressError.SAME_AS_OTHER, err("http://NAS.local", other = "nas.local:80"))
        assertEquals("http://nas.local:8000", ok("http://nas.local:8000", other = "nas.local"))
        assertEquals("https://nas.local", ok("https://nas.local", other = "http://nas.local"))
    }

    @Test
    fun `blank or unparseable other skips the same-as-other rule`() {
        assertEquals("http://a", ok("http://a", other = null))
        assertEquals("http://a", ok("http://a", other = ""))
        assertEquals("http://a", ok("http://a", other = "   "))
        assertEquals("http://a", ok("http://a", other = "http://"))
    }

    @Test
    fun `blank input means clear`() {
        assertEquals(null, ok(""))
        assertEquals(null, ok("   "))
    }

    // ---- legacyNormalize ----

    /** Verbatim copy of the five historical normalize implementations. */
    private fun historicalNormalize(address: String): String {
        var url = address.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        if (!url.endsWith("/")) {
            url = "$url/"
        }
        return url
    }

    @Test
    fun `legacyNormalize matches historical implementation`() {
        val inputs = listOf(
            "192.168.1.10:8000", " http://nas.local ", "https://nas.local/", "nas.local/sub",
            "HTTP://upper", "", "  ", "http://", "ftp://x", "[fd00::10]:8000", "https://a/b/"
        )
        for (input in inputs) {
            assertEquals("input '$input'", historicalNormalize(input), ServerAddress.legacyNormalize(input))
        }
    }

    // ---- fingerprints ----

    @Test
    fun `instance fingerprint matches shared test vectors`() {
        assertEquals("0aa7ca809fe5773d5f9134544860ff3c", ServerAddress.instanceFingerprint(TEST_INSTANCE_ID))
        assertEquals(
            "08cf6b7fb5c87de79237818508128056",
            ServerAddress.instanceFingerprint("3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d")
        )
    }

    @Test
    fun `image base url carries 16 hex chars of the fingerprint`() {
        val a = ServerAddress.imageBaseUrlFor(TEST_INSTANCE_ID)
        assertEquals("http://0aa7ca809fe5773d.photovault.invalid", a)
        assertEquals(a, ServerAddress.imageBaseUrlFor(TEST_INSTANCE_ID))
        val b = ServerAddress.imageBaseUrlFor("3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d")
        assertNotEquals(a, b)
        assertNotNull(a.toHttpUrlOrNull())
        assertNotNull(b.toHttpUrlOrNull())
    }

    @Test
    fun `image base url without instance id is the bare placeholder`() {
        assertEquals("http://photovault.invalid", ServerAddress.imageBaseUrlFor(null))
        assertEquals("http://photovault.invalid", ServerAddress.imageBaseUrlFor("  "))
        assertNotNull(ServerAddress.imageBaseUrlFor(null).toHttpUrlOrNull())
    }
}
