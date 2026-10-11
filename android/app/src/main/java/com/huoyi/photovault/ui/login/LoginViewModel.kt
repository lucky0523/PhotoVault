package com.huoyi.photovault.ui.login

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huoyi.photovault.R
import com.huoyi.photovault.data.api.model.ConnectionTestResponse
import com.huoyi.photovault.data.local.AccountSessionManager
import com.huoyi.photovault.data.local.CredentialManager
import com.huoyi.photovault.data.network.endpoint.AddressError
import com.huoyi.photovault.data.network.endpoint.ConnectionErrorClassifier
import com.huoyi.photovault.data.network.endpoint.EndpointResolver
import com.huoyi.photovault.data.network.endpoint.Route
import com.huoyi.photovault.data.repository.AuthRepository
import com.huoyi.photovault.ui.common.AddressErrorText
import com.huoyi.photovault.ui.common.AddressField
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Login form state. [serverAddress] is the public address field (name kept from the
 * single-address era); [lanAddress] is the optional LAN field. Connection-test results
 * are tracked per field so each one can be shown under its own input.
 */
data class LoginUiState(
    val serverAddress: String = "",
    val lanAddress: String = "",
    val username: String = "",
    val password: String = "",
    val rememberPassword: Boolean = false,
    val isLoading: Boolean = false,
    val isTesting: Boolean = false,
    val errorMessage: String? = null,
    val publicTestResult: TestConnectionResult? = null,
    val lanTestResult: TestConnectionResult? = null,
    val serverAddressError: String? = null,
    val lanAddressError: String? = null,
    val usernameError: String? = null,
    val passwordError: String? = null,
    val loginSuccess: Boolean = false,
    val hasValidToken: Boolean = false
)

sealed class TestConnectionResult {
    data object Success : TestConnectionResult()
    data class Failure(val message: String) : TestConnectionResult()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val credentialManager: CredentialManager,
    private val accountSessionManager: AccountSessionManager,
    private val endpointResolver: EndpointResolver,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private companion object {
        const val TAG = "PhotoVaultLogin"
    }

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()
    private var authAttemptEpoch: Long = 0L

    init {
        checkAutoLogin()
        loadSavedCredentials()
    }

    private fun checkAutoLogin() {
        val attempt = authAttemptEpoch
        viewModelScope.launch {
            val readiness = authRepository.ensureAuthenticated()
            if (readiness == com.huoyi.photovault.data.api.AuthReadiness.VALID &&
                attempt == authAttemptEpoch &&
                !_uiState.value.isLoading
            ) {
                _uiState.update { it.copy(hasValidToken = true) }
            }
        }
    }

    private fun loadSavedCredentials() {
        val saved = credentialManager.loadCredentials()
        _uiState.update {
            it.copy(
                serverAddress = saved.serverAddress,
                lanAddress = saved.lanAddress,
                username = saved.username,
                password = if (saved.rememberPassword) saved.password else "",
                rememberPassword = saved.rememberPassword
            )
        }
    }

    fun onServerAddressChange(value: String) {
        _uiState.update {
            it.copy(
                serverAddress = value,
                serverAddressError = null,
                publicTestResult = null
            )
        }
    }

    fun onLanAddressChange(value: String) {
        _uiState.update {
            it.copy(
                lanAddress = value,
                lanAddressError = null,
                lanTestResult = null
            )
        }
    }

    fun onUsernameChange(value: String) {
        _uiState.update { it.copy(username = value, usernameError = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, passwordError = null) }
    }

    fun onRememberPasswordChange(value: Boolean) {
        _uiState.update { it.copy(rememberPassword = value) }
    }

    /**
     * Tests every filled-in, valid address separately and in parallel (design §5.4).
     * Uses [AuthRepository.testConnection] directly, never the endpoint resolver (AC-17).
     */
    fun testConnection() {
        _uiState.update {
            it.copy(
                publicTestResult = null,
                lanTestResult = null,
                serverAddressError = null,
                lanAddressError = null,
                errorMessage = null
            )
        }
        val state = _uiState.value
        val plan = planAddressCheck(state.serverAddress, state.lanAddress)
        if (plan.requiredAnyMissing) {
            _uiState.update {
                it.copy(serverAddressError = context.getString(R.string.validation_address_required_any))
            }
            return
        }
        _uiState.update {
            it.copy(
                serverAddressError = plan.publicError?.let { e -> addressErrorText(e, AddressField.LOGIN_PUBLIC) },
                lanAddressError = plan.lanError?.let { e -> addressErrorText(e, AddressField.LOGIN_LAN) }
            )
        }
        val publicAddress = plan.publicCanonical
        val lanAddress = plan.lanCanonical
        if (publicAddress == null && lanAddress == null) return

        _uiState.update { it.copy(isTesting = true) }

        viewModelScope.launch {
            val publicCall = publicAddress?.let { async { authRepository.testConnection(it) } }
            val lanCall = lanAddress?.let { async { authRepository.testConnection(it) } }
            val publicResult = publicCall?.await()?.toTestResult()
            val lanResult = lanCall?.await()?.toTestResult()
            _uiState.update {
                it.copy(
                    isTesting = false,
                    publicTestResult = publicResult,
                    lanTestResult = lanResult
                )
            }
        }
    }

