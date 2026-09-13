package com.huoyi.photovault.data.local

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.work.WorkManager
import com.huoyi.photovault.R
import com.huoyi.photovault.data.api.model.LoginResponse
import com.huoyi.photovault.service.BackupForegroundService
import com.huoyi.photovault.service.BackupQueue
import com.huoyi.photovault.service.BackupResumePrompt
import com.huoyi.photovault.service.BackgroundScanWorker
import com.huoyi.photovault.service.SessionOperationGuard
import com.huoyi.photovault.service.StatusSyncManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the transition between authenticated server/account sessions.
 *
 * Backup state in the current schema is keyed by local file URI rather than by
 * server. The authenticated `(instance_id, user_id)` pair therefore decides
 * whether the current projection can be retained or must be invalidated.
 * Device folder selections and SAF grants are intentionally retained.
 */
@Singleton
class AccountSessionManager @Inject constructor(
    private val credentialManager: CredentialManager,
    private val database: AppDatabase,
    private val backupQueue: BackupQueue,
    private val statusSyncManager: StatusSyncManager,
    private val sessionOperationGuard: SessionOperationGuard,
    private val settingsPreferences: SettingsPreferences,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "PhotoVaultSession"
        private const val WORK_CANCEL_TIMEOUT_SECONDS = 5L

        @androidx.annotation.VisibleForTesting
        internal fun hasSessionChanged(
            previous: StableAccountIdentity?,
            incoming: StableAccountIdentity
        ): Boolean = previous != incoming
    }

    private val switchMutex = Mutex()

    /**
     * Activates [loginResponse] and returns true when the server/account changed.
     * The new address and tokens are written only after old asynchronous work and
     * server-owned caches have been invalidated.
     */
    suspend fun activate(
        serverAddress: String,
        username: String,
        password: String?,
        rememberPassword: Boolean,
        loginResponse: LoginResponse
    ): Boolean = switchMutex.withLock {
        val incomingInstanceId = loginResponse.instanceId
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException(context.getString(R.string.error_server_identity_missing))
        val incomingUserId = loginResponse.userId
            ?.takeIf { it > 0L }
            ?: throw IllegalArgumentException(context.getString(R.string.error_server_identity_missing))
        val incomingIdentity = StableAccountIdentity(incomingInstanceId, incomingUserId)
        val previousSession = credentialManager.getSessionSnapshot()
        val previousIdentity = previousSession.identity
        val reauthenticatedSameAccount = previousIdentity == incomingIdentity &&
            previousSession.refreshToken.isNullOrBlank()
        // Unknown is the one-time migration state for pre-stable-ID installs.
        // It deliberately clears once rather than guessing from endpoint/username.
        val changed = hasSessionChanged(previousIdentity, incomingIdentity)

        if (changed) {
            // Ask WorkManager to cancel first; then the shared gate below waits
            // until a currently executing scan has actually left its full
            // read/network/write cycle.
            cancelScheduledBackupWork()
        }

        sessionOperationGuard.withOperation {
            if (changed) {
                val stopped = BackupForegroundService.stopAndAwait(context)
                if (!stopped) {
                    throw IllegalStateException("Timed out while stopping the previous backup session")
                }

                // Wait for any status sync that was not launched by the worker,
                // then make the new session's first sync bypass throttling.
                statusSyncManager.resetForSessionSwitch()

                // This barrier runs after earlier queued-file mirror writes. The
                // Room transaction then clears every other server-owned projection.
                backupQueue.clearAndAwaitPersistence()
                database.withTransaction {
                    database.photoStatusDao().getAll().forEach { status ->
                        database.photoStatusDao().delete(status.fileUri)
                    }
                    database.uploadRecordDao().getAll().forEach { record ->
                        database.uploadRecordDao().deleteByFileUri(record.fileUri)
                    }
                    database.backupHistoryDao().deleteAll()
                    database.queuedFileDao().deleteAll()
                    database.backupFolderDao().getAllOnce().forEach { folder ->
                        database.backupFolderDao().update(
                            folder.copy(
                                totalImages = 0,
                                backedUpImages = 0,
                                trashedImages = 0,
                                purgedImages = 0,
                                lastScanTime = 0
                            )
                        )
                    }
                }
                settingsPreferences.setUserPausedBackup(false)
                BackupResumePrompt.consume()
            }

            credentialManager.saveSession(
                serverAddress = serverAddress,
                username = username,
                password = password,
                rememberPassword = rememberPassword,
                accessToken = loginResponse.accessToken,
                refreshToken = loginResponse.refreshToken,
                expiresIn = loginResponse.expiresIn,
                instanceId = incomingIdentity.instanceId,
                userId = incomingIdentity.userId
            )
        }

        if (changed || reauthenticatedSameAccount) {
            // A changed account needs a clean rescan; re-authenticating the same
            // account must also wake any queue retained after terminal refresh
            // failure instead of waiting for the next periodic interval.
            // Post-commit scheduling is best-effort and independently recoverable
            // from the persisted periodic cadence. A WorkManager enqueue failure
            // must not report authentication failure after B is already active.
            runCatching {
                // Restore the selected periodic cadence and immediately rebuild
                // counts/queue against the new server. With auto-backup disabled this
                // remains scan-only; an explicit "立即备份" still works as before.
                BackgroundScanWorker.applyScanInterval(
                    context,
                    settingsPreferences.getScanIntervalMinutes()
                )
                BackgroundScanWorker.runNow(context)
            }.onFailure { error ->
                Log.e(TAG, "New session activated but rescan scheduling failed", error)
            }
        }

        Log.i(TAG, "Activated stable account session (changed=$changed)")
        changed
    }

    /** Cancels both periodic and transient workers that may still write old state. */
    private suspend fun cancelScheduledBackupWork() {
        val workManager = WorkManager.getInstance(context)
        val names = listOf(
            BackgroundScanWorker.WORK_NAME,
            BackgroundScanWorker.ONE_TIME_WORK_NAME,
            BackgroundScanWorker.FULL_SCAN_WORK_NAME,
            BackgroundScanWorker.TEST_SCAN_WORK_NAME,
            BackgroundScanWorker.RESUME_WORK_NAME
        )

        withContext(Dispatchers.IO) {
            names.forEach { name ->
                workManager.cancelUniqueWork(name).result.get(
                    WORK_CANCEL_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )
            }
        }
    }
}
