package com.sigmabridge.app.domain.model

/**
 * A single incoming Telegram update, reduced to what the bridge cares
 * about. Message updates can carry voice, audio, or on-demand video metadata;
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
    val audioFileSizeBytes: Long?,
    val photoFileId: String?,
    val photoFileSizeBytes: Long?,
    val photoMessageId: Long?,
    val photoTranslateRequested: Boolean,
    val videoFileId: String?,
    val videoMimeType: String?,
    val videoFileName: String?,
    val videoFileSizeBytes: Long?,
    val videoMessageId: Long?,
    val videoTranslateRequested: Boolean,
    val messageText: String?,
    val callbackQueryId: String?,
    val callbackData: String?
)
