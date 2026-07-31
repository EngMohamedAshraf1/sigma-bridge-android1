package com.sigmabridge.app.di

import com.sigmabridge.app.data.settings.SecureSettingsRepository
import com.sigmabridge.app.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.SingletonComponent

/**
 * TelegramRepository and TranslationRepository bindings are added here once
 * their implementations exist (Phase 3, Phase 5) — same pattern as below.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindSettingsRepository(
        impl: SecureSettingsRepository
    ): SettingsRepository
}
