package com.photovault

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.photovault.data.local.SettingsPreferences
import com.photovault.service.BackgroundScanWorker
import com.photovault.service.ConditionBroadcastReceiver
import com.photovault.service.MediaStoreObserver
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class PhotoVaultApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var settingsPreferences: SettingsPreferences

    private var conditionReceiver: ConditionBroadcastReceiver? = null

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .components {
                // Enable decoding a poster frame from local video URIs so
                // video thumbnails render in the folder detail grid.
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()

        // Schedule periodic background scan (every 15 minutes)
        BackgroundScanWorker.schedule(this)

        // One-time full re-scan after an upgrade that added new media-type
        // support (e.g. videos), so files that predate the current scanner —
        // and therefore sit before each folder's lastScanTime — get backed up.
        maybeRunMediaBackfillScan()

        // Register broadcast receiver for battery and network changes
        conditionReceiver = ConditionBroadcastReceiver.register(this)

        // Register observer for MediaStore changes (new photos/videos)
        MediaStoreObserver.register(this)
    }

    /**
     * Runs a forced full scan once per media-capability version bump so newly
     * supported file types (videos) that already existed on the device are
     * discovered and enqueued for backup, instead of being skipped by the
     * normal incremental (lastScanTime-based) scan.
     */
    private fun maybeRunMediaBackfillScan() {
        val done = settingsPreferences.getMediaBackfillVersion()
        if (done < SettingsPreferences.CURRENT_MEDIA_BACKFILL_VERSION) {
            BackgroundScanWorker.runNow(this)
            settingsPreferences.setMediaBackfillVersion(
                SettingsPreferences.CURRENT_MEDIA_BACKFILL_VERSION
            )
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        conditionReceiver?.let {
            ConditionBroadcastReceiver.unregister(this, it)
        }
    }
}
