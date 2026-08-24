package com.sigmabridge.app.data.chat

import android.content.Context
import com.sigmabridge.app.domain.chat.ChatMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Local chat history keyed by the two user identities, not by a room code. */
@Singleton
class ChatHistoryStore @Inject constructor(
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
        val raw = json.encodeToString(serializer, messages.takeLast(MAX_MESSAGES))
        preferences.edit().putString(keyFor(conversationKey), raw).apply()
    }

    private fun keyFor(value: String): String = "history_${sha256(value.trim())}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val PREFS_NAME = "sigma_bridge_chat_history"
        const val MAX_MESSAGES = 200
    }
}
