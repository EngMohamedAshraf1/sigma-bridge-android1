package com.sigmabridge.app.data.chat

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.Auth
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the already-authenticated Supabase session for Private Chat.
 *
 * Anonymous accounts are no longer created by the chat. Authentication happens
 * once through the simple passwordless email flow, and every chat operation uses
 * that persistent Supabase Auth user.
 */
@Singleton
class SupabaseSessionManager @Inject constructor(
    private val supabase: SupabaseClient
) {
    private val auth: Auth
        get() = supabase.pluginManager.getPlugin(Auth)

    private val sessionMutex = Mutex()

    suspend fun ensureAuthenticatedSession(): Result<String> = sessionMutex.withLock {
        runCatching {
            auth.awaitInitialization()
            auth.currentUserOrNull()?.id
                ?: error("AUTH_REQUIRED")
        }
    }

    fun currentUserId(): String? = auth.currentUserOrNull()?.id
}
