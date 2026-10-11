package com.huoyi.photovault.di

import com.huoyi.photovault.data.local.CredentialManager
import com.huoyi.photovault.data.network.endpoint.Clock
import com.huoyi.photovault.data.network.endpoint.ConnectivityNetworkMonitor
import com.huoyi.photovault.data.network.endpoint.ElapsedRealtimeClock
import com.huoyi.photovault.data.network.endpoint.EndpointConfigSource
import com.huoyi.photovault.data.network.endpoint.EndpointResolver
import com.huoyi.photovault.data.network.endpoint.EndpointRoutingInterceptor
import com.huoyi.photovault.data.network.endpoint.EndpointScope
import com.huoyi.photovault.data.network.endpoint.LanProbe
import com.huoyi.photovault.data.network.endpoint.NetworkMonitor
import com.huoyi.photovault.data.network.endpoint.OkHttpLanProbe
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** OkHttpClient whose requests are tagged with the active backup task session. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BackupTaskClient

/** BackupApi bound to [BackupTaskClient]; used only by ChunkUploader (design §5.1). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BackupTaskApi

/** Wiring for the dual-endpoint routing core (design §5.1). */
@Module
@InstallIn(SingletonComponent::class)
abstract class EndpointModule {

    @Binds
    abstract fun bindEndpointConfigSource(impl: CredentialManager): EndpointConfigSource

    @Binds
    abstract fun bindNetworkMonitor(impl: ConnectivityNetworkMonitor): NetworkMonitor

    companion object {
        @Provides
        @Singleton
        fun provideLanProbe(): LanProbe = OkHttpLanProbe()

        @Provides
        @Singleton
        fun provideClock(): Clock = ElapsedRealtimeClock

        @Provides
        @Singleton
        @EndpointScope
        fun provideEndpointScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Provides
        @Singleton
        fun provideEndpointRoutingInterceptor(
            resolver: EndpointResolver,
            config: EndpointConfigSource
        ): EndpointRoutingInterceptor = EndpointRoutingInterceptor(resolver, config)
    }
}
