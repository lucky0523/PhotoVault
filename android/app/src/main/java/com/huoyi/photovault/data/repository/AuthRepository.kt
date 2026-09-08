package com.huoyi.photovault.data.repository

import android.content.Context
import com.huoyi.photovault.R
import com.huoyi.photovault.data.api.AuthApi
import com.huoyi.photovault.data.api.AuthInterceptor
import com.huoyi.photovault.data.api.model.ConnectionTestResponse
import com.huoyi.photovault.data.api.model.LoginRequest
import com.huoyi.photovault.data.api.model.LoginResponse
import com.huoyi.photovault.data.api.model.RefreshResponse
import com.huoyi.photovault.data.local.CredentialManager
import com.huoyi.photovault.data.local.StableAccountIdentity
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val credentialManager: CredentialManager,
    private val authInterceptor: AuthInterceptor,
    @ApplicationContext private val context: Context
) {

    /**
     * Creates a Retrofit instance with the given server address.
     * This allows dynamic base URL for login and connection test.
     */
    private fun createApiForServer(serverAddress: String, timeoutSeconds: Long = 15): AuthApi {
        val url = normalizeServerUrl(serverAddress)

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(AuthApi::class.java)
    }

    /**
     * Lightweight probe of whether the configured backup server is reachable
     * right now. Reuses [testConnection] against the stored server address and
     * returns false when no address is stored or the probe fails/times out (10s).
     *
     * Used by the backup pipeline to avoid attempting uploads — and burning files
     * into the per-file retry backoff — when the (typically home-LAN) server is
     * unreachable. Runs a real request to the server, unlike a mere network-
     * interface check, so "手机有网但家里服务器连不上" is detected correctly.
     */
    suspend fun isServerReachable(): Boolean {
        val serverAddress = credentialManager.getServerAddress() ?: return false
        return testConnection(serverAddress).isSuccess
    }

    suspend fun testConnection(serverAddress: String): Result<ConnectionTestResponse> {
        return try {
            val api = createApiForServer(serverAddress, timeoutSeconds = 10)
            val response = api.testConnection()
            if (response.isSuccessful) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception(context.getString(R.string.error_server_http, response.code())))
            }
        } catch (e: Throwable) {
            Result.failure(Exception(context.getString(R.string.error_connection_failed), e))
        }
    }

    suspend fun login(
        serverAddress: String,
        username: String,
        password: String
    ): Result<LoginResponse> {
        return try {
            val api = createApiForServer(serverAddress)
            val response = api.login(LoginRequest(username, password))
            if (response.isSuccessful) {
                val body = response.body()
                    ?: return Result.failure(Exception(context.getString(R.string.error_empty_server_response)))
                if (stableIdentity(body.instanceId, body.userId) == null) {
                    return Result.failure(Exception(context.getString(R.string.error_server_identity_missing)))
                }
                // Do not publish the new token yet. LoginViewModel hands the
                // response to AccountSessionManager, which first invalidates any
                // old server-bound local state and then atomically saves the new
                // endpoint, stable identity and tokens.
                Result.success(body)
            } else {
                val messageRes = if (response.code() == 401) {
                    R.string.error_invalid_credentials
                } else {
                    R.string.error_login_http
                }
                val message = if (response.code() == 401) {
                    context.getString(messageRes)
                } else {
                    context.getString(messageRes, response.code())
                }
                Result.failure(Exception(message))
            }
        } catch (e: Throwable) {
            Result.failure(Exception(context.getString(R.string.error_server_connection_failed), e))
        }
    }

    suspend fun refreshToken(): Result<RefreshResponse> {
        val refreshToken = credentialManager.getRefreshToken()
            ?: return Result.failure(Exception(context.getString(R.string.error_missing_refresh_token)))

        val serverAddress = credentialManager.getServerAddress()
            ?: return Result.failure(Exception(context.getString(R.string.error_missing_server_address)))

        return try {
            val api = createApiForServer(serverAddress)
            val response = api.refreshToken(
                com.huoyi.photovault.data.api.model.RefreshRequest(refreshToken)
            )
            if (response.isSuccessful) {
                val refreshResponse = response.body()
                    ?: return Result.failure(Exception(context.getString(R.string.error_empty_server_response)))
                val refreshedIdentity = stableIdentity(
                    refreshResponse.instanceId,
                    refreshResponse.userId
                )
                val savedIdentity = credentialManager.getStableAccountIdentity()
                if (refreshedIdentity == null) {
                    credentialManager.clearTokens()
                    return Result.failure(Exception(context.getString(R.string.error_server_identity_missing)))
                }
                if (savedIdentity == null || savedIdentity != refreshedIdentity) {
                    // A refresh must never switch the local account projection.
                    // Force an interactive login so AccountSessionManager can run
                    // its stop/clear/rescan transition safely.
                    credentialManager.clearTokens()
                    return Result.failure(Exception(context.getString(R.string.error_server_identity_changed)))
                }
                credentialManager.saveTokens(
                    accessToken = refreshResponse.accessToken,
                    refreshToken = refreshResponse.refreshToken,
                    expiresIn = refreshResponse.expiresIn
                )
                Result.success(refreshResponse)
            } else {
                credentialManager.clearTokens()
                Result.failure(Exception(context.getString(R.string.error_token_refresh_failed)))
            }
        } catch (e: Exception) {
            Result.failure(Exception(context.getString(R.string.error_token_refresh_failed), e))
        }
    }

    fun hasValidToken(): Boolean {
        if (!credentialManager.hasValidToken()) return false
        // Pre-stable-ID installs must perform one interactive login so the local
        // projection can be conservatively rebound to (instance_id, user_id).
        if (credentialManager.getStableAccountIdentity() == null) {
            credentialManager.clearTokens()
            return false
        }
        return true
    }

    fun logout() {
        credentialManager.clearTokens()
    }

    private fun stableIdentity(instanceId: String?, userId: Long?): StableAccountIdentity? {
        val validInstanceId = instanceId?.takeIf { it.isNotBlank() } ?: return null
        val validUserId = userId?.takeIf { it > 0L } ?: return null
        return StableAccountIdentity(validInstanceId, validUserId)
    }

    private fun normalizeServerUrl(address: String): String {
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
