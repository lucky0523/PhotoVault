package com.huoyi.photovault.service

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Prevents a background scan from crossing an authenticated-session switch.
 *
 * A scan holds this gate for its complete read/network/write cycle. Session
 * activation cancels scheduled work and then takes the same gate before clearing
 * old projections and publishing new credentials, which provides the completion
 * barrier that WorkManager cancellation alone does not guarantee.
 */
@Singleton
class SessionOperationGuard @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withOperation(block: suspend () -> T): T = mutex.withLock {
        block()
    }
}
