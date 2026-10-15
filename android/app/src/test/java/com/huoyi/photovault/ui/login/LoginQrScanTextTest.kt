package com.huoyi.photovault.ui.login

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * AC-16 (text half): scan errors resolve to the FR-4.5 Chinese wording from the real
 * values-zh resources, and the reducer leaves both fields untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "zh")
class LoginQrScanTextTest {

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private val before = LoginUiState(
        serverAddress = "https://old.example.com",
        lanAddress = "http://10.0.0.9:8000"
    )

    @Test
    fun `error resources carry the chinese wording`() {
        val context = context()
        assertEquals(
            "二维码版本过新，请升级 App 后再扫描",
            context.getString(qrScanErrorRes(ServerQrParseResult.VersionTooNew)!!)
        )
        assertEquals(
            "无法识别的服务器二维码",
            context.getString(qrScanErrorRes(ServerQrParseResult.Invalid)!!)
        )
    }

    @Test
    fun `failed scans keep the fields and show the chinese wording`() {
        val context = context()
        val invalid = before.withQrScan(ServerQrParseResult.Invalid, context::getString)
        assertEquals(before.copy(errorMessage = "无法识别的服务器二维码"), invalid)
        val tooNew = before.withQrScan(ServerQrParseResult.VersionTooNew, context::getString)
        assertEquals(before.copy(errorMessage = "二维码版本过新，请升级 App 后再扫描"), tooNew)
    }
}
