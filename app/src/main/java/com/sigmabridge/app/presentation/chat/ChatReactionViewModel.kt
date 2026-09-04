package com.sigmabridge.app.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sigmabridge.app.data.chat.ChatIdentity
import com.sigmabridge.app.data.chat.SupabaseReactionRepository
import com.sigmabridge.app.data.chat.SupabaseRealtimeReactionRow
import com.sigmabridge.app.domain.chat.ChatReaction
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatReactionViewModel @Inject constructor(
    private val reactionRepository: SupabaseReactionRepository,
    private val identity: ChatIdentity,
    private val supabase: SupabaseClient
) : ViewModel() {

    private val _reactions = MutableStateFlow<Map<String, List<ChatReaction>>>(emptyMap())
    val reactions: StateFlow<Map<String, List<ChatReaction>>> = _reactions.asStateFlow()

    private val pendingOwnOperations = mutableMapOf<String, PendingOwnOperation>()
    private var realtimeJob: Job? = null

    init {
        connect()
    }

    private fun connect() {
        val partner = identity.partnerId.trim()
        if (partner.isBlank() || partner == identity.myId) return

        realtimeJob?.cancel()
        realtimeJob = viewModelScope.launch {
            val channel = supabase.channel("sigma-chat-reactions-${identity.conversationKeyHex()}")
            val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "message_reactions"
            }

            val collector = launch {
                changes.collect { action ->
                    applyRealtimeAction(action)
                }
            }

            try {
                channel.subscribe(blockUntilSubscribed = true)
                reactionRepository.getReactions(partner)
                    .onSuccess { all ->
                        val serverSnapshot = all.groupBy { it.messageId }
                        val merged = serverSnapshot.toMutableMap()
                        pendingOwnOperations.forEach { (messageId, pending) ->
                            val withoutOwn = merged[messageId].orEmpty()
                                .filterNot { it.userId == identity.myId }
                            if (pending.desiredEmoji == null) {
                                if (withoutOwn.isEmpty()) merged.remove(messageId) else merged[messageId] = withoutOwn
                            } else {
                                merged[messageId] = withoutOwn + ChatReaction(
                                    messageId = messageId,
                                    userId = identity.myId,
                                    emoji = pending.desiredEmoji,
                                    createdAt = System.currentTimeMillis()
                                )
                            }
                        }
                        _reactions.value = merged
                    }
                collector.join()
            } finally {
                collector.cancel()
                runCatching { channel.unsubscribe() }
            }
        }
    }

    fun setReaction(messageId: String, emoji: String) {
        val current = _reactions.value[messageId].orEmpty()
        val own = current.firstOrNull { it.userId == identity.myId }
        val desired = if (own?.emoji == emoji) null else emoji
        val token = UUID.randomUUID().toString()

        pendingOwnOperations[messageId] = PendingOwnOperation(token, desired)
        applyOwnReaction(messageId, desired)

        viewModelScope.launch {
            val result = if (desired == null) {
                reactionRepository.removeReaction(messageId)
            } else {
                reactionRepository.setReaction(messageId, desired)
            }

            val pending = pendingOwnOperations[messageId]
            if (pending?.token != token) return@launch

            if (result.isSuccess) {
                pendingOwnOperations.remove(messageId)
            } else {
                pendingOwnOperations.remove(messageId)
                reactionRepository.getReactions(identity.partnerId)
                    .onSuccess { all -> _reactions.value = all.groupBy { it.messageId } }
            }
        }
    }

    private suspend fun applyRealtimeAction(action: PostgresAction) {
        val row = when (action) {
            is PostgresAction.Insert -> runCatching { action.decodeRecord<SupabaseRealtimeReactionRow>() }.getOrNull()
            is PostgresAction.Update -> runCatching { action.decodeRecord<SupabaseRealtimeReactionRow>() }.getOrNull()
            is PostgresAction.Delete -> runCatching { action.decodeOldRecord<SupabaseRealtimeReactionRow>() }.getOrNull()
            is PostgresAction.Select -> null
        } ?: return

        val context = reactionRepository.resolveRealtimeContext(row.messageId, row.userId).getOrNull() ?: return
        val messageId = context.clientMessageId
        val userId = context.userPublicId

        // The local device already reflects its own operation optimistically.
        // Realtime echo events from this device are intentionally ignored.
        if (userId == identity.myId) return

        when (action) {
            is PostgresAction.Delete -> {
                val current = _reactions.value[messageId].orEmpty()
                    .filterNot { it.userId == userId }
                setMessageReactions(messageId, current)
            }
            is PostgresAction.Insert,
            is PostgresAction.Update -> {
                if (row.emoji.isBlank()) return
                val current = _reactions.value[messageId].orEmpty()
                val withoutUser = current.filterNot { it.userId == userId }
                setMessageReactions(
                    messageId,
                    withoutUser + ChatReaction(
                        messageId = messageId,
                        userId = userId,
                        emoji = row.emoji,
                        createdAt = parseTimestamp(row.createdAt)
                    )
                )
            }
            is PostgresAction.Select -> Unit
        }
    }

    private fun applyOwnReaction(messageId: String, desired: String?) {
        val current = _reactions.value[messageId].orEmpty()
        val withoutOwn = current.filterNot { it.userId == identity.myId }
        val updated = if (desired == null) {
            withoutOwn
        } else {
            withoutOwn + ChatReaction(
                messageId = messageId,
                userId = identity.myId,
                emoji = desired,
                createdAt = System.currentTimeMillis()
            )
        }
        setMessageReactions(messageId, updated)
    }

    private fun setMessageReactions(messageId: String, reactions: List<ChatReaction>) {
        val next = _reactions.value.toMutableMap()
        if (reactions.isEmpty()) next.remove(messageId) else next[messageId] = reactions
        _reactions.value = next
    }

    private fun parseTimestamp(value: String): Long =
        runCatching { java.time.Instant.parse(value).toEpochMilli() }
            .getOrElse { System.currentTimeMillis() }

    private data class PendingOwnOperation(
        val token: String,
        val desiredEmoji: String?
    )
}