    fun login() {
        val state = _uiState.value

        // Validate fields
        var hasError = false
        var newState = state.copy(errorMessage = null)

        if (state.username.isBlank()) {
            newState = newState.copy(usernameError = context.getString(R.string.validation_username_required))
            hasError = true
        }
        if (state.password.isBlank()) {
            newState = newState.copy(passwordError = context.getString(R.string.validation_password_required))
            hasError = true
        }

        val plan = planAddressCheck(state.serverAddress, state.lanAddress)
        if (plan.requiredAnyMissing) {
            newState = newState.copy(
                serverAddressError = context.getString(R.string.validation_address_required_any)
            )
            hasError = true
        }
        plan.publicError?.let { error ->
            newState = newState.copy(serverAddressError = addressErrorText(error, AddressField.LOGIN_PUBLIC))
            hasError = true
        }
        plan.lanError?.let { error ->
            newState = newState.copy(lanAddressError = addressErrorText(error, AddressField.LOGIN_LAN))
            hasError = true
        }

        if (hasError) {
            _uiState.update { newState }
            return
        }

        val publicAddress = plan.publicCanonical
        val lanAddress = plan.lanCanonical
        val attempt = ++authAttemptEpoch
        _uiState.update {
            it.copy(isLoading = true, errorMessage = null, hasValidToken = false)
        }

        viewModelScope.launch {
            // Dual address on Wi-Fi/Ethernet: a 1.5 s LAN probe decides; otherwise the
            // single filled-in address (or public) is used (design §5.4 step 4).
            val target = endpointResolver.selectLoginTarget(publicAddress, lanAddress)
            if (attempt != authAttemptEpoch) return@launch

            var result = authRepository.login(
                serverAddress = target.address,
                username = state.username,
                password = state.password
            )
            if (attempt != authAttemptEpoch) return@launch

            // A connection-level LAN failure falls back to public once; HTTP-level
            // failures (wrong password etc.) are shown as-is.
            if (target.route == Route.LAN &&
                publicAddress != null &&
                ConnectionErrorClassifier.isConnectionLevel(result.exceptionOrNull()?.cause)
            ) {
                Log.w(TAG, "LAN login failed at connection level; retrying on public address")
                result = authRepository.login(
                    serverAddress = publicAddress,
                    username = state.username,
                    password = state.password
                )
                if (attempt != authAttemptEpoch) return@launch
            }

            if (result.isSuccess) {
                val sessionResult = runCatching {
                    accountSessionManager.activate(
                        serverAddress = publicAddress,
                        lanAddress = lanAddress,
                        username = state.username,
                        password = if (state.rememberPassword) state.password else null,
                        rememberPassword = state.rememberPassword,
                        loginResponse = result.getOrThrow()
                    )
                }
                if (sessionResult.isSuccess) {
                    _uiState.update { it.copy(isLoading = false, loginSuccess = true) }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = sessionResult.exceptionOrNull()?.message
                                ?: context.getString(R.string.error_login_failed)
                        )
                    }
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.exceptionOrNull()?.message
                            ?: context.getString(R.string.error_login_failed)
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearTestResult() {
        _uiState.update { it.copy(publicTestResult = null, lanTestResult = null) }
    }

    private fun addressErrorText(error: AddressError, field: AddressField): String =
        context.getString(AddressErrorText.resIdFor(error, field))

    private fun Result<ConnectionTestResponse>.toTestResult(): TestConnectionResult =
        if (isSuccess) {
            TestConnectionResult.Success
        } else {
            TestConnectionResult.Failure(
                exceptionOrNull()?.message ?: context.getString(R.string.error_connection_failed)
            )
        }
}
