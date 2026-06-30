package com.photovault.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receiver that triggers when the device boots up.
 * Re-schedules the periodic background scan worker after reboot.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Re-schedule the periodic scan worker
            BackgroundScanWorker.schedule(context)
        }
    }
}
