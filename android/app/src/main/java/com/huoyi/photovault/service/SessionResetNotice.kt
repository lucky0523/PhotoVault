package com.huoyi.photovault.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide, one-shot signal asking the UI to tell the user that the local
 * backup status was reset because the account or server changed.
 *
 * [com.huoyi.photovault.data.local.AccountSessionManager.activate] calls
 * [request] after it has cleared the local state; `MainScreen` observes
 * [pending], shows a dialog and calls [consume] when it is dismissed.
 * Same pattern as [BackupResumePrompt].
 */
object SessionResetNotice {

    private val _pending = MutableStateFlow(false)

    /** True while the reset notice should be shown. */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    /** Ask the UI to show the reset notice. */
    fun request() {
        _pending.value = true
    }

    /** Clear the request once the UI has shown it and the user dismissed it. */
    fun consume() {
        _pending.value = false
    }
}
