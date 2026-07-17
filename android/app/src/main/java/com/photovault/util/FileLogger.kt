package com.photovault.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight append-only diagnostic logger, gated behind a Settings toggle.
 *
 * Purpose: some OEM ROMs (ColorOS/OxygenOS/MIUI) suppress third-party app logs
 * from `adb logcat`, which makes diagnosing background-execution issues
 * impossible via logcat. This logger writes timestamped lines to a file.
 *
 * It writes to the app's EXTERNAL files dir when available:
 *   /sdcard/Android/data/com.photovault/files/diagnostics.log
 * which can be pulled without `run-as`:
 *   adb pull /sdcard/Android/data/com.photovault/files/diagnostics.log
 * Falls back to internal `filesDir` (read via `adb shell run-as com.photovault
 * cat files/diagnostics.log`) if external storage is unavailable.
 *
 * Thread-safe and self-rotating (bounded file size). Debug/diagnostic only.
 */
object FileLogger {

    private const val FILE_NAME = "diagnostics.log"
    private const val MAX_BYTES = 1024 * 1024L

    /** Toggled from Settings; when false, [log] is a cheap no-op. */
    @Volatile
    var enabled: Boolean = false

    @Volatile
    private var logFile: File? = null

    private val lock = Any()
    private val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Wire up the file path and initial enabled state. Call from Application.onCreate. */
    fun init(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        val dir = app.getExternalFilesDir(null) ?: app.filesDir
        logFile = File(dir, FILE_NAME)
        this.enabled = enabled
    }

    /** Absolute path of the log file, for surfacing in the UI. Null if not initialized. */
    fun currentPath(): String? = logFile?.absolutePath

    /** Append one timestamped line. No-op when disabled or not initialized. */
    fun log(tag: String, message: String) {
        if (!enabled) return
        val file = logFile ?: return
        synchronized(lock) {
            try {
                if (file.length() > MAX_BYTES) {
                    val text = file.readText()
                    file.writeText(text.substring(text.length / 2))
                }
                file.appendText("${timestamp.format(Date())}  [$tag] $message\n")
            } catch (_: Exception) {
                // Never let diagnostics break the app.
            }
        }
    }

    /** Truncate the log (e.g. when the user re-enables logging to start fresh). */
    fun clear() {
        val file = logFile ?: return
        synchronized(lock) {
            try {
                file.writeText("")
            } catch (_: Exception) {
            }
        }
    }
}
