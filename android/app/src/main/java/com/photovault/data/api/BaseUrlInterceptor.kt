package com.photovault.data.api

import com.photovault.data.local.CredentialManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Rewrites every outgoing request's scheme/host/port to the server address currently
 * stored in [CredentialManager].
 *
 * The shared Retrofit instance is built once (as a Hilt @Singleton) with a placeholder
 * base URL. Without this interceptor the base URL would be frozen at first injection,
 * so changing the server address (e.g. re-login after the NAS IP changes) would not take
 * effect for APIs that use the shared instance (BackupApi / FileApi). This interceptor
 * makes the real target always follow the latest saved server address.
 */
class BaseUrlInterceptor @Inject constructor(
    private val credentialManager: CredentialManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        val serverAddress = credentialManager.getServerAddress()
        if (serverAddress.isNullOrBlank()) {
            android.util.Log.w("PhotoVaultBackup", "BaseUrlInterceptor: server address is null/blank, using original ${original.url}")
            return chain.proceed(original)
        }

        val serverUrl = normalize(serverAddress).toHttpUrlOrNull()
        if (serverUrl == null) {
            android.util.Log.w("PhotoVaultBackup", "BaseUrlInterceptor: cannot parse server address '$serverAddress'")
            return chain.proceed(original)
        }

        val newUrl = original.url.newBuilder()
            .scheme(serverUrl.scheme)
            .host(serverUrl.host)
            .port(serverUrl.port)
            .build()

        android.util.Log.i("PhotoVaultBackup", "BaseUrlInterceptor: ${original.url} -> $newUrl")
        return chain.proceed(original.newBuilder().url(newUrl).build())
    }

    private fun normalize(address: String): String {
        var url = address.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        if (!url.endsWith("/")) {
            url = "$url/"
        }
        return url
    }
}
