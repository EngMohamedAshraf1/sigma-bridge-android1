package com.sigmabridge.app.domain.model

/**
 * A single incoming Telegram update, reduced to what the bridge cares
 * about. Full message metadata (edited messages, other content types) is
 * out of scope until a phase actually needs it — this mirrors handlers.py,
 * which only ever looked at message.voice.file_id.
 */
data class TelegramUpdate(
    val updateId: Long,
    val chatId: Long,
    val voiceFileId: String?
)
