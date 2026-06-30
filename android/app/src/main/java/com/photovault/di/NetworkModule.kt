package com.photovault.di

import com.photovault.data.api.AuthApi
import com.photovault.data.api.AuthInterceptor
import com.photovault.data.api.BackupApi
import com.photovault.data.api.BaseUrlInterceptor
import com.photovault.data.api.FileApi
import com.photovault.data.local.CredentialManager
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

    @Provides
    @Singleton
    fun provideAuthInterceptor(credentialManager: CredentialManager): AuthInterceptor {
        return AuthInterceptor(credentialManager)
    }

    @Provides
    @Singleton
    fun provideBaseUrlInterceptor(credentialManager: CredentialManager): BaseUrlInterceptor {
        return BaseUrlInterceptor(credentialManager)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        baseUrlInterceptor: BaseUrlInterceptor
    ): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        return OkHttpClient.Builder()
            .addInterceptor(baseUrlInterceptor)
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, credentialManager: CredentialManager): Retrofit {
        // Use a placeholder base URL; the actual URL is set dynamically per request
        val baseUrl = credentialManager.getServerAddress()?.let { address ->
            if (address.isNotEmpty()) {
                normalizeServerUrl(address)
            } else {
                "http://localhost:8000/"
            }
        } ?: "http://localhost:8000/"

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
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

    private fun normalizeServerUrl(address: String): String {
        var url = address.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        if (!url.endsWith("/")) {
            url = "$url/"
        }
        return url
    }
}
