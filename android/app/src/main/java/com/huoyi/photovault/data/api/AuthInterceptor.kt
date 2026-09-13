package com.huoyi.photovault.data.api

import com.huoyi.photovault.data.local.CredentialManager
import com.huoyi.photovault.data.local.CredentialSessionSnapshot
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class AuthInterceptor @Inject constructor(
    private val credentialManager: CredentialManager,
    private val tokenRefreshCoordinator: TokenRefreshCoordinator
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // These endpoints are intentionally public or authenticate via request body.
        val path = originalRequest.url.encodedPath
        if (path.contains("/auth/login") ||
            path.contains("/auth/refresh") ||
            path.contains("/connection/test")
        ) {
            return chain.proceed(originalRequest)
        }

        // Shared requests are tagged by BaseUrlInterceptor so routing, refresh and
        // retry stay bound to one immutable server/account generation. Temporary
        // AuthRepository clients have no tag and use the current snapshot fallback.
        val session = originalRequest.tag(CredentialSessionSnapshot::class.java)
            ?: credentialManager.getSessionSnapshot()
        val accessToken = tokenRefreshCoordinator.accessTokenForRequest(session)
        if (accessToken.isNullOrEmpty()) {
            return chain.proceed(originalRequest)
        }

        return chain.proceed(
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .build()
        )
    }
}
