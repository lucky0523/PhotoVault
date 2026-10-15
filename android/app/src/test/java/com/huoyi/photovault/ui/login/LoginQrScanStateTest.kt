package com.huoyi.photovault.ui.login

import com.huoyi.photovault.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AC-16 (state half): withQrScan state changes and the result → string-resource mapping.
 * Plain JVM; getText echoes the resource id so a swapped mapping fails.
 */
class LoginQrScanStateTest {

    private val getText: (Int) -> String = { id -> "res:$id" }

    private val before = LoginUiState(
        serverAddress = "https://old.example.com",
        lanAddress = "http://10.0.0.9:8000",
        username = "alice",
        password = "secret",
        errorMessage = "登录失败",
        publicTestResult = TestConnectionResult.Success,
        lanTestResult = TestConnectionResult.Failure("timeout"),
        serverAddressError = "地址格式不正确",
        lanAddressError = "与公网地址相同",
        usernameError = "请输入用户名"
    )

    @Test
    fun `public-only v1 scan overwrites both fields and clears errors and test results`() {
        val result = ServerQrCode.parse("photovault://connect?v=1&public=https%3A%2F%2Fphotos.example.com")
        val after = before.withQrScan(result, getText)
        assertEquals(
            before.copy(
                serverAddress = "https://photos.example.com",
                lanAddress = "",
                serverAddressError = null,
                lanAddressError = null,
                publicTestResult = null,
                lanTestResult = null,
                errorMessage = null
            ),
            after
        )
    }

    @Test
    fun `version too new keeps the fields and shows the version message`() {
        val after = before.withQrScan(ServerQrParseResult.VersionTooNew, getText)
        assertEquals(before.copy(errorMessage = "res:${R.string.login_qr_version_too_new}"), after)
    }

    @Test
    fun `invalid content keeps the fields and shows the invalid message`() {
        val after = before.withQrScan(ServerQrParseResult.Invalid, getText)
        assertEquals(before.copy(errorMessage = "res:${R.string.login_qr_invalid}"), after)
    }

    @Test
    fun `success has no error resource`() {
        assertNull(qrScanErrorRes(ServerQrParseResult.Success("https://a.example.com", null, legacy = false)))
    }
}
