package com.sigmabridge.app.data.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Persists the lightweight reply relationship outside the encrypted message body. */
@Singleton
class ChatReplyStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setPendingReply(replyToMessageId: String?) {
        if (replyToMessageId.isNullOrBlank()) {
            prefs.edit().remove(KEY_PENDING).apply()
        } else {
            prefs.edit().putString(KEY_PENDING, replyToMessageId.trim()).apply()
        }
    }

    fun consumePendingReply(): String? {
        val value = prefs.getString(KEY_PENDING, null)?.trim()?.takeIf(String::isNotBlank)
        prefs.edit().remove(KEY_PENDING).apply()
        return value
    }

    fun setReplyTo(messageId: String, replyToMessageId: String) {
        prefs.edit().putString(KEY_REPLY_PREFIX + messageId, replyToMessageId).apply()
    }

    fun getReplyTo(messageId: String): String? =
        prefs.getString(KEY_REPLY_PREFIX + messageId, null)?.takeIf(String::isNotBlank)

    private companion object {
        const val PREFS_NAME = "sigma_bridge_chat_replies"
        const val KEY_PENDING = "pending_reply_to"
        const val KEY_REPLY_PREFIX = "reply_to_"
    }
}
