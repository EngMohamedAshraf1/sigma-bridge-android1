package com.sigmabridge.app.data.chat

import android.content.Context
import com.sigmabridge.app.domain.chat.ChatMessage
import com.sigmabridge.app.domain.chat.MessageDeliveryStatus
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

    @Synchronized
    fun save(conversationKey: String, messages: List<ChatMessage>) {
        val raw = json.encodeToString(serializer, messages.takeLast(MAX_MESSAGES))
        preferences.edit().putString(keyFor(conversationKey), raw).apply()
    }

    @Synchronized
    fun updateDeliveryStatus(
        conversationKey: String,
        messageId: String,
        status: MessageDeliveryStatus
    ): List<ChatMessage> {
        val updated = load(conversationKey).map { message ->
            if (message.id == messageId && isStatusUpgrade(message.deliveryStatus, status)) {
                message.copy(deliveryStatus = status)
            } else {
                message
            }
        }
        save(conversationKey, updated)
        return updated
    }

    @Synchronized
    fun markSent(conversationKey: String, messageId: String): List<ChatMessage> {
        val updated = load(conversationKey).map { message ->
            if (message.id == messageId && message.deliveryStatus == MessageDeliveryStatus.PENDING) {
                message.copy(deliveryStatus = MessageDeliveryStatus.SENT)
            } else {
                message
            }
        }
        save(conversationKey, updated)
        return updated
    }

    private fun isStatusUpgrade(current: MessageDeliveryStatus, requested: MessageDeliveryStatus): Boolean =
        requested.ordinal > current.ordinal

    private fun keyFor(value: String): String = "history_${sha256(value.trim())}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val PREFS_NAME = "sigma_bridge_chat_history"
        const val MAX_MESSAGES = 200
    }
}
