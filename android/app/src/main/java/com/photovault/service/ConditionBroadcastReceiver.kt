package com.photovault.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * BroadcastReceiver that listens for battery and network connectivity changes.
 *
 * When conditions change (battery level or WiFi state), it triggers a one-time
 * backup condition check via WorkManager to determine if backup should
 * start, pause, or resume.
 */
class ConditionBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val CONDITION_CHECK_WORK_NAME = "condition_check_work"

        /**
         * Creates an IntentFilter for the conditions this receiver monitors.
         */
        fun getIntentFilter(): IntentFilter {
            return IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                @Suppress("DEPRECATION")
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            }
        }

        /**
         * Registers this receiver dynamically with the given context.
         */
        fun register(context: Context): ConditionBroadcastReceiver {
            val receiver = ConditionBroadcastReceiver()
            context.registerReceiver(receiver, getIntentFilter())
            return receiver
        }

        /**
         * Unregisters this receiver from the given context.
         */
        fun unregister(context: Context, receiver: ConditionBroadcastReceiver) {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BATTERY_CHANGED,
            @Suppress("DEPRECATION")
            ConnectivityManager.CONNECTIVITY_ACTION -> {
                triggerConditionCheck(context)
            }
        }
    }

    /**
     * Triggers a one-time condition check work request.
     * Uses KEEP policy to avoid duplicate checks when multiple events fire rapidly.
     */
    private fun triggerConditionCheck(context: Context) {
        val workRequest = OneTimeWorkRequestBuilder<ConditionCheckWorker>().build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            CONDITION_CHECK_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }
}
