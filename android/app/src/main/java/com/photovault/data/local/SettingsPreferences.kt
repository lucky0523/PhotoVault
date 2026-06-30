package com.photovault.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages backup condition settings persistence using SharedPreferences.
 *
 * Settings:
 * - WiFi only: Always true (cannot be disabled)
 * - Minimum battery level: 20%-80%, default 50%
 * - Scan interval: 5/15/30/60 minutes, default 15
 */
@Singleton
class SettingsPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "photovault_settings"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_MIN_BATTERY_LEVEL = "min_battery_level"
        private const val KEY_SCAN_INTERVAL_MINUTES = "scan_interval_minutes"

        const val DEFAULT_WIFI_ONLY = true
        const val DEFAULT_MIN_BATTERY_LEVEL = 50
        const val DEFAULT_SCAN_INTERVAL_MINUTES = 15

        const val MIN_BATTERY_LEVEL_LOWER = 20
        const val MIN_BATTERY_LEVEL_UPPER = 80

        val SCAN_INTERVAL_OPTIONS = listOf(5, 15, 30, 60)
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _wifiOnly = MutableStateFlow(DEFAULT_WIFI_ONLY)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _minBatteryLevel = MutableStateFlow(DEFAULT_MIN_BATTERY_LEVEL)
    val minBatteryLevel: StateFlow<Int> = _minBatteryLevel.asStateFlow()

    private val _scanIntervalMinutes = MutableStateFlow(DEFAULT_SCAN_INTERVAL_MINUTES)
    val scanIntervalMinutes: StateFlow<Int> = _scanIntervalMinutes.asStateFlow()

    init {
        // Load saved values
        _wifiOnly.value = prefs.getBoolean(KEY_WIFI_ONLY, DEFAULT_WIFI_ONLY)
        _minBatteryLevel.value = prefs.getInt(KEY_MIN_BATTERY_LEVEL, DEFAULT_MIN_BATTERY_LEVEL)
        _scanIntervalMinutes.value = prefs.getInt(KEY_SCAN_INTERVAL_MINUTES, DEFAULT_SCAN_INTERVAL_MINUTES)
    }

    /**
     * Whether backup is restricted to WiFi only.
     * When false, backup is also allowed on cellular networks.
     */
    fun getWifiOnly(): Boolean = _wifiOnly.value

    /**
     * Set whether backup is restricted to WiFi only.
     */
    fun setWifiOnly(enabled: Boolean) {
        _wifiOnly.value = enabled
        prefs.edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
    }

    /**
     * Get the minimum battery level required to start backup.
     */
    fun getMinBatteryLevel(): Int = _minBatteryLevel.value

    /**
     * Set the minimum battery level required to start backup.
     * Clamped to range [20, 80].
     */
    fun setMinBatteryLevel(level: Int) {
        val clamped = level.coerceIn(MIN_BATTERY_LEVEL_LOWER, MIN_BATTERY_LEVEL_UPPER)
        _minBatteryLevel.value = clamped
        prefs.edit().putInt(KEY_MIN_BATTERY_LEVEL, clamped).apply()
    }

    /**
     * Get the scan interval in minutes.
     */
    fun getScanIntervalMinutes(): Int = _scanIntervalMinutes.value

    /**
     * Set the scan interval in minutes.
     * Must be one of: 5, 15, 30, 60.
     */
    fun setScanIntervalMinutes(minutes: Int) {
        val validMinutes = if (minutes in SCAN_INTERVAL_OPTIONS) minutes else DEFAULT_SCAN_INTERVAL_MINUTES
        _scanIntervalMinutes.value = validMinutes
        prefs.edit().putInt(KEY_SCAN_INTERVAL_MINUTES, validMinutes).apply()
    }
}
