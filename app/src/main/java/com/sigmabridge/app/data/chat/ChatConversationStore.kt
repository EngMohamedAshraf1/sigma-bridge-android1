package com.sigmabridge.app.data.chat

import android.content.Context
import com.sigmabridge.app.domain.chat.ChatConversation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Local cache of server-owned Private Chat conversations. */
@Singleton
class ChatConversationStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val serializer = ListSerializer(ChatConversation.serializer())

    @Synchronized
    fun load(): List<ChatConversation> {
        val raw = preferences.getString(KEY_CONVERSATIONS, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.lastMessageAt }
    }

    @Synchronized
    fun upsert(conversation: ChatConversation) {
        val current = load().filterNot {
            if (conversation.conversationId.isNotBlank() && it.conversationId.isNotBlank()) {
                it.conversationId == conversation.conversationId
            } else {
                it.partnerId == conversation.partnerId
            }
        }
        save((current + conversation).sortedByDescending { it.lastMessageAt })
    }

    @Synchronized
    fun replaceAll(conversations: List<ChatConversation>) {
        save(
            conversations
                .filter { it.partnerId.isNotBlank() && it.conversationId.isNotBlank() }
                .distinctBy { it.conversationId }
                .sortedByDescending { it.lastMessageAt }
        )
    }

    @Synchronized
    fun updateName(partnerId: String, displayName: String) {
        val current = load().map {
            if (it.partnerId == partnerId) it.copy(displayName = displayName.trim().ifBlank { it.displayName }) else it
        }
        save(current)
    }

    @Synchronized
    fun remove(conversation: ChatConversation) {
        val current = load().filterNot {
            if (conversation.conversationId.isNotBlank() && it.conversationId.isNotBlank()) {
                it.conversationId == conversation.conversationId
            } else {
                it.partnerId == conversation.partnerId
            }
        }
        save(current)
    }

    private fun save(conversations: List<ChatConversation>) {
        preferences.edit()
            .putString(KEY_CONVERSATIONS, json.encodeToString(serializer, conversations.take(MAX_CONVERSATIONS)))
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "sigma_bridge_chat_conversations"
        const val KEY_CONVERSATIONS = "conversations"
        const val MAX_CONVERSATIONS = 100
    }
}
