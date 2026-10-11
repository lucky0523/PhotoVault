package com.huoyi.photovault.data.api

import com.huoyi.photovault.data.local.CredentialManager
import com.huoyi.photovault.data.local.CredentialSessionSnapshot
import com.huoyi.photovault.data.network.endpoint.EndpointResolver
import com.huoyi.photovault.data.network.endpoint.RouteSession
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class AuthInterceptor @Inject constructor(
    private val credentialManager: CredentialManager,
    private val tokenRefreshCoordinator: TokenRefreshCoordinator,
    private val resolver: EndpointResolver
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

        // Shared requests are tagged by EndpointRoutingInterceptor (which replaced
        // BaseUrlInterceptor) with the credential snapshot and the RouteSession, so
        // routing, refresh and retry stay bound to one immutable server/account
        // generation. Temporary AuthRepository clients have no tags and fall back
        // to the current snapshot and the ambient session.
        val session = originalRequest.tag(CredentialSessionSnapshot::class.java)
            ?: credentialManager.getSessionSnapshot()
        val routeSession = originalRequest.tag(RouteSession::class.java) ?: resolver.ambient
        val accessToken = tokenRefreshCoordinator.accessTokenForRequest(session, routeSession)
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
