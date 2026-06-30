package com.photovault.data.api

import com.photovault.data.api.model.ConnectionTestResponse
import com.photovault.data.api.model.LoginRequest
import com.photovault.data.api.model.LoginResponse
import com.photovault.data.api.model.RefreshRequest
import com.photovault.data.api.model.RefreshResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface AuthApi {

    @POST("/api/v1/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("/api/v1/auth/refresh")
    suspend fun refreshToken(@Body request: RefreshRequest): Response<RefreshResponse>

    @GET("/api/v1/connection/test")
    suspend fun testConnection(): Response<ConnectionTestResponse>
}
