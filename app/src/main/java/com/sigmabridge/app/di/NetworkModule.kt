package com.sigmabridge.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // This client is shared by Telegram's getUpdates (a 30s long-poll) AND Gemini's
        // generateContent (translating a voice note - can reasonably take longer for
        // bigger files). 60s gives comfortable margin over Telegram's poll without making
        // Gemini calls prone to a client-side SocketTimeoutException that our retry logic
        // (which only catches HTTP 503, not I/O timeouts) wouldn't recover from.
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
}
