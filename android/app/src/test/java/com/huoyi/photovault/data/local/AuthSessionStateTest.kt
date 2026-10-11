package com.huoyi.photovault.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [authSessionStateFor] and [CredentialSessionSnapshot.isSameSession] with optional public address (design §3.4). */
class AuthSessionStateTest {

    private val identity = StableAccountIdentity("instance", 1L)

    private fun snapshot(
        serverAddress: String?,
        accessTokenExpiresAt: Long = Long.MAX_VALUE,
        refreshToken: String? = "refresh",
        generation: Long = 1L
    ) = CredentialSessionSnapshot(
        serverAddress = serverAddress,
        accessToken = "access",
        refreshToken = refreshToken,
        accessTokenExpiresAt = accessTokenExpiresAt,
        identity = identity,
        generation = generation
    )

    @Test
    fun `lan only session is authenticated`() {
        assertEquals(
            AuthSessionState.AUTHENTICATED,
            authSessionStateFor(snapshot(serverAddress = null), lanAddress = "http://192.168.1.10:8000")
        )
    }

    @Test
    fun `lan only session with expired token is refreshable`() {
        assertEquals(
            AuthSessionState.REFRESHABLE,
            authSessionStateFor(
                snapshot(serverAddress = null, accessTokenExpiresAt = 0L),
                lanAddress = "http://192.168.1.10:8000"
            )
        )
    }

    @Test
    fun `no address at all is unauthenticated`() {
        assertEquals(AuthSessionState.UNAUTHENTICATED, authSessionStateFor(snapshot(serverAddress = null), lanAddress = null))
        assertEquals(AuthSessionState.UNAUTHENTICATED, authSessionStateFor(snapshot(serverAddress = " "), lanAddress = ""))
    }

    @Test
    fun `public only session keeps today's result`() {
        assertEquals(
            AuthSessionState.AUTHENTICATED,
            authSessionStateFor(snapshot(serverAddress = "https://photos.example.com"), lanAddress = null)
        )
        assertEquals(
            AuthSessionState.UNAUTHENTICATED,
            authSessionStateFor(
                snapshot(serverAddress = "https://photos.example.com", accessTokenExpiresAt = 0L, refreshToken = null),
                lanAddress = null
            )
        )
    }

    @Test
    fun `missing identity is unauthenticated even with addresses`() {
        val noIdentity = snapshot(serverAddress = "https://photos.example.com").copy(identity = null)
        assertEquals(AuthSessionState.UNAUTHENTICATED, authSessionStateFor(noIdentity, lanAddress = "http://192.168.1.10:8000"))
    }

    @Test
    fun `isSameSession holds when both public addresses are null`() {
        assertTrue(snapshot(serverAddress = null).isSameSession(snapshot(serverAddress = null)))
    }

    @Test
    fun `isSameSession fails when the generation differs`() {
        assertFalse(snapshot(serverAddress = null, generation = 1L).isSameSession(snapshot(serverAddress = null, generation = 2L)))
    }
}
