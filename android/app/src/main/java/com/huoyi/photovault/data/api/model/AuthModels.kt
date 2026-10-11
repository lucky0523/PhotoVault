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
    // The server never sends `message`; kept nullable for source compatibility.
    val message: String? = null,
    val app: String? = null,
    @SerializedName("instance_fingerprint") val instanceFingerprint: String? = null
)

/** Body of `GET /api/v1/connection/test` as parsed by the LAN probe (all fields optional). */
data class ProbeResponse(
    val status: String?,
    val app: String?,
    @SerializedName("instance_fingerprint") val instanceFingerprint: String?
)

data class ApiError(
    val detail: String
)
