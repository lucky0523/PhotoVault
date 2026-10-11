package com.huoyi.photovault.ui.common

import androidx.annotation.StringRes
import com.huoyi.photovault.R
import com.huoyi.photovault.data.network.endpoint.AddressError

/** Which address input an [AddressError] is shown under (design §3.1). */
enum class AddressField { LOGIN_PUBLIC, LOGIN_LAN, SETTINGS_LAN }

/**
 * Maps [AddressError] to user-facing text. Only [AddressError.SAME_AS_OTHER] depends on
 * the field, because it names the *other* field：登录页公网框「与局域网地址相同」、
 * 登录页局域网框「与公网地址相同」、设置页「与服务器地址相同」（FR-1.4）。
 * Everything else shares one string across fields. Pure, so it is JVM-testable.
 */
object AddressErrorText {

    @StringRes
    fun resIdFor(error: AddressError, field: AddressField): Int = when (error) {
        AddressError.TOO_LONG -> R.string.validation_address_too_long
        AddressError.BAD_SCHEME -> R.string.validation_address_bad_scheme
        AddressError.INVALID -> R.string.validation_address_invalid
        AddressError.BAD_PORT -> R.string.validation_address_bad_port
        AddressError.HAS_EXTRA -> R.string.validation_address_extra
        AddressError.SAME_AS_OTHER -> when (field) {
            AddressField.LOGIN_PUBLIC -> R.string.validation_address_same_as_lan
            AddressField.LOGIN_LAN -> R.string.validation_address_same_as_public
            AddressField.SETTINGS_LAN -> R.string.validation_address_same_as_server
        }
    }
}
