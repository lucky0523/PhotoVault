package com.photovault.service

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

class MediaStoreObserver(private val context: Context) : ContentObserver(Handler(Looper.getMainLooper())) {

    companion object {
        private var observer: MediaStoreObserver? = null

        fun register(context: Context) {
            if (observer == null) {
                observer = MediaStoreObserver(context)
                context.contentResolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    observer!!
                )
            }
        }

        fun unregister(context: Context) {
            observer?.let {
                context.contentResolver.unregisterContentObserver(it)
                observer = null
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val debounceDelayMs = 2000L
    private var pendingRunnable: Runnable? = null

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        scheduleScan()
    }

    override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
        super.onChange(selfChange, uri)
        scheduleScan()
    }

    private fun scheduleScan() {
        pendingRunnable?.let { handler.removeCallbacks(it) }

        val runnable = Runnable {
            android.util.Log.i("PhotoVaultMedia", "MediaStore changed, triggering scan")
            BackgroundScanWorker.runOnce(context)
        }
        pendingRunnable = runnable
        handler.postDelayed(runnable, debounceDelayMs)
    }
}