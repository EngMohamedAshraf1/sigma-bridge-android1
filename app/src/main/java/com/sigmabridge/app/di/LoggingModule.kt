package com.sigmabridge.app.di

import com.sigmabridge.app.data.logging.AndroidBridgeLogger
import com.sigmabridge.app.domain.logging.BridgeLogger
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class LoggingModule {

    @Binds
    abstract fun bindBridgeLogger(impl: AndroidBridgeLogger): BridgeLogger
}
