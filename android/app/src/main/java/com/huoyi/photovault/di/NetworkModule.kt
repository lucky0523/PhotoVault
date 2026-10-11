package com.huoyi.photovault.di

import com.huoyi.photovault.data.api.AuthApi
import com.huoyi.photovault.data.api.AuthInterceptor
import com.huoyi.photovault.data.api.BackupApi
import com.huoyi.photovault.data.api.FileApi
import com.huoyi.photovault.data.api.TokenAuthenticator
import com.huoyi.photovault.data.api.TokenRefreshCoordinator
import com.huoyi.photovault.data.local.CredentialManager
import com.huoyi.photovault.data.network.endpoint.BackupTaskTagInterceptor
import com.huoyi.photovault.data.network.endpoint.DispatchMarkerInterceptor
import com.huoyi.photovault.data.network.endpoint.EndpointResolver
import com.huoyi.photovault.data.network.endpoint.EndpointRoutingInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // Placeholder base URL: EndpointRoutingInterceptor rewrites scheme/host/port of
    // every request to the LAN or public address chosen by the route session.
    private const val PLACEHOLDER_BASE_URL = "http://photovault.invalid/"

    @Provides
    @Singleton
    fun provideAuthInterceptor(
        credentialManager: CredentialManager,
        tokenRefreshCoordinator: TokenRefreshCoordinator,
        resolver: EndpointResolver
    ): AuthInterceptor {
        return AuthInterceptor(credentialManager, tokenRefreshCoordinator, resolver)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        routingInterceptor: EndpointRoutingInterceptor,
        authInterceptor: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator
    ): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        return OkHttpClient.Builder()
            .addInterceptor(routingInterceptor)
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .addNetworkInterceptor(DispatchMarkerInterceptor)
            .authenticator(tokenAuthenticator)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    /**
     * Shared client plus [BackupTaskTagInterceptor] in front, so ChunkUploader's
     * requests follow the active backup task session. Shares the connection pool
     * and dispatcher with the shared client.
     */
    @Provides
    @Singleton
    @BackupTaskClient
    fun provideBackupTaskClient(shared: OkHttpClient, resolver: EndpointResolver): OkHttpClient {
        return shared.newBuilder()
            .apply { interceptors().add(0, BackupTaskTagInterceptor(resolver)) }
            .build()
    }

    @Provides
    @Singleton
    @BackupTaskApi
    fun provideBackupTaskApi(@BackupTaskClient client: OkHttpClient): BackupApi {
        return Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BackupApi::class.java)
    }

    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi {
        return retrofit.create(AuthApi::class.java)
    }

    @Provides
    @Singleton
    fun provideBackupApi(retrofit: Retrofit): BackupApi {
        return retrofit.create(BackupApi::class.java)
    }

    @Provides
    @Singleton
    fun provideFileApi(retrofit: Retrofit): FileApi {
        return retrofit.create(FileApi::class.java)
    }
}
