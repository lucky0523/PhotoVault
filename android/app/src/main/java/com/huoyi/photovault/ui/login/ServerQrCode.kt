package com.huoyi.photovault.ui.login

import androidx.annotation.StringRes
import com.huoyi.photovault.R
import com.huoyi.photovault.data.network.endpoint.HostScope
import com.huoyi.photovault.data.network.endpoint.ServerAddress
import com.huoyi.photovault.data.network.endpoint.ValidationResult
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Result of scanning a server QR code.
 *
 * 服务器二维码协议 v1，见 protocol/README.md 与 protocol/server-qr-v1.json。
 */
sealed interface ServerQrParseResult {
    /** At least one address is non-null; both are canonical. [legacy] = true for an old single-URL code. */
    data class Success(
        val publicAddress: String?,
        val lanAddress: String?,
        val legacy: Boolean
    ) : ServerQrParseResult

    data object VersionTooNew : ServerQrParseResult
    data object Invalid : ServerQrParseResult
}

/**
 * Pure parser for the server QR code (qr-dual-address design §6.1); the behaviour is
 * pinned by the shared `decode` vectors. Depends only on [ServerAddress] (OkHttp
 * `HttpUrl`) and the JDK — no android.* runtime API, so it runs in plain JVM tests.
 *
 * 服务器二维码协议 v1，见 protocol/README.md 与 protocol/server-qr-v1.json。
 */
object ServerQrCode {
    const val PREFIX = "photovault://connect?"
    const val SUPPORTED_VERSION = 1

    /**
     * 合法 v1 载荷最长 563 字节（设计 §2.3）；为同版本新增的可选参数留出余量，超过 2048 个字符的
     * 扫描结果直接判为 Invalid，不逐字解析。
     */
    const val MAX_RAW_LENGTH = 2048

    private const val KEY_VERSION = "v"
    private const val KEY_PUBLIC = "public"
    private const val KEY_LAN = "lan"
    private val COLLECTED_KEYS = setOf(KEY_VERSION, KEY_PUBLIC, KEY_LAN)

    // Whole-string match only (Regex.matches), so "1\n" is rejected.
    private val VERSION_REGEX = Regex("[1-9][0-9]{0,8}")

    fun parse(raw: String): ServerQrParseResult {
        val s = raw.trim()
        if (s.isEmpty() || s.length > MAX_RAW_LENGTH) return ServerQrParseResult.Invalid
        return if (s.startsWith("photovault:", ignoreCase = true)) parseV1(s) else parseLegacy(s)
    }

    private fun parseV1(s: String): ServerQrParseResult {
        if (!s.startsWith(PREFIX, ignoreCase = true)) return ServerQrParseResult.Invalid
        val query = s.substring(PREFIX.length)
        if (query.isEmpty() || query.contains('#')) return ServerQrParseResult.Invalid

        // Collect only v / public / lan, raw (undecoded). Every other key, including the
        // empty key, is dropped together with its value without looking at it.
        val params = HashMap<String, MutableList<String>>()
        for (segment in query.split('&')) {
            if (segment.isEmpty()) continue
            val eq = segment.indexOf('=')
            val key = if (eq >= 0) segment.substring(0, eq) else segment
            val value = if (eq >= 0) segment.substring(eq + 1) else ""
            if (key in COLLECTED_KEYS) params.getOrPut(key) { mutableListOf() }.add(value)
        }

        // Version first, before any address is decoded.
        val versions = params[KEY_VERSION]
        if (versions == null || versions.size != 1 || !VERSION_REGEX.matches(versions[0])) {
            return ServerQrParseResult.Invalid
        }
        if (versions[0].toInt() != SUPPORTED_VERSION) return ServerQrParseResult.VersionTooNew

        val public = addressParam(params[KEY_PUBLIC])
        val lan = addressParam(params[KEY_LAN])
        if (public is AddressParam.Bad || lan is AddressParam.Bad) return ServerQrParseResult.Invalid
        val publicCanonical = (public as? AddressParam.Value)?.canonical
        val lanCanonical = (lan as? AddressParam.Value)?.canonical
        if (publicCanonical == null && lanCanonical == null) return ServerQrParseResult.Invalid
        if (publicCanonical == lanCanonical) return ServerQrParseResult.Invalid
        return ServerQrParseResult.Success(publicCanonical, lanCanonical, legacy = false)
    }

    private sealed interface AddressParam {
        data object Absent : AddressParam
        data object Bad : AddressParam
        data class Value(val canonical: String) : AddressParam
    }

