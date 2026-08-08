package com.sigmabridge.app.domain.model

/**
 * A single incoming Telegram update, reduced to what the bridge cares
 * about. [messageId] is the voice message's own id — needed to reply in a
 * thread (reply_to_message_id) rather than as a bare new message, which
 * matters most in groups where multiple people may be sending voice notes.
 * [chatType]/[senderUserId] are captured for future per-group/per-user
 * language phases (see TelegramChatType); Phase 9.0 does not use them for
 * any decision.
 */
data class TelegramUpdate(
    val updateId: Long,
    val chatId: Long,
    val messageId: Long,
    val chatType: TelegramChatType,
    val senderUserId: Long?,
    val voiceFileId: String?,
    val messageText: String?
)
