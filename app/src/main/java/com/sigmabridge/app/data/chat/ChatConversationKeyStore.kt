package com.sigmabridge.app.data.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local cache of server-owned Private Chat conversation keys.
 *
 * Keys are not derived from public account identifiers. A new device obtains
 * them after authenticated account restoration through Supabase.
 */
@Singleton
class ChatConversationKeyStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun get(conversationId: String): String? =
        preferences.getString(keyFor(conversationId), null)?.takeIf { it.isNotBlank() }

    @Synchronized
    fun put(conversationId: String, keyMaterial: String) {
        if (conversationId.isBlank() || keyMaterial.isBlank()) return
        preferences.edit().putString(keyFor(conversationId), keyMaterial.trim()).apply()
    }

    @Synchronized
    fun putAll(entries: Map<String, String>) {
        val editor = preferences.edit()
        entries.forEach { (conversationId, keyMaterial) ->
            if (conversationId.isNotBlank() && keyMaterial.isNotBlank()) {
                editor.putString(keyFor(conversationId), keyMaterial.trim())
            }
        }
        editor.apply()
    }

    private fun keyFor(conversationId: String): String =
        "conversation_key_" + conversationId.trim()

    private companion object {
        const val PREFS_NAME = "sigma_bridge_chat_conversation_keys"
    }
}
