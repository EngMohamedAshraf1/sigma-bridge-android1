package com.sigmabridge.app.data.chat

import com.sigmabridge.app.domain.chat.ChatReaction
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupabaseReactionRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val sessionManager: SupabaseSessionManager,
    private val identity: ChatIdentity
) {
    suspend fun setReaction(messageId: String, emoji: String): Result<Unit> = runCatching {
        sessionManager.ensureAuthenticatedSession().getOrThrow()
        val serverMessageId = serverMessageIdFor(messageId)
        supabase.postgrest.rpc(
            "sigma_set_reaction",
            SetReactionRpcParams(messageId = serverMessageId, emoji = emoji)
        ).decodeAs<SupabaseMessageReactionRow>()
        Unit
    }

    suspend fun removeReaction(messageId: String): Result<Unit> = runCatching {
        sessionManager.ensureAuthenticatedSession().getOrThrow()
        val serverMessageId = serverMessageIdFor(messageId)
        supabase.postgrest.rpc(
            "sigma_remove_reaction",
            RemoveReactionRpcParams(messageId = serverMessageId)
        ).decodeAs<Boolean>()
        Unit
    }

    suspend fun getReactions(partnerId: String): Result<List<ChatReaction>> = runCatching {
        sessionManager.ensureAuthenticatedSession().getOrThrow()
        val rows = supabase.postgrest.rpc(
            "sigma_get_reactions",
            GetReactionsRpcParams(
                partnerPublicId = partnerId,
                conversationKey = identity.conversationKeyFor(partnerId)
                    .joinToString("") { "%02x".format(it) }
            )
        ).decodeList<SupabaseReactionRow>()

        rows.map { row ->
            ChatReaction(
                messageId = row.clientMessageId,
                userId = row.userPublicId,
                emoji = row.emoji,
                createdAt = parseTimestamp(row.createdAt)
            )
        }
    }

    suspend fun resolveRealtimeContext(
        serverMessageId: String,
        serverUserId: String
    ): Result<ReactionRealtimeContext> = runCatching {
        sessionManager.ensureAuthenticatedSession().getOrThrow()
        val rows = supabase.postgrest.rpc(
            "sigma_get_reaction_context",
            GetReactionContextRpcParams(
                messageId = serverMessageId,
                userId = serverUserId
            )
        ).decodeList<ReactionRealtimeContextRow>()
        val row = rows.firstOrNull() ?: error("REACTION_CONTEXT_NOT_FOUND")
        ReactionRealtimeContext(row.clientMessageId, row.userPublicId)
    }

    private suspend fun serverMessageIdFor(clientMessageId: String): String {
        val conversationKey = identity.conversationKeyHex()
        val partnerId = identity.partnerId
        val conversationId = supabase.postgrest.rpc(
            "sigma_ensure_conversation",
            EnsureConversationRpcParams(
                partnerPublicId = partnerId,
                conversationKey = conversationKey
            )
        ).decodeAs<String>()

        return supabase.postgrest
            .from("messages")
            .select {
                filter {
                    eq("conversation_id", conversationId)
                    eq("client_message_id", clientMessageId)
                }
            }
            .decodeSingle<SupabaseMessageRow>()
            .id
    }

    private fun parseTimestamp(value: String): Long =
        runCatching { java.time.Instant.parse(value).toEpochMilli() }
            .getOrElse { System.currentTimeMillis() }
}

data class ReactionRealtimeContext(
    val clientMessageId: String,
    val userPublicId: String
)
