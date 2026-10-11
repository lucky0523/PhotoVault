package com.huoyi.photovault.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.huoyi.photovault.data.network.endpoint.EndpointConfig
import com.huoyi.photovault.data.network.endpoint.EndpointConfigSource
import com.huoyi.photovault.data.network.endpoint.ServerAddress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialManager @Inject constructor(
    @ApplicationContext private val context: Context
) : EndpointConfigSource {
    companion object {
        private const val TAG = "PhotoVaultAuth"
        private const val PREFS_FILE_NAME = "photovault_secure_prefs"
        private const val KEY_SERVER_ADDRESS = "server_address"
        private const val KEY_LAN_ADDRESS = "lan_address"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_REMEMBER_PASSWORD = "remember_password"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_TOKEN_EXPIRY = "token_expiry"
        private const val KEY_INSTANCE_ID = "instance_id"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_SESSION_GENERATION = "session_generation"
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

    // Keep construction side-effect free: many workers/view-models inject this
    // singleton in JVM tests where Android Keystore is unavailable. The first
    // authentication check publishes the persisted state lazily.
    private val _authSessionState = MutableStateFlow(AuthSessionState.UNAUTHENTICATED)
    val authSessionState: StateFlow<AuthSessionState> = _authSessionState.asStateFlow()

    // Endpoint configuration version (EndpointConfigSource). Bumped after every
    // persisted address change so the resolver drops stale probe results.
    private val _configVersion = MutableStateFlow(0L)
    override val changes: StateFlow<Long> = _configVersion.asStateFlow()

    /**
     * Public + LAN base URLs and instance id, read from the persisted session.
     * EndpointResolver.start() calls this from Application.onCreate, so a missing
     * keystore (JVM/Robolectric tests, broken device keystore) yields an empty
     * configuration instead of crashing process start.
     */
    @Synchronized
    override fun current(): EndpointConfig {
        val prefs = try {
            encryptedPrefs
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Secure prefs unavailable: ${e.javaClass.simpleName}")
            return EndpointConfig(null, null, null, _configVersion.value)
        } catch (e: IOException) {
            Log.e(TAG, "Secure prefs unavailable: ${e.javaClass.simpleName}")
            return EndpointConfig(null, null, null, _configVersion.value)
        }
        return EndpointConfig(
            publicBase = toBaseUrl(prefs.getString(KEY_SERVER_ADDRESS, null)),
            lanBase = toBaseUrl(prefs.getString(KEY_LAN_ADDRESS, null)),
            instanceId = prefs.getString(KEY_INSTANCE_ID, null)?.takeIf { it.isNotBlank() },
            version = _configVersion.value
        )
    }

    override fun sessionSnapshot(): CredentialSessionSnapshot = getSessionSnapshot()

    private fun toBaseUrl(raw: String?): HttpUrl? =
        raw?.takeIf { it.isNotBlank() }?.let { ServerAddress.legacyNormalize(it).toHttpUrlOrNull() }

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
            lanAddress = encryptedPrefs.getString(KEY_LAN_ADDRESS, "") ?: "",
            username = encryptedPrefs.getString(KEY_USERNAME, "") ?: "",
            password = encryptedPrefs.getString(KEY_PASSWORD, "") ?: "",
            rememberPassword = encryptedPrefs.getBoolean(KEY_REMEMBER_PASSWORD, false)
        )
    }

    @Synchronized
    fun clearCredentials() {
        val nextGeneration = nextGeneration()
        encryptedPrefs.edit().apply {
            remove(KEY_SERVER_ADDRESS)
            remove(KEY_LAN_ADDRESS)
            remove(KEY_USERNAME)
            remove(KEY_PASSWORD)
            remove(KEY_REMEMBER_PASSWORD)
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_TOKEN_EXPIRY)
            remove(KEY_INSTANCE_ID)
            remove(KEY_USER_ID)
            putLong(KEY_SESSION_GENERATION, nextGeneration)
            apply()
        }
        _configVersion.value += 1
        publishAuthSessionState()
    }

    /**
     * Publishes a successfully authenticated session atomically. Incrementing the
     * generation invalidates refresh responses that were started by an older login.
     * A null address removes the stored key; at least one of [serverAddress]
     * (public) and [lanAddress] must be present.
     */
    @Synchronized
    fun saveSession(
        serverAddress: String?,
        lanAddress: String?,
        username: String,
        password: String?,
        rememberPassword: Boolean,
        accessToken: String,
        refreshToken: String,
        expiresIn: Int,
        instanceId: String,
        userId: Long
    ) {
        require(serverAddress != null || lanAddress != null) {
            "saveSession requires a public or LAN address"
        }
        val expiryTime = System.currentTimeMillis() + (expiresIn * 1000L)
        val nextGeneration = nextGeneration()
        encryptedPrefs.edit().apply {
            if (serverAddress == null) remove(KEY_SERVER_ADDRESS) else putString(KEY_SERVER_ADDRESS, serverAddress)
            if (lanAddress == null) remove(KEY_LAN_ADDRESS) else putString(KEY_LAN_ADDRESS, lanAddress)
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
            putLong(KEY_SESSION_GENERATION, nextGeneration)
            apply()
        }
        _configVersion.value += 1
        publishAuthSessionState()
    }

    /**
     * Sets (canonical form) or clears (null) the LAN address. Does not touch the
     * session generation, so in-flight token refreshes stay valid (design §3.4).
     */
    @Synchronized
    fun setLanAddress(canonical: String?) {
        encryptedPrefs.edit().apply {
            if (canonical == null) remove(KEY_LAN_ADDRESS) else putString(KEY_LAN_ADDRESS, canonical)
            apply()
        }
        _configVersion.value += 1
        publishAuthSessionState()
    }

    @Synchronized
    fun getLanAddress(): String? = encryptedPrefs.getString(KEY_LAN_ADDRESS, null)

    /** Retained for callers that already own the current session transaction. */
    @Synchronized
    fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Int) {
        val expiryTime = System.currentTimeMillis() + (expiresIn * 1000L)
        encryptedPrefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_REFRESH_TOKEN, refreshToken)
            putLong(KEY_TOKEN_EXPIRY, expiryTime)
            apply()
        }
        publishAuthSessionState()
    }

    /**
     * Saves refreshed tokens only if the login generation, endpoint and stable
     * account still match the session that initiated the refresh.
     */
    @Synchronized
    fun saveRefreshedTokensIfCurrent(
        expected: CredentialSessionSnapshot,
        accessToken: String,
        refreshToken: String,
        expiresIn: Int
    ): Boolean {
        if (!getSessionSnapshot().isSameSession(expected)) return false
        val expiryTime = System.currentTimeMillis() + (expiresIn * 1000L)
        encryptedPrefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_REFRESH_TOKEN, refreshToken)
            putLong(KEY_TOKEN_EXPIRY, expiryTime)
            apply()
        }
        publishAuthSessionState()
        return true
    }

    /** Clears only the session that initiated a terminally failed refresh. */
    @Synchronized
    fun clearTokensIfCurrent(expected: CredentialSessionSnapshot): Boolean {
        if (!getSessionSnapshot().isSameSession(expected)) return false
        clearTokensLocked()
        return true
    }

    /**
     * Returns one immutable endpoint/token/account snapshot. The access token is
     * returned even when expired; callers decide whether to refresh it. This lets
     * a 401 retry remain bound to the exact server/account generation it started on.
     */
    @Synchronized
    fun getSessionSnapshot(): CredentialSessionSnapshot {
        val instanceId = encryptedPrefs.getString(KEY_INSTANCE_ID, null)
            ?.takeIf { it.isNotBlank() }
        val userId = if (encryptedPrefs.contains(KEY_USER_ID)) {
            encryptedPrefs.getLong(KEY_USER_ID, -1L).takeIf { it > 0L }
        } else {
            null
        }
        val identity = if (instanceId != null && userId != null) {
            StableAccountIdentity(instanceId, userId)
        } else {
            null
        }
        return CredentialSessionSnapshot(
            serverAddress = encryptedPrefs.getString(KEY_SERVER_ADDRESS, null),
            accessToken = encryptedPrefs.getString(KEY_ACCESS_TOKEN, null),
            refreshToken = encryptedPrefs.getString(KEY_REFRESH_TOKEN, null),
            accessTokenExpiresAt = encryptedPrefs.getLong(KEY_TOKEN_EXPIRY, 0L),
            identity = identity,
            generation = encryptedPrefs.getLong(KEY_SESSION_GENERATION, 0L)
        )
    }

    fun getAccessToken(): String? = getSessionSnapshot()
        .takeIf { it.isAccessTokenValid() }
        ?.accessToken

    @Synchronized
    fun getRefreshToken(): String? = encryptedPrefs.getString(KEY_REFRESH_TOKEN, null)

    fun hasValidToken(): Boolean = getAccessToken() != null

    @Synchronized
    fun getServerAddress(): String? = encryptedPrefs.getString(KEY_SERVER_ADDRESS, null)

    /** Stable server/database account identity; absent for pre-upgrade installs. */
    fun getStableAccountIdentity(): StableAccountIdentity? = getSessionSnapshot().identity

    @Synchronized
    fun clearTokens() {
        clearTokensLocked()
    }

    private fun clearTokensLocked() {
        val nextGeneration = nextGeneration()
        encryptedPrefs.edit().apply {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_TOKEN_EXPIRY)
            putLong(KEY_SESSION_GENERATION, nextGeneration)
            apply()
        }
        publishAuthSessionState()
    }

    private fun nextGeneration(): Long =
        encryptedPrefs.getLong(KEY_SESSION_GENERATION, 0L) + 1L

    /** Publishes persisted state after the first lazy authentication check. */
    @Synchronized
    fun refreshAuthSessionState() {
        publishAuthSessionState()
    }

    private fun publishAuthSessionState() {
        _authSessionState.value = readAuthSessionState()
    }

    private fun readAuthSessionState(): AuthSessionState =
        authSessionStateFor(getSessionSnapshot(), encryptedPrefs.getString(KEY_LAN_ADDRESS, null))
}

