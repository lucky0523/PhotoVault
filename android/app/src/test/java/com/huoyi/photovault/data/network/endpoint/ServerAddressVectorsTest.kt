package com.huoyi.photovault.data.network.endpoint

import com.huoyi.photovault.ui.login.planAddressCheck
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Shared-vector checks for address validation and host scope (服务器二维码协议 v1，见
 * protocol/README.md 与 protocol/server-qr-v1.json; qr-dual-address design §7.1).
 * Each test walks a whole group and reports every mismatch at once.
 */
class ServerAddressVectorsTest {

    private fun assertNoMismatch(group: String, mismatches: List<String>) {
        assertEquals("$group mismatches:\n" + mismatches.joinToString("\n"), emptyList<String>(), mismatches)
    }

    @Test
    fun `address_validation vectors match validate`() {
        val vectors = ServerQrVectors.group("address_validation")
        assertEquals(44, vectors.size)
        val mismatches = vectors.mapNotNull { v ->
            val input = v.str("input")!!
            val expected = v.str("error")
                ?.let { ValidationResult.Err(AddressError.valueOf(it)) }
                ?: ValidationResult.Ok(v.str("canonical"))
            val actual = ServerAddress.validate(input, null)
            if (actual == expected) null else "${input.quoted()}: expected $expected, got $actual"
        }
        assertNoMismatch("address_validation", mismatches)
    }

    @Test
    fun `server_web_only vectors are accepted by android with the pinned canonical`() {
        val vectors = ServerQrVectors.group("server_web_only")
        assertEquals(10, vectors.size)
        val mismatches = vectors.mapNotNull { v ->
            val input = v.str("input")!!
            val expected = ValidationResult.Ok(v.str("android_canonical")!!)
            val actual = ServerAddress.validate(input, null)
            if (actual == expected) null else "${input.quoted()}: expected $expected, got $actual"
        }
        assertNoMismatch("server_web_only", mismatches)
    }

    @Test
    fun `address_pairs vectors match planAddressCheck`() {
        val vectors = ServerQrVectors.group("address_pairs")
        assertEquals(4, vectors.size)
        val mismatches = vectors.mapNotNull { v ->
            val plan = planAddressCheck(v.str("public")!!, v.str("lan")!!)
            val expected = listOf(
                v.str("public_canonical"), v.str("public_error"),
                v.str("lan_canonical"), v.str("lan_error")
            )
            val actual = listOf(
                plan.publicCanonical, plan.publicError?.name,
                plan.lanCanonical, plan.lanError?.name
            )
            if (actual == expected) null else "$v: expected $expected, got $actual"
        }
        assertNoMismatch("address_pairs", mismatches)
    }

    @Test
    fun `host_classification vectors match hostScope`() {
        val vectors = ServerQrVectors.group("host_classification")
        assertEquals(24, vectors.size)
        val mismatches = vectors.mapNotNull { v ->
            val canonical = v.str("canonical")!!
            val expected = HostScope.valueOf(v.str("scope")!!.uppercase())
            val actual = ServerAddress.hostScope(canonical)
            if (actual == expected) null else "$canonical: expected $expected, got $actual"
        }
        assertNoMismatch("host_classification", mismatches)
    }

    // ---- Android-only (the server rejects a trailing dot, so these are not shared vectors) ----

    @Test
    fun `trailing dot is dropped before host name rules`() {
        assertEquals(HostScope.LAN, ServerAddress.hostScope("http://nas.local."))
        assertEquals(HostScope.LOOPBACK, ServerAddress.hostScope("http://localhost."))
    }

    @Test
    fun `unparseable input is public`() {
        assertEquals(HostScope.PUBLIC, ServerAddress.hostScope("not a url"))
    }

    private fun String.quoted(): String = "\"" + replace("\n", "\\n") + "\""
}
