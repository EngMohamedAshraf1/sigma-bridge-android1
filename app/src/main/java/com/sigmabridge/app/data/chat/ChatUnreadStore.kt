package com.sigmabridge.app.data.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Tracks delivered incoming message IDs that have not been acknowledged as read yet. */
@Singleton
class ChatUnreadStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun addUnread(historyKey: String, messageId: String) {
        val ids = load(historyKey).toMutableSet()
        ids += messageId
        save(historyKey, ids)
    }

    @Synchronized
    fun load(historyKey: String): Set<String> =
        preferences.getStringSet(key(historyKey), emptySet()).orEmpty().toSet()

    @Synchronized
    fun remove(historyKey: String, messageId: String) {
        val ids = load(historyKey).toMutableSet()
        if (ids.remove(messageId)) save(historyKey, ids)
    }

    private fun save(historyKey: String, ids: Set<String>) {
        preferences.edit().putStringSet(key(historyKey), ids).apply()
    }

    private fun key(historyKey: String): String = "unread_$historyKey"

    private companion object {
        const val PREFS_NAME = "sigma_bridge_chat_unread"
    }
}
