package com.sigmabridge.app.data.chat

import android.content.Context
import com.sigmabridge.app.domain.chat.ChatConversation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Stores the user's private-chat list separately from message history. */
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
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
            .sortedByDescending { it.lastMessageAt }
    }

    @Synchronized
    fun upsert(conversation: ChatConversation) {
        val current = load().filterNot { it.partnerId == conversation.partnerId }
        val updated = (current + conversation).sortedByDescending { it.lastMessageAt }
        save(updated)
    }

    @Synchronized
    fun updateName(partnerId: String, displayName: String) {
        val current = load().map {
            if (it.partnerId == partnerId) it.copy(displayName = displayName.trim().ifBlank { partnerId }) else it
        }
        save(current)
    }

    @Synchronized
    fun remove(partnerId: String) {
        save(load().filterNot { it.partnerId == partnerId })
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
