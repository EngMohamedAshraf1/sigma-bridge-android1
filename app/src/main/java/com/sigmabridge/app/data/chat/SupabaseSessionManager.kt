package com.sigmabridge.app.data.chat

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.Auth
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
    private val auth: Auth
        get() = supabase.pluginManager.getPlugin(Auth)

    suspend fun ensureAnonymousSession(): Result<String> = runCatching {
        val existing = auth.currentUserOrNull()
        if (existing != null) return@runCatching existing.id

        auth.signInAnonymously()
        auth.currentUserOrNull()?.id
            ?: error("Supabase anonymous sign-in succeeded but no user session was returned.")
    }

    fun currentUserId(): String? = auth.currentUserOrNull()?.id
}
