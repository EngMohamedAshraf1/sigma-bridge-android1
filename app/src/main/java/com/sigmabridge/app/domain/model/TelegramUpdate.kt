package com.sigmabridge.app.domain.model

/**
 * A single incoming Telegram update, reduced to what the bridge cares
 * about. [messageId] is the voice message's own id — needed to reply in a
 * thread (reply_to_message_id) rather than as a bare new message, which
 * matters most in groups where multiple people may be sending voice notes.
 * [chatType]/[senderUserId] are captured for future per-group/per-user
 * language phases (see TelegramChatType); Phase 9.0 does not use them for
 * any decision.
 *
 * Phase 9.8: this same type now also represents a callback_query update
 * (an inline keyboard button tap), not just a message. For that case,
 * [chatId]/[messageId] identify the message the keyboard is attached to
 * (so it can be edited in place), [senderUserId] is who tapped, and
 * [callbackQueryId]/[callbackData] are populated while [voiceFileId]/
 * [messageText] stay null. A message-based update is the mirror image:
 * [callbackQueryId]/[callbackData] stay null. This mirrors how the same
 * type already carries either a voice update or a text-command update.
 */
data class TelegramUpdate(
    val updateId: Long,
    val chatId: Long,
    val messageId: Long,
    val chatType: TelegramChatType,
    val senderUserId: Long?,
    val voiceFileId: String?,
    val messageText: String?,
    val callbackQueryId: String?,
    val callbackData: String?
)
