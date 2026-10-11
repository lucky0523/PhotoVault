package com.huoyi.photovault.data.network.endpoint

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/** Reason a user-entered server address was rejected (design §3.1). Text mapping lives in the UI layer. */
enum class AddressError { TOO_LONG, BAD_SCHEME, INVALID, BAD_PORT, HAS_EXTRA, SAME_AS_OTHER }

/** Result of [ServerAddress.validate]. */
sealed class ValidationResult {
    /** Valid input. [canonical] is `scheme://host[:port]` (no trailing `/`), or null when the input was blank (= clear). */
    data class Ok(val canonical: String?) : ValidationResult()

    data class Err(val error: AddressError) : ValidationResult()
}

/**
 * Pure address helpers shared by the routing core, login and settings (design §3.1).
 * JVM-testable: relies only on OkHttp's [HttpUrl] parser and [MessageDigest].
 */
object ServerAddress {

    const val MAX_LENGTH = 255

    /** Placeholder host used by Coil requests when no instance id is known yet. */
    const val IMAGE_PLACEHOLDER_HOST = "photovault.invalid"

    private const val FINGERPRINT_PREFIX = "photovault-instance:"

    // Rule 5: extract the explicit port before HttpUrl parsing so "http://a:70000"
    // is reported as BAD_PORT instead of INVALID.
    private val PORT_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*://(\\[[^\\]]+\\]|[^/:?#@]+):(\\d+)")

    /**
     * Legacy normalisation, byte-for-byte identical to the five historical `normalize`
     * copies: trim, prefix `http://` when no http(s) scheme, ensure a trailing `/`.
     */
    fun legacyNormalize(raw: String): String {
        var url = raw.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        if (!url.endsWith("/")) {
            url = "$url/"
        }
        return url
    }

    /**
     * Validates a login/settings address field. Rules are checked in the order of the
     * design §3.1 table; [other] is the opposite field (or the stored server address)
     * and is only used for the SAME_AS_OTHER check.
     */
    fun validate(raw: String, other: String?): ValidationResult {
        val trimmed = raw.trim()
        // 1. blank = clear (caller decides whether clearing is allowed)
        if (trimmed.isEmpty()) return ValidationResult.Ok(null)
        // 2. length
        if (trimmed.length > MAX_LENGTH) return ValidationResult.Err(AddressError.TOO_LONG)
        // 3. scheme
        val schemeEnd = trimmed.indexOf("://")
        val withScheme = if (schemeEnd >= 0) {
            val scheme = trimmed.substring(0, schemeEnd).lowercase()
            if (scheme != "http" && scheme != "https") {
                return ValidationResult.Err(AddressError.BAD_SCHEME)
            }
            trimmed
        } else {
            "http://$trimmed"
        }
        // 5. explicit port (checked before parsing, see PORT_REGEX)
        PORT_REGEX.find(withScheme)?.let { match ->
            val digits = match.groupValues[2]
            if (digits.length > 5 || digits.toInt() !in 1..65535) {
                return ValidationResult.Err(AddressError.BAD_PORT)
            }
        }
        // 4. parseable with a host
        val url = withScheme.toHttpUrlOrNull()
        if (url == null || url.host.isEmpty()) return ValidationResult.Err(AddressError.INVALID)
        // 6. nothing but scheme/host/port
        if (url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null ||
            (url.encodedPath.isNotEmpty() && url.encodedPath != "/")
        ) {
            return ValidationResult.Err(AddressError.HAS_EXTRA)
        }
        // 7. must differ from the other field (skipped when other is blank/unparseable)
        val otherUrl = other?.takeIf { it.isNotBlank() }
            ?.let { legacyNormalize(it).toHttpUrlOrNull() }
            ?.takeIf { it.host.isNotEmpty() }
        if (otherUrl != null && sameEndpoint(url, otherUrl)) {
            return ValidationResult.Err(AddressError.SAME_AS_OTHER)
        }
        return ValidationResult.Ok(canonicalOf(url))
    }

    /** `scheme://host[:port]`, IPv6 in brackets, default port omitted, no trailing `/`. */
    fun canonicalOf(url: HttpUrl): String {
        val host = if (url.host.contains(':')) "[${url.host}]" else url.host
        val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
        return "${url.scheme}://$host$port"
    }

    /** First 32 lowercase hex chars of SHA-256("photovault-instance:" + instanceId); matches the server. */
    fun instanceFingerprint(instanceId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((FINGERPRINT_PREFIX + instanceId).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * Coil placeholder prefix carrying the instance fingerprint, so thumbnail cache keys
     * are stable across LAN/public switches but differ between instances (design §3.1, §5.2).
     */
    fun imageBaseUrlFor(instanceId: String?): String {
        if (instanceId.isNullOrBlank()) return "http://$IMAGE_PLACEHOLDER_HOST"
        return "http://${instanceFingerprint(instanceId).take(16)}.$IMAGE_PLACEHOLDER_HOST"
    }

    private fun sameEndpoint(a: HttpUrl, b: HttpUrl): Boolean =
        a.scheme == b.scheme && a.host.lowercase() == b.host.lowercase() && a.port == b.port
}
