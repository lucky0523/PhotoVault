package com.photovault.data.local

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

    fun clearTokens() {
        encryptedPrefs.edit().apply {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_TOKEN_EXPIRY)
            apply()
        }
    }
}

data class SavedCredentials(
    val serverAddress: String,
    val username: String,
    val password: String,
    val rememberPassword: Boolean
)
