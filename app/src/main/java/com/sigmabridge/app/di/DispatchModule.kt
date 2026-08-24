package com.sigmabridge.app.di

import com.sigmabridge.app.domain.dispatch.AudioMessageHandler
import com.sigmabridge.app.domain.dispatch.LanguageCallbackHandler
import com.sigmabridge.app.domain.dispatch.LanguageCommandHandler
import com.sigmabridge.app.domain.dispatch.UpdateHandler
import com.sigmabridge.app.domain.dispatch.VoiceMessageHandler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Future handlers are registered here without changing UpdateDispatcher.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DispatchModule {

    @Binds
    @IntoSet
    abstract fun bindVoiceMessageHandler(impl: VoiceMessageHandler): UpdateHandler

    @Binds
    @IntoSet
    abstract fun bindAudioMessageHandler(impl: AudioMessageHandler): UpdateHandler

    @Binds
    @IntoSet
    abstract fun bindLanguageCommandHandler(impl: LanguageCommandHandler): UpdateHandler

    @Binds
    @IntoSet
    abstract fun bindLanguageCallbackHandler(impl: LanguageCallbackHandler): UpdateHandler
}
