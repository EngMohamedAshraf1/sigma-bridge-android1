package com.sigmabridge.app.di

import com.sigmabridge.app.BuildConfig
import com.sigmabridge.app.data.chat.NtfyChatRepository
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

    @Provides
    @Singleton
    fun provideChatRepository(
        ntfy: NtfyChatRepository,
        supabase: SupabaseChatRepository
    ): ChatRepository = when (BuildConfig.SIGMA_CHAT_BACKEND.trim().lowercase()) {
        "supabase" -> supabase
        else -> ntfy
    }
}
