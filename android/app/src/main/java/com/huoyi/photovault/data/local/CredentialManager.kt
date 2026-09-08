package com.huoyi.photovault.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_FILE_NAME = "photovault_secure_prefs"
        private const val KEY_SERVER_ADDRESS = "server_address"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_REMEMBER_PASSWORD = "remember_password"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_TOKEN_EXPIRY = "token_expiry"
        private const val KEY_INSTANCE_ID = "instance_id"
        private const val KEY_USER_ID = "user_id"
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveCredentials(
        serverAddress: String,
        username: String,
        password: String?,
        rememberPassword: Boolean
    ) {
        encryptedPrefs.edit().apply {
            putString(KEY_SERVER_ADDRESS, serverAddress)
            putString(KEY_USERNAME, username)
            putBoolean(KEY_REMEMBER_PASSWORD, rememberPassword)
            if (rememberPassword && password != null) {
                putString(KEY_PASSWORD, password)
            } else {
                remove(KEY_PASSWORD)
            }
            apply()
        }
    }

    fun loadCredentials(): SavedCredentials {
        return SavedCredentials(
            serverAddress = encryptedPrefs.getString(KEY_SERVER_ADDRESS, "") ?: "",
            username = encryptedPrefs.getString(KEY_USERNAME, "") ?: "",
            password = encryptedPrefs.getString(KEY_PASSWORD, "") ?: "",
            rememberPassword = encryptedPrefs.getBoolean(KEY_REMEMBER_PASSWORD, false)
        )
    }

    fun clearCredentials() {
        encryptedPrefs.edit().apply {
            remove(KEY_SERVER_ADDRESS)
            remove(KEY_USERNAME)
            remove(KEY_PASSWORD)
            remove(KEY_REMEMBER_PASSWORD)
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_TOKEN_EXPIRY)
            remove(KEY_INSTANCE_ID)
            remove(KEY_USER_ID)
            apply()
        }
    }

    /**
     * Publishes a successfully authenticated session in one preferences edit so
     * dynamic API requests never observe a new token paired with the old server.
     */
    @Synchronized
    fun saveSession(
        serverAddress: String,
        username: String,
        password: String?,
        rememberPassword: Boolean,
        accessToken: String,
        refreshToken: String,
        expiresIn: Int,
        instanceId: String,
        userId: Long
    ) {
        val expiryTime = System.currentTimeMillis() + (expiresIn * 1000L)
        encryptedPrefs.edit().apply {
            putString(KEY_SERVER_ADDRESS, serverAddress)
            putString(KEY_USERNAME, username)
            putBoolean(KEY_REMEMBER_PASSWORD, rememberPassword)
            if (rememberPassword && password != null) {
                putString(KEY_PASSWORD, password)
            } else {
                remove(KEY_PASSWORD)
            }
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_REFRESH_TOKEN, refreshToken)
            putLong(KEY_TOKEN_EXPIRY, expiryTime)
            putString(KEY_INSTANCE_ID, instanceId)
            putLong(KEY_USER_ID, userId)
            apply()
        }
    }

    fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Int) {
        val expiryTime = System.currentTimeMillis() + (expiresIn * 1000L)
        encryptedPrefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_REFRESH_TOKEN, refreshToken)
            putLong(KEY_TOKEN_EXPIRY, expiryTime)
            apply()
        }
    }

    /**
     * Returns the address and currently valid token under the same lock used by
     * [saveSession]. BaseUrlInterceptor attaches this immutable snapshot to the
     * request so AuthInterceptor cannot observe a different login generation.
     */
    @Synchronized
    fun getSessionSnapshot(): CredentialSessionSnapshot {
        val expiry = encryptedPrefs.getLong(KEY_TOKEN_EXPIRY, 0)
        val token = if (System.currentTimeMillis() < expiry) {
            encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)
        } else {
            null
        }
        return CredentialSessionSnapshot(
            serverAddress = encryptedPrefs.getString(KEY_SERVER_ADDRESS, null),
            accessToken = token
        )
    }

    fun getAccessToken(): String? {
        val expiry = encryptedPrefs.getLong(KEY_TOKEN_EXPIRY, 0)
        if (System.currentTimeMillis() >= expiry) {
            return null
        }
        return encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)
    }

    fun getRefreshToken(): String? {
        return encryptedPrefs.getString(KEY_REFRESH_TOKEN, null)
    }

    fun hasValidToken(): Boolean {
        return getAccessToken() != null
    }

    fun getServerAddress(): String? {
        return encryptedPrefs.getString(KEY_SERVER_ADDRESS, null)
    }

    /** Stable server/database account identity; absent for pre-upgrade installs. */
    fun getStableAccountIdentity(): StableAccountIdentity? {
        val instanceId = encryptedPrefs.getString(KEY_INSTANCE_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        if (!encryptedPrefs.contains(KEY_USER_ID)) return null
        val userId = encryptedPrefs.getLong(KEY_USER_ID, -1L)
        if (userId <= 0L) return null
        return StableAccountIdentity(instanceId = instanceId, userId = userId)
    }

    fun clearTokens() {
        encryptedPrefs.edit().apply {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_TOKEN_EXPIRY)
            apply()
        }
    }
}

data class StableAccountIdentity(
    val instanceId: String,
    val userId: Long
)

data class CredentialSessionSnapshot(
    val serverAddress: String?,
    val accessToken: String?
)

data class SavedCredentials(
    val serverAddress: String,
    val username: String,
    val password: String,
    val rememberPassword: Boolean
)
