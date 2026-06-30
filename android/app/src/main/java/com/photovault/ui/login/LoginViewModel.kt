package com.photovault.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photovault.data.local.CredentialManager
import com.photovault.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val serverAddress: String = "",
    val username: String = "",
    val password: String = "",
    val rememberPassword: Boolean = false,
    val isLoading: Boolean = false,
    val isTesting: Boolean = false,
    val errorMessage: String? = null,
    val testResult: TestConnectionResult? = null,
    val serverAddressError: String? = null,
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
    private val credentialManager: CredentialManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        checkAutoLogin()
        loadSavedCredentials()
    }

    private fun checkAutoLogin() {
        if (authRepository.hasValidToken()) {
            _uiState.update { it.copy(hasValidToken = true) }
        }
    }

    private fun loadSavedCredentials() {
        val saved = credentialManager.loadCredentials()
        _uiState.update {
            it.copy(
                serverAddress = saved.serverAddress,
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
                testResult = null
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

    fun testConnection() {
        val state = _uiState.value
        if (state.serverAddress.isBlank()) {
            _uiState.update { it.copy(serverAddressError = "请输入服务器地址") }
            return
        }

        _uiState.update { it.copy(isTesting = true, testResult = null, errorMessage = null) }

        viewModelScope.launch {
            val result = authRepository.testConnection(state.serverAddress)
            _uiState.update {
                it.copy(
                    isTesting = false,
                    testResult = if (result.isSuccess) {
                        TestConnectionResult.Success
                    } else {
                        TestConnectionResult.Failure(
                            result.exceptionOrNull()?.message ?: "连接失败"
                        )
                    }
                )
            }
        }
    }

    fun login() {
        val state = _uiState.value

        // Validate fields
        var hasError = false
        var newState = state.copy(errorMessage = null)

        if (state.serverAddress.isBlank()) {
            newState = newState.copy(serverAddressError = "请输入服务器地址")
            hasError = true
        }
        if (state.username.isBlank()) {
            newState = newState.copy(usernameError = "请输入用户名")
            hasError = true
        }
        if (state.password.isBlank()) {
            newState = newState.copy(passwordError = "请输入密码")
            hasError = true
        }

        if (hasError) {
            _uiState.update { newState }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            val result = authRepository.login(
                serverAddress = state.serverAddress,
                username = state.username,
                password = state.password
            )

            if (result.isSuccess) {
                // Save credentials
                credentialManager.saveCredentials(
                    serverAddress = state.serverAddress,
                    username = state.username,
                    password = if (state.rememberPassword) state.password else null,
                    rememberPassword = state.rememberPassword
                )
                _uiState.update { it.copy(isLoading = false, loginSuccess = true) }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "登录失败"
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearTestResult() {
        _uiState.update { it.copy(testResult = null) }
    }
}
