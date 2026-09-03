package com.sigmabridge.app.data.chat

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.Auth
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the Supabase anonymous-auth session alive for Private Chat.
 *
 * Anonymous sign-in is serialized so concurrent chat initialization cannot create
 * multiple Supabase users for the same persisted Sigma Bridge identity.
 *
 * This class intentionally knows nothing about Telegram or Gemini.
 */
@Singleton
class SupabaseSessionManager @Inject constructor(
    private val supabase: SupabaseClient
) {
    private val auth: Auth
        get() = supabase.pluginManager.getPlugin(Auth)

    private val sessionMutex = Mutex()

    suspend fun ensureAnonymousSession(): Result<String> = sessionMutex.withLock {
        runCatching {
            val existing = auth.currentUserOrNull()
            if (existing != null) return@runCatching existing.id

            auth.signInAnonymously()
            auth.currentUserOrNull()?.id
                ?: error("Supabase anonymous sign-in succeeded but no user session was returned.")
        }
    }

    fun currentUserId(): String? = auth.currentUserOrNull()?.id
}
