package com.huoyi.photovault.data.api

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.gson.Gson
import com.huoyi.photovault.data.api.model.RefreshRequest
import com.huoyi.photovault.data.api.model.RefreshResponse
import com.huoyi.photovault.data.local.CredentialManager
import com.huoyi.photovault.data.local.CredentialSessionSnapshot
import com.huoyi.photovault.data.local.StableAccountIdentity
import com.huoyi.photovault.data.network.endpoint.DispatchMarkerInterceptor
import com.huoyi.photovault.data.network.endpoint.EndpointConfigSource
import com.huoyi.photovault.data.network.endpoint.EndpointRoutingInterceptor
import com.huoyi.photovault.data.network.endpoint.RouteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

enum class AuthReadiness {
    VALID,
    TRANSIENT_FAILURE,
    TERMINAL_FAILURE
}

/**
 * Owns access-token refresh for foreground and background callers.
 *
 * The blocking entry points are intentional: OkHttp interceptors/authenticators are
 * synchronous. A dedicated client with only the routing interceptor (no AuthInterceptor)
 * prevents refresh requests from recursively invoking authentication, while still
 * following the caller's [RouteSession] (LAN or public, with fallback). [refreshLock] merges concurrent refreshes,
 * while CredentialManager's generation-checked writes prevent an old account's
 * in-flight response from overwriting a newly activated session.
 */