    private fun addressParam(values: List<String>?): AddressParam {
        if (values == null) return AddressParam.Absent
        if (values.size > 1) return AddressParam.Bad
        val decoded = percentDecodeStrict(values[0]) ?: return AddressParam.Bad
        // Leading/trailing whitespace is dropped before the scheme check, matching the
        // server's strip() (design-review finding 1).
        val candidate = decoded.trim()
        if (candidate.isEmpty()) return AddressParam.Absent
        if (!candidate.startsWith("http://", ignoreCase = true) &&
            !candidate.startsWith("https://", ignoreCase = true)
        ) {
            return AddressParam.Bad
        }
        val result = ServerAddress.validate(candidate, other = null)
        val canonical = (result as? ValidationResult.Ok)?.canonical ?: return AddressParam.Bad
        return AddressParam.Value(canonical)
    }

    /** Old single-URL QR codes (design §6.1 step 4): routed to a field by [ServerAddress.hostScope]. */
    private fun parseLegacy(s: String): ServerQrParseResult {
        val result = ServerAddress.validate(s, other = null)
        val canonical = (result as? ValidationResult.Ok)?.canonical ?: return ServerQrParseResult.Invalid
        if (!s.contains("://")) {
            // Scheme-less text must look like an IP literal or a dotted name, so "hello" or
            // "localhost:8000" is not taken for an address. Checked on OkHttp's host, not the
            // canonical string (whose "http:" would always contain ':').
            val host = canonical.toHttpUrl().host
            if (!host.contains('.') && !host.contains(':')) return ServerQrParseResult.Invalid
        }
        return when (ServerAddress.hostScope(canonical)) {
            HostScope.PUBLIC -> ServerQrParseResult.Success(canonical, null, legacy = true)
            HostScope.LAN, HostScope.LOOPBACK -> ServerQrParseResult.Success(null, canonical, legacy = true)
        }
    }

    /**
     * Strict percent-decoding: every '%' must be followed by two hex digits and the bytes
     * must be valid UTF-8 (no U+FFFD substitution). '+' stays a literal '+'. Returns null
     * on any violation. Deliberately not URLDecoder / String(bytes, UTF_8), both lenient.
     */
    private fun percentDecodeStrict(value: String): String? {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            if (value[i] == '%') {
                if (i + 2 >= value.length) return null
                val hi = hexValue(value[i + 1])
                val lo = hexValue(value[i + 2])
                if (hi < 0 || lo < 0) return null
                out.write((hi shl 4) or lo)
                i += 3
            } else {
                val next = value.indexOf('%', i).let { if (it < 0) value.length else it }
                // Whole segment at once so surrogate pairs are never split.
                out.write(value.substring(i, next).toByteArray(Charsets.UTF_8))
                i = next
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(out.toByteArray()))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    /** [0-9A-Fa-f] only (Character.digit would also accept non-ASCII digits). */
    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'F' -> c - 'A' + 10
        in 'a'..'f' -> c - 'a' + 10
        else -> -1
    }
}

/** 扫码失败时要显示的文案资源；成功时返回 null（FR-4.5）。 */
@StringRes
fun qrScanErrorRes(result: ServerQrParseResult): Int? = when (result) {
    is ServerQrParseResult.Success -> null
    ServerQrParseResult.VersionTooNew -> R.string.login_qr_version_too_new
    ServerQrParseResult.Invalid -> R.string.login_qr_invalid
}

/**
 * onQrScanned 的状态变换。成功：覆盖两个框（载荷缺省的一项清空），并清除两个字段错误、两个测试结果和
 * errorMessage。失败：只设置 errorMessage = getText(qrScanErrorRes(result))，输入框保持不变。
 * getText 在生产代码中是 context::getString。用户名、密码及其错误不受影响。
 */
fun LoginUiState.withQrScan(result: ServerQrParseResult, getText: (Int) -> String): LoginUiState =
    when (result) {
        is ServerQrParseResult.Success -> copy(
            serverAddress = result.publicAddress.orEmpty(),
            lanAddress = result.lanAddress.orEmpty(),
            serverAddressError = null,
            lanAddressError = null,
            publicTestResult = null,
            lanTestResult = null,
            errorMessage = null
        )
        ServerQrParseResult.VersionTooNew, ServerQrParseResult.Invalid ->
            copy(errorMessage = getText(qrScanErrorRes(result)!!))
    }
