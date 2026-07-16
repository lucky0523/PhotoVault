package com.photovault

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.photovault.data.local.SettingsPreferences
import com.photovault.service.BackgroundScanWorker
import com.photovault.service.ConditionBroadcastReceiver
import com.photovault.service.MediaStoreObserver
import com.photovault.util.AppForegroundState
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
            // Generous in-memory + on-disk thumbnail caches so scrolling a large
            // folder grid (thousands of items) and re-entering a folder reuse
            // already-decoded thumbnails instead of re-decoding from disk/video.
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()

        // Track app foreground/background so background workers can decide whether
        // to resume a user-paused backup silently (background) or ask for
        // confirmation (foreground).
        AppForegroundState.register(this)

        // Schedule background scanning. Normally the 15-minute periodic worker;
        // if the debug ~10-second test interval is selected, start that chain
        // instead so the pause resume/confirm flow can be tested across restarts.
        if (settingsPreferences.getScanIntervalMinutes() ==
            SettingsPreferences.SCAN_INTERVAL_TEST_10S
        ) {
            BackgroundScanWorker.enqueueTestScan(this)
        } else {
            BackgroundScanWorker.schedule(this)
        }

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