/**
 * Auth state for a persisted session. A session needs a stable identity and at
 * least one address (public or LAN, CR-1); a LAN-only login is not "unauthenticated".
 */
@VisibleForTesting
internal fun authSessionStateFor(
    snapshot: CredentialSessionSnapshot,
    lanAddress: String?
): AuthSessionState = when {
    snapshot.identity == null ||
        (snapshot.serverAddress.isNullOrBlank() && lanAddress.isNullOrBlank()) ->
        AuthSessionState.UNAUTHENTICATED
    snapshot.isAccessTokenValid() -> AuthSessionState.AUTHENTICATED
    !snapshot.refreshToken.isNullOrBlank() -> AuthSessionState.REFRESHABLE
    else -> AuthSessionState.UNAUTHENTICATED
}

enum class AuthSessionState {
    AUTHENTICATED,
    REFRESHABLE,
    UNAUTHENTICATED
}

data class StableAccountIdentity(
    val instanceId: String,
    val userId: Long
)

data class CredentialSessionSnapshot(
    val serverAddress: String?,
    val accessToken: String?,
    val refreshToken: String?,
    val accessTokenExpiresAt: Long,
    val identity: StableAccountIdentity?,
    val generation: Long
) {
    fun isAccessTokenValid(
        nowMillis: Long = System.currentTimeMillis(),
        minimumValidityMillis: Long = 0L
    ): Boolean = !accessToken.isNullOrBlank() &&
        accessTokenExpiresAt > nowMillis + minimumValidityMillis

    fun isSameSession(other: CredentialSessionSnapshot): Boolean =
        generation == other.generation &&
            serverAddress == other.serverAddress &&
            identity == other.identity
}

data class SavedCredentials(
    val serverAddress: String,
    val lanAddress: String,
    val username: String,
    val password: String,
    val rememberPassword: Boolean
)