@Singleton
class TokenRefreshCoordinator @Inject constructor(
    private val credentialManager: CredentialManager,
    private val endpointConfig: EndpointConfigSource,
    routingInterceptor: EndpointRoutingInterceptor
) {
    companion object {
        private const val TAG = "PhotoVaultAuth"
        // Placeholder host; the routing interceptor rewrites scheme/host/port.
        private const val REFRESH_URL = "http://photovault.invalid/api/v1/auth/refresh"
        private const val PROACTIVE_REFRESH_WINDOW_MS = 5 * 60 * 1000L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private data class TokenOutcome(
        val token: String?,
        val readiness: AuthReadiness
    )

    private val gson = Gson()
    private val refreshLock = ReentrantLock()
    // Explicit 15/30/30 s: keep today's timeouts instead of OkHttp's 10/10/10 defaults.
    @VisibleForTesting
    internal val refreshClient = OkHttpClient.Builder()
        .addInterceptor(routingInterceptor)
        .addNetworkInterceptor(DispatchMarkerInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Ensures workers/services have a usable token and preserves failure type. */
    suspend fun ensureValidAccessToken(routeSession: RouteSession): AuthReadiness = withContext(Dispatchers.IO) {
        val snapshot = credentialManager.getSessionSnapshot()
        val outcome = tokenOutcomeForRequest(snapshot, routeSession)
        credentialManager.refreshAuthSessionState()
        outcome.readiness
    }

    /**
     * Returns a token for an outgoing request, proactively refreshing when it is
     * expired or has less than five minutes remaining.
     */
    fun accessTokenForRequest(expected: CredentialSessionSnapshot, routeSession: RouteSession): String? =
        tokenOutcomeForRequest(expected, routeSession).token

    private fun tokenOutcomeForRequest(
        expected: CredentialSessionSnapshot,
        routeSession: RouteSession
    ): TokenOutcome {
        if (expected.isAccessTokenValid(minimumValidityMillis = PROACTIVE_REFRESH_WINDOW_MS)) {
            return TokenOutcome(expected.accessToken, AuthReadiness.VALID)
        }
        return refreshLock.withLock {
            val current = credentialManager.getSessionSnapshot()
            if (!current.isSameSession(expected)) {
                return@withLock TokenOutcome(null, AuthReadiness.TERMINAL_FAILURE)
            }
            if (current.isAccessTokenValid(minimumValidityMillis = PROACTIVE_REFRESH_WINDOW_MS)) {
                return@withLock TokenOutcome(current.accessToken, AuthReadiness.VALID)
            }
            refreshLocked(current, fallbackToStillValidToken = true, routeSession = routeSession)
        }
    }

    /**
     * Handles a business request's first 401. If another caller already refreshed
     * this generation, reuse its token; otherwise perform one forced refresh.
     */
    fun refreshAfterUnauthorized(
        expected: CredentialSessionSnapshot,
        rejectedAccessToken: String?,
        routeSession: RouteSession
    ): String? = refreshLock.withLock {
        val current = credentialManager.getSessionSnapshot()
        if (!current.isSameSession(expected)) return@withLock null

        if (current.accessToken != rejectedAccessToken && current.isAccessTokenValid()) {
            return@withLock current.accessToken
        }
        refreshLocked(current, fallbackToStillValidToken = false, routeSession = routeSession).token
    }

    private fun refreshLocked(
        session: CredentialSessionSnapshot,
        fallbackToStillValidToken: Boolean,
        routeSession: RouteSession
    ): TokenOutcome {
        val fallback = session.accessToken
            ?.takeIf { fallbackToStillValidToken && session.isAccessTokenValid() }
        // Public or LAN address (a LAN-only login has no server_address, design §3.4).
        if (!endpointConfig.current().hasAnyAddress) {
            return terminal(session, "missing server address")
        }
        val refreshToken = session.refreshToken?.takeIf { it.isNotBlank() }
            ?: return terminal(session, "missing refresh token")
        val identity = session.identity
            ?: return terminal(session, "missing stable account identity")

        val request = Request.Builder()
            .url(REFRESH_URL)
            .post(gson.toJson(RefreshRequest(refreshToken)).toRequestBody(JSON))
            .tag(RouteSession::class.java, routeSession)
            .tag(CredentialSessionSnapshot::class.java, session)
            .build()

        return try {
            refreshClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Token refresh failed with HTTP ${response.code}")
                    if (response.code in setOf(400, 401, 403, 422)) {
                        terminal(session, "HTTP ${response.code}")
                    } else {
                        transient(fallback)
                    }
                } else {
                    val body = response.body?.string()
                    val refreshed = body?.let {
                        runCatching { gson.fromJson(it, RefreshResponse::class.java) }.getOrNull()
                    }
                    val refreshedIdentity = refreshed?.toStableIdentity()
                    if (refreshed == null || refreshedIdentity != identity ||
                        refreshed.accessToken.isBlank() || refreshed.refreshToken.isBlank() ||
                        refreshed.expiresIn <= 0
                    ) {
                        terminal(session, "invalid refresh response")
                    } else {
                        val saved = credentialManager.saveRefreshedTokensIfCurrent(
                            expected = session,
                            accessToken = refreshed.accessToken,
                            refreshToken = refreshed.refreshToken,
                            expiresIn = refreshed.expiresIn
                        )
                        if (saved) {
                            Log.i(TAG, "Access token refreshed")
                            TokenOutcome(refreshed.accessToken, AuthReadiness.VALID)
                        } else {
                            // Login generation changed while the request was in flight.
                            Log.i(TAG, "Discarded stale token refresh response")
                            TokenOutcome(null, AuthReadiness.TERMINAL_FAILURE)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Network/5xx failures do not destroy a potentially recoverable session.
            Log.w(TAG, "Token refresh request failed: ${e.javaClass.simpleName}: ${e.message}")
            transient(fallback)
        }
    }

    private fun terminal(
        session: CredentialSessionSnapshot,
        reason: String
    ): TokenOutcome {
        Log.w(TAG, "Authentication session is no longer refreshable: $reason")
        credentialManager.clearTokensIfCurrent(session)
        return TokenOutcome(null, AuthReadiness.TERMINAL_FAILURE)
    }

    private fun transient(fallback: String?): TokenOutcome =
        if (fallback != null) {
            TokenOutcome(fallback, AuthReadiness.VALID)
        } else {
            TokenOutcome(null, AuthReadiness.TRANSIENT_FAILURE)
        }

    private fun RefreshResponse.toStableIdentity(): StableAccountIdentity? {
        val validInstanceId = instanceId?.takeIf { it.isNotBlank() } ?: return null
        val validUserId = userId?.takeIf { it > 0L } ?: return null
        return StableAccountIdentity(validInstanceId, validUserId)
    }
}
