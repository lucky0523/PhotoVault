package com.huoyi.photovault.data.api.model

import com.google.gson.annotations.SerializedName

data class LoginRequest(
    val username: String,
    val password: String
)

data class LoginResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("expires_in") val expiresIn: Int,
    @SerializedName("instance_id") val instanceId: String? = null,
    @SerializedName("user_id") val userId: Long? = null
)

data class RefreshRequest(
    @SerializedName("refresh_token") val refreshToken: String
)

data class RefreshResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("expires_in") val expiresIn: Int,
    @SerializedName("instance_id") val instanceId: String? = null,
    @SerializedName("user_id") val userId: Long? = null
)

data class ConnectionTestResponse(
    val status: String,
    val message: String
)

data class ApiError(
    val detail: String
)
