package com.sigmabridge.app.data.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatPresenceStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getLastSeen(publicId: String): Long =
        if (publicId.isBlank()) 0L else preferences.getLong(key(publicId), 0L)

    fun setLastSeen(publicId: String, timestamp: Long) {
        if (publicId.isBlank() || timestamp <= 0L) return
        preferences.edit().putLong(key(publicId), timestamp).apply()
    }

    private fun key(publicId: String): String = "last_seen_$publicId"

    private companion object {
        const val PREFERENCES_NAME = "sigma_bridge_chat_presence"
    }
}
