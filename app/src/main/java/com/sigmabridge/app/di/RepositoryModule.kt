package com.sigmabridge.app.di

import com.sigmabridge.app.data.cache.FileCacheManager
import com.sigmabridge.app.data.connectivity.AndroidConnectivityRepository
import com.sigmabridge.app.data.settings.SecureSettingsRepository
import com.sigmabridge.app.data.telegram.TelegramDownloadRepository
import com.sigmabridge.app.data.telegram.TelegramRepositoryImpl
import com.sigmabridge.app.domain.cache.CacheManager
import com.sigmabridge.app.domain.repository.ConnectivityRepository
import com.sigmabridge.app.domain.repository.DownloadRepository
import com.sigmabridge.app.domain.repository.SettingsRepository
import com.sigmabridge.app.domain.repository.TelegramRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.SingletonComponent

/**
 * TranslationRepository binding is added here once its implementation
 * exists (Phase 5) — same pattern as below.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindSettingsRepository(
        impl: SecureSettingsRepository
    ): SettingsRepository

    @Binds
    abstract fun bindConnectivityRepository(
        impl: AndroidConnectivityRepository
    ): ConnectivityRepository

    @Binds
    abstract fun bindTelegramRepository(
        impl: TelegramRepositoryImpl
    ): TelegramRepository

    @Binds
    abstract fun bindCacheManager(
        impl: FileCacheManager
    ): CacheManager

    @Binds
    abstract fun bindDownloadRepository(
        impl: TelegramDownloadRepository
    ): DownloadRepository
}
