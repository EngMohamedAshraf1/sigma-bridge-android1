package com.sigmabridge.app.di

import com.sigmabridge.app.domain.dispatch.UpdateHandler
import com.sigmabridge.app.domain.dispatch.VoiceMessageHandler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * A future handler (text, photo, PDF...) is registered by adding one more
 * @Binds @IntoSet function here — UpdateDispatcher itself never changes.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DispatchModule {

    @Binds
    @IntoSet
    abstract fun bindVoiceMessageHandler(impl: VoiceMessageHandler): UpdateHandler
}
