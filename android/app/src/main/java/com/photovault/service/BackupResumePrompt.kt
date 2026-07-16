package com.photovault.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide, one-shot signal asking the UI to confirm whether to resume a
 * backup the user had manually paused.
 *
 * When a periodic scan runs while the app is in the foreground and finds a
 * user-paused backup, it does NOT resume silently — instead it calls [request],
 * and the UI (see `MainScreen`) observes [pending] to show a confirmation
 * dialog. The user either continues (which resumes the backup and clears the
 * persisted pause) or keeps it paused; either way the UI calls [consume] so the
 * dialog is shown once per request. Because the auto-scan runs periodically, a
 * dismissed prompt will be raised again on a later scan (by design).
 */
object BackupResumePrompt {

    private val _pending = MutableStateFlow(false)

    /** True while a resume-confirmation dialog should be shown. */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    /** Ask the UI to show the resume-confirmation dialog. */
    fun request() {
        _pending.value = true
    }

    /** Clear the request once the UI has handled it (shown + user decided). */
    fun consume() {
        _pending.value = false
    }
}
