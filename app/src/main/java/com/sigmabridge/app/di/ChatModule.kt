package com.sigmabridge.app.di

import com.sigmabridge.app.data.chat.NtfyChatRepository
import com.sigmabridge.app.domain.chat.ChatRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ChatModule {

    @Binds
    abstract fun bindChatRepository(
        impl: NtfyChatRepository
    ): ChatRepository
}
