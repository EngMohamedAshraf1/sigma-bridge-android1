package com.sigmabridge.app.data.chat

import android.content.Context
import com.sigmabridge.app.domain.chat.ChatMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Small local history store for the private chat. Keeps the last 200 visible messages per pairing. */
@Singleton
class ChatHistoryStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val serializer = ListSerializer(ChatMessage.serializer())

    fun load(pairingFingerprint: String): List<ChatMessage> {
        val raw = preferences.getString(keyFor(pairingFingerprint), null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    fun save(pairingFingerprint: String, messages: List<ChatMessage>) {
        val kept = messages.takeLast(MAX_MESSAGES)
        val raw = json.encodeToString(serializer, kept)
        preferences.edit().putString(keyFor(pairingFingerprint), raw).apply()
    }

    private fun keyFor(pairingFingerprint: String): String =
        "history_${sha256(pairingFingerprint.trim())}"

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PREFS_NAME = "sigma_bridge_chat"
        const val MAX_MESSAGES = 200
    }
}
