package com.sigmabridge.app.di

import com.sigmabridge.app.data.chat.ChatCrypto
import com.sigmabridge.app.data.chat.ChatIdentity
import com.sigmabridge.app.data.chat.NtfyChatRepository
import com.sigmabridge.app.domain.chat.ChatRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ChatModule {

    @Binds
    abstract fun bindChatRepository(
        impl: NtfyChatRepository
    ): ChatRepository

    companion object {
        @Provides
        @Singleton
        fun provideChatCrypto(identity: ChatIdentity): ChatCrypto = ChatCrypto(identity)
    }
}
