package com.sigmabridge.app.data.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Persists the last server sequence observed by the background chat worker. */
@Singleton
class ChatSyncCursorStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(conversationId: String): Long = preferences.getLong(keyFor(conversationId), 0L)

    fun save(conversationId: String, sequenceNumber: Long) {
        preferences.edit().putLong(keyFor(conversationId), sequenceNumber).apply()
    }

    private fun keyFor(conversationId: String): String = "cursor_${conversationId.trim()}"

    private companion object {
        const val PREFS_NAME = "sigma_bridge_chat_sync_cursors"
    }
}
