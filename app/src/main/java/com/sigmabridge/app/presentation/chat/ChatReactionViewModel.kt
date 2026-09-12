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
import io.github.jan.supabase.realtime.decodeOldRecord
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
    private var syncJob: Job? = null
    private var activePartnerId: String? = null

    init {
        watchForPartner()
    }

    /**
     * The chat identity/partner can be populated after this ViewModel is created.
     * Keep the reaction layer dormant until a valid partner exists.
     */
    private fun watchForPartner() {
        viewModelScope.launch {
            while (isActive) {
                val partner = identity.partnerId.trim()
                val valid = partner.isNotBlank() && partner != identity.myId
                if (valid && partner != activePartnerId) {
                    activePartnerId = partner
                    restartSync(partner)
                } else if (!valid && activePartnerId != null) {
                    activePartnerId = null
                    stopSync()
                    _reactions.value = emptyMap()
                }
                delay(PARTNER_CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Reactions use two complementary paths:
     * 1) a Realtime subscription for immediate remote changes;
     * 2) a small periodic snapshot sync so a missed Realtime event or a screen
     *    recreation cannot make persisted reactions disappear from the UI.
     */
    private fun restartSync(partner: String) {
        stopSync()

        realtimeJob = viewModelScope.launch {
            val conversationKey = identity.conversationKeyFor(partner)
                .joinToString("") { "%02x".format(it) }
            val channel = supabase.channel("sigma-chat-reactions-$conversationKey")
            val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "message_reactions"
            }

            val collector = launch {
                changes.collect { applyRealtimeAction(it) }
            }

            try {
                channel.subscribe(blockUntilSubscribed = true)
                // Do not rely on Realtime for the first load.
                syncSnapshot(partner)
                collector.join()
            } finally {
                collector.cancel()
                runCatching { channel.unsubscribe() }
            }
        }

        syncJob = viewModelScope.launch {
            // This covers both app/screen re-entry and missed Realtime events.
            syncSnapshot(partner)
            while (isActive && activePartnerId == partner) {
                delay(SNAPSHOT_SYNC_INTERVAL_MS)
                syncSnapshot(partner)
            }
        }
    }

    private fun stopSync() {
        realtimeJob?.cancel()
        realtimeJob = null
        syncJob?.cancel()
        syncJob = null
    }

    private suspend fun syncSnapshot(partner: String) {
        reactionRepository.getReactions(partner)
            .onSuccess { all ->
                _reactions.value = mergeServerSnapshot(all)
            }
    }

    private fun mergeServerSnapshot(all: List<ChatReaction>): Map<String, List<ChatReaction>> {
        val merged = all.groupBy { it.messageId }
            .mapValues { (_, values) -> values.toMutableList() }
            .toMutableMap()

        pendingOwnOperations.forEach { (messageId, pending) ->
            val withoutOwn = merged[messageId].orEmpty()
                .filterNot { it.userId == identity.myId }

            if (pending.desiredEmoji == null) {
                if (withoutOwn.isEmpty()) merged.remove(messageId)
                else merged[messageId] = withoutOwn.toMutableList()
            } else {
                merged[messageId] = (
                    withoutOwn + ChatReaction(
                        messageId = messageId,
                        userId = identity.myId,
                        emoji = pending.desiredEmoji,
                        createdAt = System.currentTimeMillis()
                    )
                ).toMutableList()
            }
        }

        return merged.mapValues { (_, values) -> values.toList() }
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

            if (pendingOwnOperations[messageId]?.token != token) return@launch

            if (result.isSuccess) {
                pendingOwnOperations.remove(messageId)
            } else {
                pendingOwnOperations.remove(messageId)
                val partner = activePartnerId ?: identity.partnerId.trim()
                if (partner.isNotBlank()) syncSnapshot(partner)
            }
        }
    }

    private suspend fun applyRealtimeAction(action: PostgresAction) {
        val row = when (action) {
            is PostgresAction.Insert -> runCatching {
                action.decodeRecord<SupabaseRealtimeReactionRow>()
            }.getOrNull()
            is PostgresAction.Update -> runCatching {
                action.decodeRecord<SupabaseRealtimeReactionRow>()
            }.getOrNull()
            is PostgresAction.Delete -> runCatching {
                action.decodeOldRecord<SupabaseRealtimeReactionRow>()
            }.getOrNull()
            is PostgresAction.Select -> null
        } ?: return

        val context = reactionRepository
            .resolveRealtimeContext(row.messageId, row.userId)
            .getOrNull()
            ?: return

        if (context.userPublicId == identity.myId) return

        val current = _reactions.value[context.clientMessageId].orEmpty()
        when (action) {
            is PostgresAction.Delete -> setMessageReactions(
                context.clientMessageId,
                current.filterNot { it.userId == context.userPublicId }
            )

            is PostgresAction.Insert,
            is PostgresAction.Update -> {
                if (row.emoji.isBlank()) return
                val withoutUser = current.filterNot { it.userId == context.userPublicId }
                setMessageReactions(
                    context.clientMessageId,
                    withoutUser + ChatReaction(
                        messageId = context.clientMessageId,
                        userId = context.userPublicId,
                        emoji = row.emoji,
                        createdAt = parseTimestamp(row.createdAt)
                    )
                )
            }

            is PostgresAction.Select -> Unit
        }
    }

    private fun applyOwnReaction(messageId: String, desired: String?) {
        val withoutOwn = _reactions.value[messageId].orEmpty()
            .filterNot { it.userId == identity.myId }
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

    private companion object {
        const val PARTNER_CHECK_INTERVAL_MS = 500L
        const val SNAPSHOT_SYNC_INTERVAL_MS = 3000L
    }
}
