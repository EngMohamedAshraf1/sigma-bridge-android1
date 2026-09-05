package com.sigmabridge.app.data.chat

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers incoming Private Chat messages independently of the locally selected
 * conversation. This is what allows the first message from a new person to arrive
 * without the recipient searching for that person first.
 */
@Singleton
class ChatInboxRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val sessionManager: SupabaseSessionManager
) {
    suspend fun fetchUndeliveredMessages(): Result<List<SupabaseUndeliveredMessageRow>> =
        runCatching {
            sessionManager.ensureAnonymousSession().getOrThrow()
            supabase.postgrest.rpc("sigma_get_undelivered_messages")
                .decodeList<SupabaseUndeliveredMessageRow>()
        }
}
