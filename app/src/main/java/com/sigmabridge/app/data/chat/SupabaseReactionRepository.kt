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
        sessionManager.ensureAnonymousSession().getOrThrow()
        val serverMessageId = serverMessageIdFor(messageId)
        supabase.postgrest.rpc(
            "sigma_set_reaction",
            SetReactionRpcParams(messageId = serverMessageId, emoji = emoji)
        ).decodeAs<SupabaseMessageReactionRow>()
        Unit
    }

    suspend fun removeReaction(messageId: String): Result<Unit> = runCatching {
        sessionManager.ensureAnonymousSession().getOrThrow()
        val serverMessageId = serverMessageIdFor(messageId)
        supabase.postgrest.rpc(
            "sigma_remove_reaction",
            RemoveReactionRpcParams(messageId = serverMessageId)
        ).decodeAs<Boolean>()
        Unit
    }

    suspend fun getReactions(partnerId: String): Result<List<ChatReaction>> = runCatching {
        sessionManager.ensureAnonymousSession().getOrThrow()
        val rows = supabase.postgrest.rpc(
            "sigma_get_reactions",
            GetReactionsRpcParams(
                partnerPublicId = partnerId,
                conversationKey = identity.conversationKeyHex()
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

    private suspend fun serverMessageIdFor(clientMessageId: String): String {
        val conversationKey = identity.conversationKeyHex()
        val partnerId = identity.partnerId
        supabase.postgrest.rpc(
            "sigma_ensure_conversation",
            EnsureConversationRpcParams(
                partnerPublicId = partnerId,
                conversationKey = conversationKey
            )
        ).decodeAs<String>()

        val conversationId = supabase.postgrest
            .from("conversations")
            .select { filter { eq("conversation_key", conversationKey) } }
            .decodeSingle<ConversationIdRow>()
            .id

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
