package com.photovault.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.photovault.data.local.SettingsPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks backup conditions: battery level and WiFi connectivity.
 *
 * Battery rules (thresholds configurable via SettingsPreferences):
 * - Start backup when battery > configured minimum level
 * - Pause backup when battery <= configured minimum level
 * - Resume backup when battery >= configured minimum level + 5% (hysteresis)
 *
 * WiFi rules:
 * - Only backup when connected to WiFi
 */
@Singleton
class BackupConditionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsPreferences: SettingsPreferences
) {

    companion object {
        const val BATTERY_START_THRESHOLD = 50
        const val BATTERY_PAUSE_THRESHOLD = 50
        const val BATTERY_RESUME_THRESHOLD = 55
        const val HYSTERESIS_OFFSET = 5
    }

    /**
     * Returns the current battery level as a percentage (0-100).
     */
    fun getBatteryLevel(): Int {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) {
            (level * 100) / scale
        } else {
            -1
        }
    }

    /**
     * Checks if the device is currently charging (AC, USB, or wireless).
     */
    fun isCharging(): Boolean {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    /**
     * Checks if the device is currently connected to WiFi.
     */
    fun isWifiConnected(): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Checks if the device has a usable network connection for backup.
     * If WiFi-only is enabled, only WiFi counts. Otherwise WiFi or cellular both count.
     */
    fun isNetworkAvailableForBackup(): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return false
        }

        return if (settingsPreferences.getWifiOnly()) {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        }
    }

    /**
     * Determines if backup should start.
     * Both conditions must be true: battery > configured minimum AND network available
     * (WiFi, or cellular when WiFi-only is disabled).
     */
    fun shouldStartBackup(): Boolean {
        val batteryLevel = getBatteryLevel()
        val threshold = settingsPreferences.getMinBatteryLevel()
        return batteryLevel > threshold && isNetworkAvailableForBackup()
    }

    /**
     * Determines if backup should be paused.
     * Either condition failing triggers pause: battery <= configured minimum (unless charging) OR no usable network.
     */
    fun shouldPauseBackup(): Boolean {
        val batteryLevel = getBatteryLevel()
        val threshold = settingsPreferences.getMinBatteryLevel()
        val batteryOk = isCharging() || batteryLevel > threshold
        return !batteryOk || !isNetworkAvailableForBackup()
    }

    /**
     * Determines if backup can resume after being paused.
     * Uses hysteresis: battery must be >= (configured minimum + 5%) AND a usable network.
     */
    fun shouldResumeBackup(): Boolean {
        val batteryLevel = getBatteryLevel()
        val threshold = settingsPreferences.getMinBatteryLevel() + HYSTERESIS_OFFSET
        return batteryLevel >= threshold && isNetworkAvailableForBackup()
    }

    /**
     * Gets the configured minimum battery level from settings.
     */
    fun getMinBatteryLevel(): Int {
        return settingsPreferences.getMinBatteryLevel()
    }
}
