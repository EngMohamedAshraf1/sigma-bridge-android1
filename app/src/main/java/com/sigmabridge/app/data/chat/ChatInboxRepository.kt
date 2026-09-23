package com.sigmabridge.app.data.chat

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers incoming Private Chat messages independently of the locally selected
 * conversation. Account UUIDs and conversation IDs are the only v2 identities.
 */
@Singleton
class ChatInboxRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val sessionManager: SupabaseSessionManager
) {
    suspend fun fetchUndeliveredMessages(): Result<List<SupabaseUndeliveredMessageV2Row>> =
        runCatching {
            sessionManager.ensureAuthenticatedSession().getOrThrow()
            supabase.postgrest.rpc("sigma_get_undelivered_messages_v2")
                .decodeList<SupabaseUndeliveredMessageV2Row>()
        }
}
