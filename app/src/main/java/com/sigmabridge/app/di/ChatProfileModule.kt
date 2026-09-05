package com.sigmabridge.app.di

import com.sigmabridge.app.data.chat.ChatProfileRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ChatProfileModule {
    @Provides
    @Singleton
    fun provideChatProfileRepository(repository: ChatProfileRepository): ChatProfileRepository = repository
}
