package com.sigmabridge.app.data.chat

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.gotrue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the Supabase anonymous-auth session alive for Private Chat.
 *
 * This class intentionally knows nothing about Telegram or Gemini.
 */
@Singleton
class SupabaseSessionManager @Inject constructor(
    private val supabase: SupabaseClient
) {
    suspend fun ensureAnonymousSession(): Result<String> = runCatching {
        val existing = supabase.gotrue.currentUserOrNull()
        if (existing != null) return@runCatching existing.id

        supabase.gotrue.signInAnonymously()
        supabase.gotrue.currentUserOrNull()?.id
            ?: error("Supabase anonymous sign-in succeeded but no user session was returned.")
    }

    fun currentUserId(): String? = supabase.gotrue.currentUserOrNull()?.id
}
