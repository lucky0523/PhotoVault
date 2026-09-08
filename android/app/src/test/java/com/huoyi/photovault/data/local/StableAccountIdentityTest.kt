package com.huoyi.photovault.data.local

import com.google.gson.Gson
import com.huoyi.photovault.data.api.model.LoginResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StableAccountIdentityTest {

    @Test
    fun `same instance and user keeps local state even when endpoint changes`() {
        val saved = StableAccountIdentity("instance-a", 7L)
        val incoming = StableAccountIdentity("instance-a", 7L)

        assertFalse(AccountSessionManager.hasSessionChanged(saved, incoming))
    }

    @Test
    fun `different instance or user changes session`() {
        val saved = StableAccountIdentity("instance-a", 7L)

        assertTrue(
            AccountSessionManager.hasSessionChanged(
                saved,
                StableAccountIdentity("instance-b", 7L)
            )
        )
        assertTrue(
            AccountSessionManager.hasSessionChanged(
                saved,
                StableAccountIdentity("instance-a", 8L)
            )
        )
    }

    @Test
    fun `missing saved identity performs one conservative migration reset`() {
        assertTrue(
            AccountSessionManager.hasSessionChanged(
                previous = null,
                incoming = StableAccountIdentity("instance-a", 7L)
            )
        )
    }

    @Test
    fun `login response maps stable identity fields`() {
        val response = Gson().fromJson(
            """{
                "access_token":"access",
                "refresh_token":"refresh",
                "expires_in":3600,
                "instance_id":"550e8400-e29b-41d4-a716-446655440000",
                "user_id":42
            }""".trimIndent(),
            LoginResponse::class.java
        )

        assertEquals("550e8400-e29b-41d4-a716-446655440000", response.instanceId)
        assertEquals(42L, response.userId)
    }

    @Test
    fun `legacy login response leaves stable identity absent`() {
        val response = Gson().fromJson(
            """{
                "access_token":"access",
                "refresh_token":"refresh",
                "expires_in":3600
            }""".trimIndent(),
            LoginResponse::class.java
        )

        assertNull(response.instanceId)
        assertNull(response.userId)
    }
}
