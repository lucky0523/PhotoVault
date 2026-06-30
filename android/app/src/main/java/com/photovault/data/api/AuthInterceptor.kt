package com.photovault.data.api

import com.photovault.data.local.CredentialManager
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class AuthInterceptor @Inject constructor(
    private val credentialManager: CredentialManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Skip adding token for login and connection test endpoints
        val path = originalRequest.url.encodedPath
        if (path.contains("/auth/login") || path.contains("/connection/test")) {
            return chain.proceed(originalRequest)
        }

        val accessToken = credentialManager.getAccessToken()
        if (accessToken.isNullOrEmpty()) {
            return chain.proceed(originalRequest)
        }

        val authenticatedRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $accessToken")
            .build()

        return chain.proceed(authenticatedRequest)
    }
}
