package com.huoyi.photovault.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LocalNetworkPermissionTest {

    @Test
    fun permissionName() {
        assertEquals("android.permission.ACCESS_LOCAL_NETWORK", LocalNetworkPermission.PERMISSION)
    }

    @Test
    fun isRequiredOnlyFromApi37() {
        assertFalse(LocalNetworkPermission.isRequired(26))
        assertFalse(LocalNetworkPermission.isRequired(34))
        assertFalse(LocalNetworkPermission.isRequired(36))
        assertTrue(LocalNetworkPermission.isRequired(37))
        assertTrue(LocalNetworkPermission.isRequired(38))
    }

    @Test
    fun denialClassification() {
        assertNull(LocalNetworkPermission.denialAfterRequest(granted = true, shouldShowRationale = false))
        assertEquals(
            LocalNetworkPermission.Denial.RETRYABLE,
            LocalNetworkPermission.denialAfterRequest(granted = false, shouldShowRationale = true)
        )
        assertEquals(
            LocalNetworkPermission.Denial.PERMANENT,
            LocalNetworkPermission.denialAfterRequest(granted = false, shouldShowRationale = false)
        )
    }

    @Test
    fun isGrantedBelowApi37IsNoOp() {
        assertTrue(LocalNetworkPermission.isGranted(RuntimeEnvironment.getApplication()))
    }
}
