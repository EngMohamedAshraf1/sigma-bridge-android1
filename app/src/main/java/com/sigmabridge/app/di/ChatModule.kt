package com.sigmabridge.app.di

import com.sigmabridge.app.data.chat.SupabaseChatRepository
import com.sigmabridge.app.domain.chat.ChatRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ChatModule {

    /** Private Chat uses Supabase as its only transport backend. */
    @Provides
    @Singleton
    fun provideChatRepository(
        supabase: SupabaseChatRepository
    ): ChatRepository = supabase
}
