package com.photovault.data.repository

import com.google.gson.Gson
import com.photovault.data.api.AuthApi
import com.photovault.data.api.AuthInterceptor
import com.photovault.data.api.model.ApiError
import com.photovault.data.api.model.ConnectionTestResponse
import com.photovault.data.api.model.LoginRequest
import com.photovault.data.api.model.LoginResponse
import com.photovault.data.local.CredentialManager
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val credentialManager: CredentialManager,
    private val authInterceptor: AuthInterceptor
) {
    private val gson = Gson()

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

    suspend fun testConnection(serverAddress: String): Result<ConnectionTestResponse> {
        return try {
            val api = createApiForServer(serverAddress, timeoutSeconds = 10)
            val response = api.testConnection()
            if (response.isSuccessful) {
                Result.success(response.body()!!)
            } else {
                val errorBody = response.errorBody()?.string()
                val errorMessage = try {
                    gson.fromJson(errorBody, ApiError::class.java).detail
                } catch (e: Exception) {
                    "服务器返回错误: ${response.code()}"
                }
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Throwable) {
            Result.failure(Exception("连接失败: ${e.javaClass.simpleName}: ${e.localizedMessage ?: "未知错误"}"))
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
                val loginResponse = response.body()!!
                // Save tokens
                credentialManager.saveTokens(
                    accessToken = loginResponse.accessToken,
                    refreshToken = loginResponse.refreshToken,
                    expiresIn = loginResponse.expiresIn
                )
                Result.success(loginResponse)
            } else {
                val errorBody = response.errorBody()?.string()
                val errorMessage = try {
                    gson.fromJson(errorBody, ApiError::class.java).detail
                } catch (e: Exception) {
                    when (response.code()) {
                        401 -> "用户名或密码错误"
                        else -> "登录失败: ${response.code()}"
                    }
                }
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Throwable) {
            Result.failure(Exception("连接服务器失败: ${e.javaClass.simpleName}: ${e.localizedMessage ?: "未知错误"}"))
        }
    }

    suspend fun refreshToken(): Result<LoginResponse> {
        val refreshToken = credentialManager.getRefreshToken()
            ?: return Result.failure(Exception("无刷新令牌"))

        val serverAddress = credentialManager.getServerAddress()
            ?: return Result.failure(Exception("无服务器地址"))

        return try {
            val api = createApiForServer(serverAddress)
            val response = api.refreshToken(
                com.photovault.data.api.model.RefreshRequest(refreshToken)
            )
            if (response.isSuccessful) {
                val refreshResponse = response.body()!!
                credentialManager.saveTokens(
                    accessToken = refreshResponse.accessToken,
                    refreshToken = refreshResponse.refreshToken,
                    expiresIn = refreshResponse.expiresIn
                )
                Result.success(
                    LoginResponse(
                        accessToken = refreshResponse.accessToken,
                        refreshToken = refreshResponse.refreshToken,
                        expiresIn = refreshResponse.expiresIn
                    )
                )
            } else {
                credentialManager.clearTokens()
                Result.failure(Exception("令牌刷新失败"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("刷新令牌失败: ${e.localizedMessage}"))
        }
    }

    fun hasValidToken(): Boolean {
        return credentialManager.hasValidToken()
    }

    fun logout() {
        credentialManager.clearTokens()
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
