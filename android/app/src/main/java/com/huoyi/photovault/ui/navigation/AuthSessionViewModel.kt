package com.huoyi.photovault.ui.navigation

import androidx.lifecycle.ViewModel
import com.huoyi.photovault.data.local.CredentialManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AuthSessionViewModel @Inject constructor(
    credentialManager: CredentialManager
) : ViewModel() {
    val sessionState = credentialManager.authSessionState
}
