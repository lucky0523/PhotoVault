package com.huoyi.photovault.data.api

import com.huoyi.photovault.data.local.CredentialSessionSnapshot
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

/** Refreshes and replays a failed authenticated request at most once. */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val tokenRefreshCoordinator: TokenRefreshCoordinator
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val path = response.request.url.encodedPath
        if (path.contains("/auth/login") ||
            path.contains("/auth/refresh") ||
            path.contains("/connection/test") ||
            responseCount(response) >= 2
        ) {
            return null
        }

        val session = response.request.tag(CredentialSessionSnapshot::class.java)
            ?: return null
        val rejectedToken = response.request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.takeIf { it.isNotBlank() }
        val refreshedToken = tokenRefreshCoordinator.refreshAfterUnauthorized(
            expected = session,
            rejectedAccessToken = rejectedToken
        ) ?: return null

        return response.request.newBuilder()
            .header("Authorization", "Bearer $refreshedToken")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
