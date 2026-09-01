package com.sigmabridge.app.data.chat

import android.content.Context
import com.sigmabridge.app.domain.chat.ChatMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Small persistent outbox. Pending messages survive app restarts and network loss. */
@Singleton
class ChatOutboxStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val serializer = ListSerializer(ChatMessage.serializer())

    fun load(conversationKey: String): List<ChatMessage> {
        val raw = preferences.getString(keyFor(conversationKey), null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    fun save(conversationKey: String, messages: List<ChatMessage>) {
        val raw = json.encodeToString(serializer, messages)
        preferences.edit().putString(keyFor(conversationKey), raw).apply()
    }

    fun add(conversationKey: String, message: ChatMessage) {
        save(conversationKey, (load(conversationKey) + message).distinctBy { it.id })
    }

    fun remove(conversationKey: String, messageId: String) {
        save(conversationKey, load(conversationKey).filterNot { it.id == messageId })
    }

    private fun keyFor(value: String): String = "outbox_${value.trim()}"

    private companion object {
        const val PREFS_NAME = "sigma_bridge_chat_outbox"
    }
}
