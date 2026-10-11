package com.huoyi.photovault.ui.common

import com.huoyi.photovault.R
import com.huoyi.photovault.data.network.endpoint.AddressError
import org.junit.Assert.assertEquals
import org.junit.Test

class AddressErrorTextTest {

    @Test
    fun `SAME_AS_OTHER names the other field per input`() {
        assertEquals(
            R.string.validation_address_same_as_lan,
            AddressErrorText.resIdFor(AddressError.SAME_AS_OTHER, AddressField.LOGIN_PUBLIC)
        )
        assertEquals(
            R.string.validation_address_same_as_public,
            AddressErrorText.resIdFor(AddressError.SAME_AS_OTHER, AddressField.LOGIN_LAN)
        )
        assertEquals(
            R.string.validation_address_same_as_server,
            AddressErrorText.resIdFor(AddressError.SAME_AS_OTHER, AddressField.SETTINGS_LAN)
        )
    }

    @Test
    fun `other errors map to the same shared string for every field`() {
        val expected = mapOf(
            AddressError.TOO_LONG to R.string.validation_address_too_long,
            AddressError.BAD_SCHEME to R.string.validation_address_bad_scheme,
            AddressError.INVALID to R.string.validation_address_invalid,
            AddressError.BAD_PORT to R.string.validation_address_bad_port,
            AddressError.HAS_EXTRA to R.string.validation_address_extra
        )
        assertEquals(AddressError.entries.size - 1, expected.size)
        for ((error, resId) in expected) {
            for (field in AddressField.entries) {
                assertEquals("$error / $field", resId, AddressErrorText.resIdFor(error, field))
            }
        }
    }
}
