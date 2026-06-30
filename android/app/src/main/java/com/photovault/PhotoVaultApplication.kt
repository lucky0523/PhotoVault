package com.photovault

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
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

    private var conditionReceiver: ConditionBroadcastReceiver? = null

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .crossfade(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()

        // Schedule periodic background scan (every 15 minutes)
        BackgroundScanWorker.schedule(this)

        // Register broadcast receiver for battery and network changes
        conditionReceiver = ConditionBroadcastReceiver.register(this)

        // Register observer for MediaStore changes (new photos/videos)
        MediaStoreObserver.register(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        conditionReceiver?.let {
            ConditionBroadcastReceiver.unregister(this, it)
        }
    }
}
