package com.sigmabridge.app.domain.model

/**
 * A single incoming Telegram update, reduced to what the bridge cares
 * about. Message updates can carry voice or Telegram audio metadata;
 * callback_query updates use the callback fields instead.
 */
data class TelegramUpdate(
    val updateId: Long,
    val chatId: Long,
    val messageId: Long,
    val chatType: TelegramChatType,
    val senderUserId: Long?,
    val voiceFileId: String?,
    val audioFileId: String?,
    val audioMimeType: String?,
    val audioFileName: String?,
    val messageText: String?,
    val callbackQueryId: String?,
    val callbackData: String?
)
