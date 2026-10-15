package com.huoyi.photovault.ui.login

import com.huoyi.photovault.data.network.endpoint.ServerAddress
import com.huoyi.photovault.data.network.endpoint.ServerQrVectors
import com.huoyi.photovault.data.network.endpoint.ValidationResult
import com.huoyi.photovault.data.network.endpoint.str
import com.huoyi.photovault.ui.login.ServerQrParseResult.Invalid
import com.huoyi.photovault.ui.login.ServerQrParseResult.Success
import com.huoyi.photovault.ui.login.ServerQrParseResult.VersionTooNew
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ServerQrCode.parse（服务器二维码协议 v1，见 protocol/README.md 与 protocol/server-qr-v1.json）.
 * Vector-driven groups first, then a named matrix for the cases the workflow lists
 * explicitly (plan.md item 11), then Android-only extras (design §7.3, review finding 1).
 */
class ServerQrCodeTest {

    private fun parse(raw: String) = ServerQrCode.parse(raw)

    private fun String.quoted(): String = "\"" + replace("\n", "\\n") + "\""

    private fun assertNoMismatch(group: String, mismatches: List<String>) {
        assertEquals("$group mismatches:\n" + mismatches.joinToString("\n"), emptyList<String>(), mismatches)
    }

    // ---- shared vectors ----

    @Test
    fun `decode vectors`() {
        val vectors = ServerQrVectors.group("decode")
        assertEquals(50, vectors.size)
        val mismatches = vectors.mapNotNull { v ->
            val payload = v.str("payload")!!
            val expected = when (val r = v.str("result")) {
                "ok" -> Success(v.str("public"), v.str("lan"), v.get("legacy").asBoolean)
                "too_new" -> VersionTooNew
                "invalid" -> Invalid
                else -> error("unknown result '$r'")
            }
            val actual = parse(payload)
            if (actual == expected) null else "${payload.quoted()}: expected $expected, got $actual"
        }
        assertNoMismatch("decode", mismatches)
    }

    @Test
    fun `payloads vectors decode to the encoded addresses and are idempotent`() {
        val vectors = ServerQrVectors.group("payloads")
        assertEquals(5, vectors.size)
        val mismatches = mutableListOf<String>()
        for (v in vectors) {
            val public = v.str("public")
            val lan = v.str("lan")
            val payload = v.str("payload")!!
            val actual = parse(payload)
            val expected = Success(public, lan, legacy = false)
            if (actual != expected) mismatches += "$payload: expected $expected, got $actual"
            for (address in listOfNotNull(public, lan)) {
                val again = ServerAddress.validate(address, null)
                if (again != ValidationResult.Ok(address)) mismatches += "$address not idempotent: $again"
            }
        }
        assertNoMismatch("payloads", mismatches)
    }

    @Test
    fun `host_classification vectors land in the right field with the canonical value`() {
        val vectors = ServerQrVectors.group("host_classification")
        assertEquals(24, vectors.size)
        val mismatches = vectors.mapNotNull { v ->
            val address = v.str("address")!!
            val canonical = v.str("canonical")!!
            val expected = if (v.str("scope") == "public") {
                Success(canonical, null, legacy = true)
            } else {
                Success(null, canonical, legacy = true)
            }
            val actual = parse(address)
            if (actual == expected) null else "$address: expected $expected, got $actual"
        }
        assertNoMismatch("host_classification", mismatches)
    }

    // ---- named matrix: v1 payloads ----

    @Test
    fun `v1 with both addresses`() {
        assertEquals(
            Success("https://photos.example.com", "http://192.168.1.10:8000", legacy = false),
            parse("photovault://connect?v=1&public=https%3A%2F%2Fphotos.example.com&lan=http%3A%2F%2F192.168.1.10%3A8000")
        )
    }

    @Test
    fun `v1 with public only`() {
        assertEquals(
            Success("https://photos.example.com", null, legacy = false),
            parse("photovault://connect?v=1&public=https%3A%2F%2Fphotos.example.com")
        )
    }

    @Test
    fun `v1 with lan only`() {
        assertEquals(
            Success(null, "http://192.168.1.10:8000", legacy = false),
            parse("photovault://connect?v=1&lan=http%3A%2F%2F192.168.1.10%3A8000")
        )
    }

    // ---- named matrix: legacy single-URL codes ----

    @Test
    fun `legacy private ipv4 goes to the lan field`() {
        assertEquals(Success(null, "http://192.168.1.10:8000", legacy = true), parse("http://192.168.1.10:8000"))
    }

    @Test
    fun `legacy ipv6 ula goes to the lan field`() {
        assertEquals(Success(null, "http://[fd00::1]", legacy = true), parse("http://[fd00::1]"))
    }

    @Test
    fun `legacy public domain goes to the public field`() {
        assertEquals(Success("https://photos.example.com", null, legacy = true), parse("https://photos.example.com"))
    }

    // ---- named matrix: unknown version ----

    @Test
    fun `unknown versions are too new`() {
        assertEquals(VersionTooNew, parse("photovault://connect?v=2&public=https%3A%2F%2Fphotos.example.com"))
        assertEquals(VersionTooNew, parse("photovault://connect?v=10&public=https%3A%2F%2Fphotos.example.com"))
    }

    // ---- named matrix: garbage ----

    @Test
    fun `garbage input is invalid`() {
        for (raw in listOf("hello world", "{}", "WIFI:S:home;T:WPA;P:secret;;", "mailto:a@b.com")) {
            assertEquals(raw, Invalid, parse(raw))
        }
    }

    @Test
    fun `blank input is invalid`() {
        assertEquals(Invalid, parse(""))
        assertEquals(Invalid, parse("  \n"))
    }

    // ---- named matrix: oversized input ----

    @Test
    fun `input longer than 2048 characters is invalid`() {
        val head = "photovault://connect?v=1&public=https%3A%2F%2Fphotos.example.com&x="
        val tooLong = head + "a".repeat(2049 - head.length)
        assertEquals(2049, tooLong.length)
        assertEquals(Invalid, parse(tooLong))
        // Exactly at the limit is still parsed (the padding is an ignored unknown key).
        val atLimit = head + "a".repeat(ServerQrCode.MAX_RAW_LENGTH - head.length)
        assertEquals(Success("https://photos.example.com", null, legacy = false), parse(atLimit))
    }

    // ---- named matrix: normalisation ----

    @Test
    fun `v1 prefix and percent hex are case-insensitive and addresses are canonicalised`() {
        assertEquals(
            Success("https://photos.example.com", null, legacy = false),
            parse("PHOTOVAULT://Connect?v=1&public=HTTPS%3a%2f%2fPhotos.Example.com%3A443%2F")
        )
    }

    @Test
    fun `legacy address is canonicalised`() {
        assertEquals(Success(null, "http://nas.local:8000", legacy = true), parse("HTTP://NAS.local:8000/"))
    }

    // ---- named matrix: path / query rejected ----

    @Test
    fun `v1 address with a path is invalid`() {
        assertEquals(Invalid, parse("photovault://connect?v=1&lan=http%3A%2F%2Fa%2Fpath"))
    }

    @Test
    fun `v1 address with a query is invalid`() {
        assertEquals(Invalid, parse("photovault://connect?v=1&public=http%3A%2F%2Fa%3Fx%3D1"))
    }

    @Test
    fun `legacy address with a path or query is invalid`() {
        assertEquals(Invalid, parse("http://a/path"))
        assertEquals(Invalid, parse("http://a?x=1"))
    }

    // ---- Android-only extras (design §7.3, design-review finding 1) ----

    @Test
    fun `legacy fqdn with trailing dot goes to the lan field`() {
        assertEquals(Success(null, "http://nas.local.:8000", legacy = true), parse("http://nas.local.:8000"))
    }

    @Test
    fun `percent bytes that are not valid utf-8 are invalid`() {
        assertEquals(Invalid, parse("photovault://connect?v=1&public=http%3A%2F%2Fa&lan=http%3A%2F%2Fb%FF"))
    }

    @Test
    fun `truncated percent escapes are invalid`() {
        assertEquals(Invalid, parse("photovault://connect?v=1&public=http%3A%2F%2Fa%"))
        assertEquals(Invalid, parse("photovault://connect?v=1&public=http%3A%2F%2Fa%4"))
    }

    @Test
    fun `leading whitespace in a decoded address is trimmed before the scheme check`() {
        assertEquals(
            Success("https://photos.example.com", null, legacy = false),
            parse("photovault://connect?v=1&public=%20https%3A%2F%2Fphotos.example.com")
        )
    }
}
